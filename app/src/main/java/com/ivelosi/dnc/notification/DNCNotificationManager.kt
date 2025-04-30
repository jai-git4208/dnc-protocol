package com.ivelosi.dnc.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ivelosi.dnc.MainActivity
import com.ivelosi.dnc.R

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 * 
 * Manages all notifications for the DNC application, including foreground service
 * notifications that keep the app running in the background.
 */
class DNCNotificationManager(private val context: Context) {

    companion object {
        // Notification channels
        const val CHANNEL_ID_SERVICE = "dnc_service_channel"
        const val CHANNEL_ID_DISCOVERY = "dnc_discovery_channel"
        const val CHANNEL_ID_MESSAGES = "dnc_messages_channel"
        const val CHANNEL_ID_CONNECTION = "dnc_connection_channel"
        
        // Notification IDs
        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_DISCOVERY = 1002
        const val NOTIFICATION_ID_CONNECTION = 1003
        const val NOTIFICATION_ID_MESSAGE_BASE = 2000
        
        // Intent actions
        const val ACTION_STOP_SERVICE = "com.ivelosi.dnc.STOP_SERVICE"
        const val ACTION_SCAN_DEVICES = "com.ivelosi.dnc.SCAN_DEVICES"
        const val ACTION_SHOW_NETWORK_INFO = "com.ivelosi.dnc.SHOW_NETWORK_INFO"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    /**
     * Create all notification channels required by the app
     */
    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service channel - for foreground service
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "DNC Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the DNC service running in the background"
                setShowBadge(false)
            }
            
            // Discovery channel - for device discovery updates
            val discoveryChannel = NotificationChannel(
                CHANNEL_ID_DISCOVERY,
                "Device Discovery",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Updates about device discovery process"
                setShowBadge(true)
            }
            
            // Messages channel - for incoming messages
            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "DNC Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming messages"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
            }
            
            // Connection channel - for connection events
            val connectionChannel = NotificationChannel(
                CHANNEL_ID_CONNECTION,
                "DNC Connections",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Updates about device connections and socket server"
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannels(
                listOf(serviceChannel, discoveryChannel, messagesChannel, connectionChannel)
            )
        }
    }
    
    /**
     * Create a foreground service notification for background running
     */
    fun createServiceNotification(isScanning: Boolean = false, connectedDevices: Int = 0): Notification {
        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Stop service action
        val stopIntent = Intent(ACTION_STOP_SERVICE)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Scan devices action
        val scanIntent = Intent(ACTION_SCAN_DEVICES)
        val scanPendingIntent = PendingIntent.getBroadcast(
            context, 0, scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Network info action
        val infoIntent = Intent(ACTION_SHOW_NETWORK_INFO)
        val infoPendingIntent = PendingIntent.getBroadcast(
            context, 0, infoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Status text based on current activity
        val statusText = when {
            isScanning -> "Currently scanning for devices"
            connectedDevices > 0 -> "Connected to $connectedDevices device(s)"
            else -> "Socket server ready for connections"
        }
        
        // Build the notification
        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setContentTitle("DNC Service Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // You should replace with your own icon
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_search, "Scan", scanPendingIntent)
            .addAction(android.R.drawable.ic_menu_info_details, "Info", infoPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Update the foreground service notification
     */
    fun updateServiceNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID_SERVICE, notification)
    }
    
    /**
     * Create a notification for device discovery events
     */
    fun showDiscoveryNotification(message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DISCOVERY)
            .setContentTitle("DNC Discovery")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your own icon
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(NOTIFICATION_ID_DISCOVERY, notification)
    }
    
    /**
     * Create a notification for connection events
     */
    fun showConnectionNotification(title: String, message: String) {
        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CONNECTION)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your own icon
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
            
        notificationManager.notify(NOTIFICATION_ID_CONNECTION, notification)
    }
    
    /**
     * Create a notification for incoming messages
     */
    fun showMessageNotification(senderName: String, message: String) {
        val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setContentTitle("Message from $senderName")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your own icon
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            
        // Use a unique ID for each message notification
        val notificationId = NOTIFICATION_ID_MESSAGE_BASE + senderName.hashCode() % 1000
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * Show a socket server status notification
     */
    fun showSocketServerNotification(serverPort: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CONNECTION)
            .setContentTitle("DNC Socket Server")
            .setContentText("Server running on port $serverPort")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your own icon
            .setAutoCancel(false)
            .build()
            
        notificationManager.notify(NOTIFICATION_ID_CONNECTION, notification)
    }
    
    /**
     * Cancel all notifications
     */
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
} 