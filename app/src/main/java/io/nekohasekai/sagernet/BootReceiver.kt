package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        runOnDefaultDispatcher {
            SubscriptionUpdater.reconfigureUpdater()
        }

        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Android 16 can leave stale netd rules behind when a VPN app
            // is updated while connected, leaving the tunnel in a zombie
            // state where reconnects hang forever until the device is
            // rebooted. If the service was connected before the update
            // killed the process, start it once so the tunnel is rebuilt
            // cleanly. Never start it for users who were not connected.
            // (MY_PACKAGE_REPLACED is exempt from the background FGS start
            // restrictions, so startService() is allowed here.)
            val canStart = DataStore.serviceWasRunning && DataStore.selectedProxy > 0 &&
                (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked)
            if (canStart) {
                runCatching { SagerNet.startService() }.onFailure { Logs.w(it) }
            }
            return
        }

        if (!DataStore.persistAcrossReboot) {   // sanity check
            enabled = false
            return
        }

        val doStart = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false // DataStore.directBootAware
            else -> Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked
        } && DataStore.selectedProxy > 0

        if (doStart) SagerNet.startService()
    }
}
