package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityLanSharingBinding
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

class LanSharingActivity : ThemedActivity() {

    private lateinit var binding: ActivityLanSharingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanSharingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.lan_sharing_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        binding.shareSwitch.isChecked = DataStore.allowAccess
        binding.shareSwitch.setOnCheckedChangeListener { _, isChecked ->
            DataStore.allowAccess = isChecked
            if (DataStore.serviceState.started) {
                Toast.makeText(this, R.string.lan_sharing_restarting, Toast.LENGTH_SHORT).show()
                SagerNet.reloadService()
            }
            refreshInfo()
        }

        binding.hotspotCopyBtn.setOnClickListener { copyIp(binding.hotspotIpValue.text.toString()) }
        binding.wifiCopyBtn.setOnClickListener { copyIp(binding.wifiIpValue.text.toString()) }
        binding.portChangeBtn.setOnClickListener { showPortDialog() }

        refreshInfo()
    }

    override fun onResume() {
        super.onResume()
        binding.shareSwitch.isChecked = DataStore.allowAccess
        refreshInfo()
    }

    private fun copyIp(text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lan_proxy", text))
        Toast.makeText(this, R.string.lan_sharing_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showPortDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DataStore.mixedPort.toString())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lan_sharing_port_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port == null || port < 1024 || port > 65535) {
                    Toast.makeText(this, R.string.lan_sharing_port_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                DataStore.mixedPort = port
                if (DataStore.serviceState.started) {
                    SagerNet.reloadService()
                }
                refreshInfo()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshInfo() {
        val port = DataStore.mixedPort
        binding.portValue.text = port.toString()

        val hotspotIp = detectHotspotIp()
        if (hotspotIp != null) {
            binding.hotspotCard.isVisible = true
            binding.hotspotStatus.text = getString(R.string.lan_sharing_hotspot_on)
            binding.hotspotIpValue.text = "$hotspotIp:$port"
        } else {
            binding.hotspotCard.isVisible = true
            binding.hotspotStatus.text = getString(R.string.lan_sharing_hotspot_off)
            binding.hotspotIpValue.text = ""
            binding.hotspotCopyBtn.isEnabled = false
        }
        binding.hotspotCopyBtn.isEnabled = hotspotIp != null

        val wifiIp = detectWifiIp()
        if (wifiIp != null) {
            binding.wifiStatus.text = ""
            binding.wifiIpValue.text = "$wifiIp:$port"
            binding.wifiCopyBtn.isEnabled = true
        } else {
            binding.wifiStatus.text = getString(R.string.lan_sharing_wifi_off)
            binding.wifiIpValue.text = ""
            binding.wifiCopyBtn.isEnabled = false
        }
    }

    private fun isHotspotActive(): Boolean {
        return runCatching {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
            runCatching {
                val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                method.invoke(wifiManager) as? Boolean
            }.getOrNull() ?: runCatching {
                val method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
                method.isAccessible = true
                val state = method.invoke(wifiManager) as? Int ?: return false
                state == 13
            }.getOrDefault(false)
        }.getOrDefault(false)
    }

    private fun detectHotspotIp(): String? {
        if (!isHotspotActive()) return null
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tetheredIfaces = runCatching {
            val method = cm?.javaClass?.getDeclaredMethod("getTetheredIfaces")
            method?.isAccessible = true
            (method?.invoke(cm) as? Array<*>)?.filterIsInstance<String>().orEmpty()
        }.getOrDefault(emptyList())
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val preferred = if (tetheredIfaces.isNotEmpty()) {
            interfaces.filter { it.name in tetheredIfaces }
        } else {
            interfaces
        }
        for (iface in preferred) {
            if (!runCatching { iface.isUp }.getOrDefault(false)) continue
            for (addr in iface.inetAddresses) {
                val ip = (addr as? Inet4Address)?.hostAddress ?: continue
                if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.") || ip.startsWith("172.20.10.")) {
                    return ip
                }
            }
        }
        return null
    }

    private fun detectWifiIp(): String? {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val linkProps = cm.getLinkProperties(network) ?: continue
                val ip = linkProps.linkAddresses
                    .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                    .firstOrNull { !it.startsWith("127.") && !it.startsWith("169.254.") }
                if (!ip.isNullOrBlank()) return ip
            }
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
