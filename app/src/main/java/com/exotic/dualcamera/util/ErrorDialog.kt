package com.exotic.dualcamera.util

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class ErrorDialog : DialogFragment(){
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(activity)
            .setMessage(arguments?.getString(MESSAGE))
            .setPositiveButton(android.R.string.ok){_, _ -> activity?.finish() }
            .create()

    companion object{
        @JvmStatic private val MESSAGE = "message"

        @JvmStatic fun newInstance(message: String): ErrorDialog = ErrorDialog().apply{
            arguments = Bundle().apply{putString(MESSAGE, message)}
        }
    }
}