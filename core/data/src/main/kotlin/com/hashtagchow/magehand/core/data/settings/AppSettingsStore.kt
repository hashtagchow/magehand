package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-level preferences — the ones that are *not* about an account and *not* about one
 * character (docs/design/09-local-characters.md decision 9).
 *
 * ### Why this exists at all
 *
 * Settings had no store before FR-6. Its two existing controls are account operations, which
 * `AccountRepository` already owns, so "the mechanism Settings already uses" was, literally,
 * none. 09 decision 9 names the alternative — "DataStore or the existing prefs pattern" — and
 * DataStore is what the one comparable preference in this app (`ActiveAccountStore`) uses, so
 * this is that pattern applied a second time rather than a third mechanism invented.
 *
 * The same `magehand_prefs` file backs both: two `DataStore` instances over one file is the
 * documented way to corrupt it, so `DataModule` builds one and hands it to both stores.
 *
 * Split behind an interface for the same reason [
 * com.hashtagchow.magehand.core.data.account.ActiveAccountStore] is: a view model asserted
 * against a real DataStore is a view model asserted against a filesystem.
 */
interface AppSettingsStore {

    /**
     * FR-6: whether the tracker shows the conditions section and the customize sheet shows
     * its CONDITIONS group.
     *
     * **Default `false`**, which is the operator's explicit intent (09 decision 9): most
     * sheets' toggles are build machinery, and existing users who used them will see them
     * vanish on upgrade with the switch one screen away. The default lives *here*, not at
     * each read site, so a caller cannot accidentally opt a screen back in by forgetting it.
     */
    val showToggles: Flow<Boolean>

    suspend fun setShowToggles(value: Boolean)

    companion object {
        /** 09 decision 9, stated once. */
        const val DEFAULT_SHOW_TOGGLES: Boolean = false
    }
}

/** Preferences-DataStore backed [AppSettingsStore]. */
class DataStoreAppSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : AppSettingsStore {

    override val showToggles: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_TOGGLES] ?: AppSettingsStore.DEFAULT_SHOW_TOGGLES }

    override suspend fun setShowToggles(value: Boolean) {
        dataStore.edit { it[KEY_SHOW_TOGGLES] = value }
    }

    companion object {
        /** 09 decision 9 names the key `show_toggles`; this is that name, unchanged. */
        private val KEY_SHOW_TOGGLES = booleanPreferencesKey("show_toggles")
    }
}
