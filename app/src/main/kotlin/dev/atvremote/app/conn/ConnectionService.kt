package dev.atvremote.app.conn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.atvremote.app.AtvRemoteApp

/**
 * Foreground + bound service that owns the CompanionSession lifetime so it
 * survives Activity recreation / backgrounding (spec §7).
 *
 * NOTE: this Service is the intended long-lived owner of the connection; per
 * ConnectionManager's KDoc, the first connect() must be issued on the Service's
 * lifecycle scope (not a short-lived coroutine) and bind/unbind must sequence
 * connect()/disconnect(). Wiring the actual connect() call is a later (UI) task;
 * this skeleton only exposes the singleton manager.
 */
class ConnectionService : Service() {
    private val channelId = "atv_connection"

    val connectionManager: ConnectionManager
        get() = (application as AtvRemoteApp).graph.connectionManager

    inner class LocalBinder : Binder() {
        fun manager(): ConnectionManager = connectionManager
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // API-34 typed foreground start (carried-forward Plan-3 T7 review item;
        // triggered now that MainActivity startForegroundService's this).
        //
        // Defense-in-depth: the connectedDevice FGS type requires a qualifying
        // permission on Android 14 (declared in the manifest:
        // CHANGE_NETWORK_STATE / CHANGE_WIFI_MULTICAST_STATE). runCatching so a
        // platform/OEM FGS rejection degrades to a plain bound service (the
        // bound ConnectionManager still works — binding does not depend on the
        // foreground notification) instead of crashing the app on launch.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(1, buildNotification())
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "Remote connection", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Apple TV Remote")
            .setContentText("Connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
