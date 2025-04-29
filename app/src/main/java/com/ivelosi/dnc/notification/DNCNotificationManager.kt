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
        
        // Notification IDs
        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_DISCOVERY = 1002
        const val NOTIFICATION_ID_MESSAGE_BASE = 2000
        
        // Intent actions
        const val ACTION_STOP_SERVICE = "com.ivelosi.dnc.STOP_SERVICE"
        const val ACTION_SCAN_DEVICES = "com.ivelosi.dnc.SCAN_DEVICES"
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
            
            notificationManager.createNotificationChannels(
                listOf(serviceChannel, discoveryChannel, messagesChannel)
            )
        }
    }
    
    /**
     * Create a foreground service notification for background running
     */
    fun createServiceNotification(deviceCount: Int = 0, isScanning: Boolean = false): Notification {
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
        
        // Status text based on current activity
        val statusText = when {
            isScanning -> "Currently scanning for devices"
            deviceCount > 0 -> "Connected to $deviceCount device(s)"
            else -> "Running in background"
        }
        
        // Build the notification
        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setContentTitle("DNC Service Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // You should replace with your own icon
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_search, "Scan", scanPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
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
     * Update the foreground service notification with new status
     */
    fun updateServiceNotification(deviceCount: Int, isScanning: Boolean) {
        val notification = createServiceNotification(deviceCount, isScanning)
        notificationManager.notify(NOTIFICATION_ID_SERVICE, notification)
    }
    
    /**
     * Cancel all notifications
     */
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
} 