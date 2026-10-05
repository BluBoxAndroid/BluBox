package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class OpenVPNSettingsActivity : ProfileSettingsActivity<OpenVPNBean>() {

    override fun createEntity() = OpenVPNBean().applyDefaultValues()

    override fun OpenVPNBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.openvpnNetwork = network
        DataStore.openvpnCipher = cipher
        DataStore.openvpnCertificate = certificate
        DataStore.openvpnClientCertificate = clientCertificate
        DataStore.openvpnClientKey = clientKey
        // Preserved without an edit-screen entry in the minimal set.
        DataStore.openvpnDataCiphers = dataCiphers
        DataStore.openvpnAuth = auth
        DataStore.openvpnServers = servers
    }

    override fun OpenVPNBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        network = DataStore.openvpnNetwork
        cipher = DataStore.openvpnCipher
        certificate = DataStore.openvpnCertificate
        clientCertificate = DataStore.openvpnClientCertificate
        clientKey = DataStore.openvpnClientKey
        dataCiphers = DataStore.openvpnDataCiphers
        auth = DataStore.openvpnAuth
        servers = DataStore.openvpnServers
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.openvpn_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
