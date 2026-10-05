package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class OpenConnectSettingsActivity : ProfileSettingsActivity<OpenConnectBean>() {

    override fun createEntity() = OpenConnectBean().applyDefaultValues()

    override fun OpenConnectBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.openconnectFlavor = flavor
        DataStore.openconnectAuthGroup = authGroup
        DataStore.openconnectInsecure = insecure
        // Preserved without an edit-screen entry in the minimal set.
        DataStore.openconnectCookie = cookie
        DataStore.openconnectServerName = serverName
    }

    override fun OpenConnectBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        flavor = DataStore.openconnectFlavor
        authGroup = DataStore.openconnectAuthGroup
        insecure = DataStore.openconnectInsecure
        cookie = DataStore.openconnectCookie
        serverName = DataStore.openconnectServerName
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.openconnect_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
