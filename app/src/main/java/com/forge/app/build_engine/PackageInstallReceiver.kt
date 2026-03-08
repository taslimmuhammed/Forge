package com.forge.app.build_engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class PackageInstallReceiver : BroadcastReceiver() {
    companion object {
        const val INSTALL_RESULT_ACTION = "com.forge.app.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_SUCCESS = "success"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val success = status == PackageInstaller.STATUS_SUCCESS

        Log.d("PackageInstaller", "Install status: $status, package: $packageName")

        // Broadcast result to UI
        val resultIntent = Intent(INSTALL_RESULT_ACTION).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_SUCCESS, success)
        }
        context.sendBroadcast(resultIntent)

        // Auto-launch on success
        if (success && packageName != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        }
    }
}