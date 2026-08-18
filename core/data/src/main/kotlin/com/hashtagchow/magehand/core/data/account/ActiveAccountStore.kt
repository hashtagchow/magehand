package com.hashtagchow.magehand.core.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which account the UI is currently showing.
 *
 * This is a *selection*, not a secret, so it lives in DataStore rather than the
 * encrypted store. Split out behind an interface purely so the repository is
 * testable without a filesystem.
 */
interface ActiveAccountStore {
    /** Emits the active `Account.id`, or `null` when no account is selected. */
    val activeAccountId: Flow<String?>

    suspend fun setActiveAccountId(id: String?)
}

/** Preferences-DataStore backed [ActiveAccountStore]. */
class DataStoreActiveAccountStore(
    private val dataStore: DataStore<Preferences>,
) : ActiveAccountStore {

    override val activeAccountId: Flow<String?> =
        dataStore.data.map { it[KEY_ACTIVE_ACCOUNT_ID] }

    override suspend fun setActiveAccountId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_ACCOUNT_ID) else prefs[KEY_ACTIVE_ACCOUNT_ID] = id
        }
    }

    companion object {
        /** DataStore file name (without the `.preferences_pb` suffix DataStore appends). */
        const val PREFS_NAME: String = "magehand_prefs"

        private val KEY_ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")
    }
}
