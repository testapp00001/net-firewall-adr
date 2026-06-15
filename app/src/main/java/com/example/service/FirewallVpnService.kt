package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.FirewallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class FirewallVpnService : VpnService() {

    companion object {
        private const val TAG = "FirewallVpnService"
        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        private const val CHANNEL_ID = "firewall_service_channel"
        private const val NOTIFICATION_ID = 404
        
        @Volatile
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var repository: FirewallRepository
    private var timerJob: Job? = null
    private var readJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        repository = FirewallRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        if (action == ACTION_STOP) {
            stopVpnService()
            return START_NOT_STICKY
        }

        val durationMinutes = intent?.getIntExtra("extra_duration_minutes", -1) ?: -1
        Log.d(TAG, "Starting VPN with durationMinutes: $durationMinutes")

        // Handle timer logic if active
        timerJob?.cancel()
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)

        if (durationMinutes > 0) {
            val durationMs = durationMinutes * 60 * 1000L
            val expirationTimeMs = System.currentTimeMillis() + durationMs
            prefs.edit().putLong("key_expiration_timestamp_ms", expirationTimeMs).apply()

            timerJob = serviceScope.launch {
                delay(durationMs)
                Log.d(TAG, "Firewall timer expired! Self-stopping.")
                stopVpnService()
            }

            // Start foreground with custom chronometer counts
            startVpnServiceForeground(expirationTimeMs)
        } else {
            prefs.edit().putLong("key_expiration_timestamp_ms", 0L).apply()
            startVpnServiceForeground(0L)
        }

        isRunning = true

        // Observe blocked package names from Room Database dynamically
        serviceScope.launch {
            repository.blockedPackageNamesFlow
                .distinctUntilChanged()
                .collect { blockedPackages ->
                    Log.d(TAG, "Database updated, renewing VPN blocks: count=${blockedPackages.size}")
                    configureFirewallTunnel(blockedPackages)
                }
        }

        return START_STICKY
    }

    private fun getLocalizedContext(): Context {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("key_app_language", "en") ?: "en"
        val locale = java.util.Locale(savedLang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        return try {
            createConfigurationContext(config)
        } catch (e: Exception) {
            this
        }
    }

    /**
     * Start the VPN service in the foreground with interactive controls.
     */
    private fun startVpnServiceForeground(expirationTimeMs: Long) {
        createNotificationChannel()

        val localizedCtx = getLocalizedContext()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // One-Click stop action
        val stopIntent = Intent(this, FirewallVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localizedCtx.getString(R.string.notification_running_title))
            .setSmallIcon(android.R.drawable.ic_lock_lock) // Standard lock icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                localizedCtx.getString(R.string.notification_turn_off),
                stopPendingIntent
            )

        if (expirationTimeMs > System.currentTimeMillis()) {
            builder.setContentText(localizedCtx.getString(R.string.notification_timer_desc))
            builder.setWhen(expirationTimeMs)
            builder.setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true)
            }
        } else {
            builder.setContentText(localizedCtx.getString(R.string.notification_running_desc))
        }

        val notification: Notification = builder.build()
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Configure the local VPN Tunnel.
     * This selectively routes traffic only for the specified blocked applications.
     */
    @Synchronized
    private fun configureFirewallTunnel(blockedPackages: List<String>) {
        try {
            val builder = Builder()
            builder.setSession("NetFirewallSinker")
            builder.setMtu(1500)

            // Dynamic sinkhole assignment
            // IP addressing forces matching applications' IP stacks to enter our empty VPN interface.
            builder.addAddress("10.254.254.1", 32)
            builder.addRoute("0.0.0.0", 0)
            
            builder.addAddress("fd00::1", 128)
            builder.addRoute("::", 0)

            // DNS sinkhole routing configuration:
            // Designate local loopback addresses (127.0.0.1/::1) as primary DNS servers for the tunnel.
            // When blocked applications attempt to resolve domain names, query packets hit localhost
            // port 53. Since no resolver runs on the local port, the device's kernel immediately
            // returns ICMP Port Unreachable. This triggers an instantaneous socket failure in the application,
            // bypassing standard 1-5 minute timeouts and letting the app switch to local/offline modes instantly.
            try {
                builder.addDnsServer("127.0.0.1")
                builder.addDnsServer("::1")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to register local loopback fallback DNS servers", e)
            }

            if (blockedPackages.isNotEmpty()) {
                // Point routing exclusively to selected blocked applications
                for (pkg in blockedPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                        Log.d(TAG, "Assigned firewall sinkhole for application: $pkg")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Package not found when setting up firewall rule: $pkg", e)
                    }
                }
            } else {
                // If the block-list is empty, we bind to a dummy nonexistent app to keep the VPN running
                // This keeps the system "VPN active" status active without touching any normal app traffic.
                val dummyPkg = "${packageName}.vpn_idle_dummy"
                try {
                    builder.addAllowedApplication(dummyPkg)
                } catch (e: Exception) {
                    // fall through
                }
                Log.d(TAG, "No app is blocked currently. Idle block filter configured.")
            }

            // Create and swap the interface descriptor
            val nextInterface = builder.establish()
            
            // Atomically close previous interface to complete the handoff smoothly
            val oldInterface = vpnInterface
            vpnInterface = nextInterface
            oldInterface?.close()

            startPacketReader(nextInterface)

            Log.i(TAG, "Firewall VPN interface configuration applied successfully with packet flow consumer.")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error configuring local VPN firewall interface", e)
        }
    }

    private fun stopVpnService() {
        Log.i(TAG, "Stopping Net Firewall VPN Service")
        isRunning = false
        timerJob?.cancel()
        timerJob = null
        readJob?.cancel()
        readJob = null

        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("key_expiration_timestamp_ms", 0L).apply()
        
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing tunnel descriptor on stop", e)
        }

        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Net Firewall Protection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Net Firewall protection and blocking status."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startPacketReader(pfd: ParcelFileDescriptor?) {
        readJob?.cancel()
        if (pfd == null) return

        readJob = serviceScope.launch(Dispatchers.IO) {
            val fd = pfd.fileDescriptor
            val inputStream = FileInputStream(fd)
            val buffer = ByteArray(32768)
            try {
                while (isRunning) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes <= 0) {
                        break
                    }
                    // Simply discard the packet!
                    // This clears the VPN interface buffer so the OS socket layer
                    // doesn't block or stall on full buffers, helping apps run smoothly.
                }
            } catch (e: Exception) {
                Log.d(TAG, "Packet reader stopped: ${e.message}")
            } finally {
                try {
                    inputStream.close()
                } catch (ignored: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        serviceScope.cancel()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // silent close
        }
        super.onDestroy()
    }
}
