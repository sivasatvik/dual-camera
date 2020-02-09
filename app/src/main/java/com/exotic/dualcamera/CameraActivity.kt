package com.exotic.dualcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.File
import java.util.concurrent.Semaphore

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
            configureChanged(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) = Unit
    }

    private var mCameraId: String

    private var mCameraDevice: CameraDevice? = null

    private lateinit var mTextureView: TextureViewAuto

    private var mCreateCaptureSession: CameraCaptureSession? = null

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
                STATE_WAITING_LOCK -> capturePicture(result)
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
        mFile = File(activity?.getExternalFilesDir(null), IMG_NAME)
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

    private fun onRequestCameraPermission(){
        if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        }
        else{
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
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
}