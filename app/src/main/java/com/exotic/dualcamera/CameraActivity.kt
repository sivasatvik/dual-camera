package com.exotic.dualcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.exotic.dualcamera.util.*
import java.io.File
import java.lang.NullPointerException
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraActivity : Fragment(), View.OnClickListener,
    ActivityCompat.OnRequestPermissionsResultCallback{

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener{

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int){
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture?,
            width: Int,
            height: Int){
            configureChange(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) = Unit
    }

    private lateinit var mCameraId: String

    private var mCameraDevice: CameraDevice? = null

    private lateinit var mTextureView: TextureViewAuto

    private var mCaptureSession: CameraCaptureSession? = null

    private lateinit var mPreviewRequestBuilder: CaptureRequest.Builder

    private lateinit var mPreviewRequest: CaptureRequest

    private var mState = STATE_PREVIEW

    private val mCameraLock = Semaphore(1)

    private var mFlashSupoorted = false

    private var mSensorOrientation = 0

    private lateinit var mPreviewSize: Size

    private var mBackgroundThread: HandlerThread? = null

    private var mBackgroundHandler: Handler? = null

    private var mImageReader: ImageReader? = null

    private lateinit var mFile: File

    private var mCameraStateCallBack = object : CameraDevice.StateCallback(){

        override fun onOpened(camera: CameraDevice) {
            mCameraLock.release()
            this@CameraActivity.mCameraDevice = camera
            createPreviewSession()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            onDisconnected(camera)
            this@CameraActivity.activity?.finish()
        }

        override fun onDisconnected(camera: CameraDevice) {
            mCameraLock.release()
            mCameraDevice?.close()
            this@CameraActivity.mCameraDevice = null
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener{
        mBackgroundHandler?.post(SaveImage(it.acquireNextImage(), mFile))
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback(){

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult){
            captureProcess(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult){
            captureProcess(result)
        }

        private fun captureProcess(result: CaptureResult){
            when(mState){
                STATE_PREVIEW -> Unit
                STATE_WAITING_AF_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if(aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                                aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED){
                        mState = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if(aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE){
                        mState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private fun capturePicture(result: CaptureResult){
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if(afState == null) {
                captureStillPicture()
            }
            else if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED){
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if(aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_CONVERGED){
                    mState = STATE_PICTURE_TAKEN
                    captureStillPicture()
                }
                else {
                    runPreCaptureSequence()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.activity_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.snap).setOnClickListener(this)
        mTextureView = view.findViewById(R.id.texture)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        mFile = File(activity?.getExternalFilesDir(null),
            IMG_NAME
        )
    }

    override fun onResume() {
        super.onResume()

        startBackgroundThread()

        if(mTextureView.isAvailable){
            openCamera(mTextureView.width, mTextureView.height)
        }
        else{
            mTextureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun requestCameraPermission(){
        if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        }
        else{
            requestPermissions(arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray){
        if(requestCode == REQUEST_CAMERA_PERMISSION){
            if(grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED){
                ErrorDialog.newInstance(getString(R.string.request_permission))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        }
        else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun createCamera(width: Int, height: Int){
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try{
            for(cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                val max = Collections.max(
                    Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                    CompareSizesByArea())
                mImageReader = ImageReader.newInstance(max.width, max.height,
                    ImageFormat.JPEG, 2).apply{
                    setOnImageAvailableListener(onImageAvailableListener, mBackgroundHandler)
                }

                val rotation = activity.windowManager.defaultDisplay.rotation

                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

                val swapDimension = isDimensionSwap(rotation)

                val displaySize = Point()
                activity.windowManager.defaultDisplay.getSize(displaySize)
                val rotateWidth = if(swapDimension) height else width
                val rotateHeight = if(swapDimension) width else height
                var maxWidth = if (swapDimension) displaySize.y else displaySize.x
                var maxHeight = if (swapDimension) displaySize.x else displaySize.y

                if(maxWidth > MAX_PREVIEW_WIDTH) maxWidth = MAX_PREVIEW_WIDTH
                if(maxHeight > MAX_PREVIEW_HEIGHT) maxHeight = MAX_PREVIEW_HEIGHT

                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                    rotateWidth, rotateHeight,
                    maxWidth, maxHeight,
                    max)

                if(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
                    mTextureView.setAspectRatio(mPreviewSize.width, mPreviewSize.height)
                }
                else{
                    mTextureView.setAspectRatio(mPreviewSize.height, mPreviewSize.width)
                }

                mFlashSupoorted =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                mCameraId = cameraId

                return
            }
        }
        catch(e: CameraAccessException){
            Log.e(TAG, e.toString())
        }
        catch(e: NullPointerException){
            ErrorDialog.newInstance(getString(R.string.err))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        }
    }

    private fun isDimensionSwap(rotation: Int): Boolean{
        var swapDimension = false
        when(rotation){
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if(mSensorOrientation == 90 || mSensorOrientation == 270){
                    swapDimension = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if(mSensorOrientation == 0 || mSensorOrientation == 180){
                    swapDimension = true
                }
            }
            else -> {
                Log.e(TAG, "Rotation is invalid: $rotation")
            }
        }
        return swapDimension
    }

    private fun openCamera(width: Int, height: Int){
        val permission = activity?.let{ContextCompat.checkSelfPermission(it, Manifest.permission.CAMERA)}
        if(permission != PackageManager.PERMISSION_GRANTED){
            requestCameraPermission()
            return
        }

        createCamera(width, height)
        configureChange(width, height)

        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try{
            if(!mCameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)){
                throw RuntimeException("Time out in acquiring lock.")
            }
            manager.openCamera(mCameraId, mCameraStateCallBack, mBackgroundHandler)
        }
        catch(e: CameraAccessException){
            Log.e(TAG, e.toString())
        }
        catch(e: InterruptedException){
            throw RuntimeException("Interrupt while trying to acquire lock (opening).", e)
        }
    }

    private fun closeCamera(){
        try{
            mCameraLock.acquire()
            mCaptureSession?.close()
            mCaptureSession = null
            mCameraDevice = null
            mImageReader?.close()
            mImageReader = null
        }
        catch(e: InterruptedException){
            throw RuntimeException("Interrupt while trying to acquire lock (closing).", e)
        }
        finally{
            mCameraLock.release()
        }
    }

    private fun startBackgroundThread(){
        mBackgroundThread = HandlerThread("CameraBackground").also{it.start()}
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    private fun stopBackgroundThread(){
        mBackgroundThread?.quitSafely()
        try{
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        }
        catch(e: InterruptedException){
            Log.e(TAG, e.toString())
        }
    }

    private fun createPreviewSession() {
        try {
            val texture = mTextureView.surfaceTexture

            texture.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)

            val surface = Surface(texture)

            mPreviewRequestBuilder = mCameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )

            mPreviewRequestBuilder.addTarget(surface)

            mCameraDevice?.createCaptureSession(Arrays.asList(surface, mImageReader?.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        if (mCameraDevice == null) return

                        mCaptureSession = captureSession
                        try {
                            mPreviewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )

                            setFlashAuto(mPreviewRequestBuilder)

                            mPreviewRequest = mPreviewRequestBuilder.build()
                            mCaptureSession?.setRepeatingRequest(
                                mPreviewRequest,
                                captureCallback, mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, e.toString())
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        activity?.showToast("Failed")
                    }
                }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun configureChange(viewWidth: Int, viewHeight: Int){
        activity?:return
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, mPreviewSize.height.toFloat(), mPreviewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if(Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation){
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                viewHeight.toFloat()/mPreviewSize.height,
                viewHeight.toFloat()/mPreviewSize.width)

            with(matrix){
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        else if(Surface.ROTATION_180 == rotation){
            matrix.postRotate(180f, centerX, centerY)
        }

        mTextureView.setTransform(matrix)
    }

    private fun focusLock(){
        try{
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START)

            mState = STATE_WAITING_AF_LOCK
            mCaptureSession?.capture(mPreviewRequestBuilder.build(), captureCallback,
                mBackgroundHandler)
        }
        catch(e:CameraAccessException){
            Log.e(TAG, e.toString())
        }
    }

    private fun runPreCaptureSequence(){
        try{
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            mState = STATE_WAITING_PRECAPTURE
            mCaptureSession?.capture(mPreviewRequestBuilder.build(), captureCallback,
                mBackgroundHandler)
        }
        catch(e: CameraAccessException){
            Log.e(TAG, e.toString())
        }
    }

    private fun captureStillPicture(){
        try{
            if(activity == null || mCameraDevice == null) return
            val rotation = activity!!.windowManager.defaultDisplay.rotation

            val captureBuilder = mCameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply{
                mImageReader?.surface?.let{addTarget(it)}

                set(CaptureRequest.JPEG_ORIENTATION,
                    (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360)

                set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }?.also{setFlashAuto(it)}

            val captureCallback = object : CameraCaptureSession.CaptureCallback(){

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult){
                    activity!!.showToast("Saved: $mFile")
                    Log.d(TAG, mFile.toString())
                    focusUnlock()
                }
            }
            mCaptureSession?.apply{
                stopRepeating()
                abortCaptures()
                captureBuilder?.build()?.let{capture(it, captureCallback, null)}
            }
        }
        catch(e: CameraAccessException){
            Log.e(TAG, e.toString())
        }
    }

    private fun focusUnlock(){
        try{
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            setFlashAuto(mPreviewRequestBuilder)
            mCaptureSession?.setRepeatingRequest(mPreviewRequest, captureCallback,
                mBackgroundHandler)
        }
        catch(e: CameraAccessException){
            Log.e(TAG, e.toString())
        }
    }

    override fun onClick(p0: View?) {
        when(view?.id){
            R.id.snap -> focusLock()
        }
    }

    private fun setFlashAuto(requestBuilder: CaptureRequest.Builder){
        if(mFlashSupoorted){
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
    }

    companion object{

        private val ORIENTATIONS = SparseIntArray()
        private const val FRAGMENT_DIALOG = "dialog"

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        private const val TAG = "DualCameraKotlin"

        private const val STATE_PREVIEW = 0

        private const val STATE_WAITING_AF_LOCK = 1

        private const val STATE_WAITING_PRECAPTURE = 2

        private const val STATE_WAITING_NON_PRECAPTURE = 3

        private const val STATE_PICTURE_TAKEN = 4

        private const val MAX_PREVIEW_WIDTH = 1920

        private const val MAX_PREVIEW_HEIGHT = 1080

        @JvmStatic private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size): Size {

            //TODO: write the flow for choosing best size
            return choices[0]
        }

        @JvmStatic fun newInstance(): CameraActivity = CameraActivity()
    }
}