package com.hashtagchow.magehand.core.data.auth

import android.content.Context
import com.hashtagchow.magehand.core.data.account.ActiveAccountStore
import com.hashtagchow.magehand.core.data.db.AccountDao
import java.io.File

/**
 * One-shot cleanup for installs that predate WP8's [KeystoreTokenStore].
 *
 * WP3 stored tokens in an `EncryptedSharedPreferences` file called
 * `magehand_tokens.xml`. WP8 removed `androidx.security:security-crypto`
 * altogether (it is deprecated with no replacement), which means nothing in the
 * app can decrypt that file any more — not even to migrate it. Keeping the
 * dependency alive purely as a one-release importer was rejected: it would leave
 * the retired API on the release classpath, which is the thing being fixed.
 *
 * So the file is deleted, and every account row goes with it. That is deliberate
 * and it is the *smaller* failure: an account row whose token cannot be read is an
 * account that parks the DDP client in `AUTH_FAILED` and shows an empty character
 * list forever, whereas no account row at all is the login screen and one sign-in.
 *
 * Blast radius: the table's WP5–WP7 debug sideloads. `versionCode 2` is the first
 * release build that has ever existed, so no store install can be affected.
 */
class LegacyTokenStorePurge(
    private val context: Context,
    private val accountDao: AccountDao,
    private val activeAccountStore: ActiveAccountStore,
) {

    /**
     * @return `true` if a legacy file was found and the purge ran, `false` on the
     *   overwhelmingly common path where there was nothing to do. Callers can log
     *   the `true` case; it should happen at most once per install.
     */
    suspend fun runIfNeeded(): Boolean {
        val legacy = KeystoreTokenStore.legacyPrefsFile(context)
        if (!legacy.exists()) return false

        for (account in accountDao.getAll()) accountDao.deleteById(account.id)
        activeAccountStore.setActiveAccountId(null)

        legacy.delete()
        // SharedPreferences keeps a write-ahead backup next to the file; deleting
        // only the .xml would let the framework restore it on the next open.
        File(legacy.parentFile, legacy.name + ".bak").delete()
        return true
    }
}
