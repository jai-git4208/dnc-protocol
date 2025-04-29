package com.ivelosi.dnc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ivelosi.dnc.notification.DNCNotificationManager
import com.ivelosi.dnc.service.DNCBackgroundService

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 * 
 * Handles actions from notification buttons, such as stopping the service
 * or starting a device scan.
 */
class DNCNotificationReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "DNCNotificationReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            DNCNotificationManager.ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping service via notification")
                context.stopService(Intent(context, DNCBackgroundService::class.java))
            }
            
            DNCNotificationManager.ACTION_SCAN_DEVICES -> {
                Log.d(TAG, "Starting scan via notification")
                // Start/bind to service if needed and trigger scan
                val serviceIntent = Intent(context, DNCBackgroundService::class.java).apply {
                    action = DNCNotificationManager.ACTION_SCAN_DEVICES
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
} 