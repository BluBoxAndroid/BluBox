package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {

    override fun createEntity() = SnellBean().applyDefaultValues()

    override fun SnellBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.snellPsk = psk
        DataStore.snellVersion = version
        DataStore.snellObfsMode = obfsMode
        DataStore.snellObfsHost = obfsHost
        // Fields below have no edit-screen entry in the minimal preference set;
        // they are preserved through DataStore so link/JSON imports survive edits.
        DataStore.snellUserkey = userkey
        DataStore.snellReuse = reuse
        DataStore.snellNetwork = network
        DataStore.snellMode = mode
    }

    override fun SnellBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        psk = DataStore.snellPsk
        version = DataStore.snellVersion
        obfsMode = DataStore.snellObfsMode
        obfsHost = DataStore.snellObfsHost
        userkey = DataStore.snellUserkey
        reuse = DataStore.snellReuse
        network = DataStore.snellNetwork
        mode = DataStore.snellMode
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.snell_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SNELL_PSK)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
