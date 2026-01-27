package com.vox.android.control.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vox.android.MainActivity
import com.vox.android.R
import com.vox.android.VoxAccessibilityService

/**
 * Foreground service for running the remote control server.
 * Keeps the WebSocket server alive when the app is in the background.
 */
class RemoteControlService : Service() {

    companion object {
        private const val TAG = "Vox"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vox_remote_control"

        const val ACTION_START = "com.vox.android.START_REMOTE"
        const val ACTION_STOP = "com.vox.android.STOP_REMOTE"

        const val EXTRA_PORT = "port"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MODEL = "model"

        private var instance: RemoteControlService? = null
        fun getInstance(): RemoteControlService? = instance

        fun isRunning(): Boolean = instance?.server?.isRunning == true

        /**
         * Start the remote control service.
         */
        fun start(
            context: Context,
            port: Int = 8080,
            authToken: String? = null,
            apiKey: String? = null,
            model: String = "anthropic/claude-opus-4.5"
        ) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_AUTH_TOKEN, authToken)
                putExtra(EXTRA_API_KEY, apiKey)
                putExtra(EXTRA_MODEL, model)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the remote control service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, RemoteControlService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var server: RemoteControlServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "RemoteControlService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN)
                val apiKey = intent.getStringExtra(EXTRA_API_KEY)
                val model = intent.getStringExtra(EXTRA_MODEL) ?: "anthropic/claude-opus-4.5"

                startServer(port, authToken, apiKey, model)
            }
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        instance = null
        Log.d(TAG, "RemoteControlService destroyed")
    }

    private fun startServer(port: Int, authToken: String?, apiKey: String?, model: String) {
        val accessibilityService = VoxAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "Cannot start server: accessibility service not running")
            stopSelf()
            return
        }

        val config = RemoteServerConfig(
            port = port,
            authToken = authToken,
            apiKey = apiKey,
            model = model
        )

        server = RemoteControlServer(
            stateProvider = accessibilityService.getStateProvider(),
            actionExecutor = accessibilityService.getActionExecutor(),
            config = config
        )

        server?.start()

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification(port, server?.clientCount ?: 0))
        Log.d(TAG, "Remote control server started on port $port")
    }

    private fun stopServer() {
        server?.stop()
        server = null
        Log.d(TAG, "Remote control server stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when remote control is active"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(port: Int, clientCount: Int): Notification {
        // Open app intent
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Stop intent
        val stopIntent = Intent(this, RemoteControlService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Control Active")
            .setContentText("Listening on port $port • $clientCount clients")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    /**
     * Update notification with current client count.
     */
    fun updateNotification() {
        val port = server?.port ?: 8080
        val notification = createNotification(port, server?.clientCount ?: 0)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
