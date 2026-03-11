package com.forge.app.build_engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

class PackageInstallReceiver : BroadcastReceiver() {
    companion object {
        const val INSTALL_RESULT_ACTION = "com.forge.app.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        private const val TAG = "PackageInstaller"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        Log.d(TAG, "Install status: $status, package: $packageName, message: $statusMessage")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }

            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmIntent)
                Log.d(TAG, "Launched install confirmation UI")
            } else {
                Log.e(TAG, "Pending user action without confirmation intent")
            }

            broadcastResult(context, packageName, false, status, statusMessage)
            return
        }

        val success = status == PackageInstaller.STATUS_SUCCESS

        broadcastResult(context, packageName, success, status, statusMessage)

        // Auto-launch on success
        if (success && packageName != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        }
    }

    private fun broadcastResult(
        context: Context,
        packageName: String?,
        success: Boolean,
        status: Int,
        statusMessage: String?
    ) {
        val resultIntent = Intent(INSTALL_RESULT_ACTION).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
        }
        context.sendBroadcast(resultIntent)
    }
}
