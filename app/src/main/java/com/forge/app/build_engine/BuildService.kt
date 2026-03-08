package com.forge.app.build_engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.forge.app.ForgeApplication
import com.forge.app.MainActivity
import com.forge.app.R
import com.forge.app.data.models.BuildResult
import com.forge.app.data.repository.ProjectFileManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class BuildService : Service() {

    companion object {
        const val ACTION_START_BUILD = "com.forge.app.START_BUILD"
        const val ACTION_CANCEL_BUILD = "com.forge.app.CANCEL_BUILD"
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_PACKAGE_NAME = "package_name"

        // Shared flow so Activities can observe build events even when in background
        private val _buildEvents = MutableSharedFlow<BuildServiceEvent>(replay = 0, extraBufferCapacity = 64)
        val buildEvents: SharedFlow<BuildServiceEvent> = _buildEvents.asSharedFlow()

        fun startBuild(context: Context, projectId: String, packageName: String) {
            val intent = Intent(context, BuildService::class.java).apply {
                action = ACTION_START_BUILD
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            context.startForegroundService(intent)
        }

        fun cancelBuild(context: Context) {
            context.startService(Intent(context, BuildService::class.java).apply {
                action = ACTION_CANCEL_BUILD
            })
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var buildJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BUILD -> {
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: return START_NOT_STICKY
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_NOT_STICKY

                startForeground(ForgeApplication.BUILD_NOTIFICATION_ID, buildNotification("Starting build..."))
                startBuildJob(projectId, packageName)
            }
            ACTION_CANCEL_BUILD -> {
                buildJob?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startBuildJob(projectId: String, packageName: String) {
        buildJob = serviceScope.launch {
            try {
                // Dynamically load the FileManager for this project
                val prefs = getSharedPreferences("forge_build_tmp", Context.MODE_PRIVATE)
                // FileManager needs a project object — emit a signal to the ViewModel to handle the build
                // The service's role is purely to keep the process alive as a foreground service
                _buildEvents.emit(BuildServiceEvent.BuildStarted(projectId, packageName))

                // Keep alive — the actual build is driven by BuildEngine in the ViewModel coroutine
                // Service stops when ViewModel signals completion
                var alive = true
                while (alive && isActive) {
                    delay(500)
                }
            } catch (e: CancellationException) {
                _buildEvents.emit(BuildServiceEvent.BuildCancelled(projectId))
            } finally {
                stopSelf()
            }
        }
    }

    fun signalBuildComplete() {
        buildJob?.cancel()
    }

    private fun buildNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BuildService::class.java).apply { action = ACTION_CANCEL_BUILD },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ForgeApplication.BUILD_CHANNEL_ID)
            .setContentTitle("⚡ Forge Building")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

sealed class BuildServiceEvent {
    data class BuildStarted(val projectId: String, val packageName: String) : BuildServiceEvent()
    data class BuildCancelled(val projectId: String) : BuildServiceEvent()
}