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
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.HapCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground + bound service that owns the CompanionSession lifetime so it
 * survives Activity recreation / backgrounding (spec §7).
 *
 * This Service is the long-lived owner of the connection. [serviceScope] is
 * parented to the Service lifetime and cancelled in [onDestroy], satisfying
 * [ConnectionManager]'s T6 contract (connect() must run on a scope that outlives
 * any single Activity). [LocalBinder.launchConnect] runs
 * [ConnectionManager.connect] on [serviceScope] so the connection survives
 * Activity recreation and backgrounding. S5 (MainActivity) calls
 * [LocalBinder.launchConnect] instead of the deprecated
 * `lifecycleScope.launchWhenStarted`.
 */
class ConnectionService : Service() {
    private val channelId = "atv_connection"

    /** Service-lifetime coroutine scope. Cancelled in [onDestroy] before [super.onDestroy]. */
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val connectionManager: ConnectionManager
        get() = (application as AtvRemoteApp).graph.connectionManager

    inner class LocalBinder : Binder() {
        fun manager(): ConnectionManager = connectionManager

        /**
         * Non-blocking: schedules [ConnectionManager.connect] on [serviceScope] and
         * returns immediately. The connection runs on the Service lifetime scope, so it
         * survives Activity recreation and backgrounding per the T6 contract.
         */
        fun launchConnect(device: AppleTvDevice, credentials: HapCredentials) {
            serviceScope.launch { connectionManager.connect(device, credentials) }
        }
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
        serviceScope.cancel()
        super.onDestroy()
    }
}
