package com.hashtagchow.magehand.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.BuildConfig
import com.hashtagchow.magehand.MainActivity
import com.hashtagchow.magehand.core.data.account.AccountRepository
import javax.inject.Inject

/**
 * **Debug builds only.** Signs the app in from an already-minted resume token, so
 * an automated device probe can reach the character list without a password.
 *
 * ### Why this exists
 *
 * docs/design/07-build-plan.md rules out live password logins — this repo holds a
 * dev *token* (docs/dicecloud-api.md) and deliberately no password. WP5's
 * acceptance is a device probe of exactly the screens that require being signed
 * in, so the probe needs a password-free way in.
 *
 * ### Why it is safe to ship this file
 *
 * It is in `app/src/debug/`, so it is **not compiled into the release variant at
 * all** — not disabled at runtime, absent. The `BuildConfig.DEBUG` guard below is
 * belt and braces for the case where someone moves the file. There is no token
 * baked in anywhere: everything arrives as an intent extra, and
 * docs/design/05-security.md's "dev token stays in this private repo, never in app
 * assets" therefore still holds.
 *
 * The activity is `exported` because `adb shell am start` cannot reach a
 * non-exported component. On a debug build that means any app on the device could
 * invoke it — which is why it can only *add* an account from a token the caller
 * already possesses, and why it never exists on a build a user installs.
 *
 * ### Usage
 *
 * ```
 * adb shell am start -n com.hashtagchow.magehand/.debug.DebugSeedActivity \
 *   -e server   https://dnd.example-table.com \
 *   -e userId   FakeDmUser23456ab \
 *   -e username DungeonMaster \
 *   -e token    '<resume token>'
 * ```
 *
 * Optional: `-e expires <epochMillis>`, `--ez launch false` to seed without
 * opening the app.
 */
@AndroidEntryPoint
class DebugSeedActivity : ComponentActivity() {

    @Inject
    lateinit var accountRepository: AccountRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!BuildConfig.DEBUG) {
            finish()
            return
        }

        // No default: baking any real server URL into the APK — even the debug
        // variant — violates 04 §1's store-safety rule (no private URLs ship).
        val server = intent.getStringExtra(EXTRA_SERVER)
        val userId = intent.getStringExtra(EXTRA_USER_ID)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: "dev"
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val expires = intent.getStringExtra(EXTRA_EXPIRES)?.toLongOrNull()
        val launch = intent.getBooleanExtra(EXTRA_LAUNCH, true)

        if (server.isNullOrBlank() || userId.isNullOrBlank() || token.isNullOrBlank()) {
            report("seed FAILED: -e server, -e userId and -e token are required")
            finish()
            return
        }

        lifecycleScope.launch {
            accountRepository.adoptToken(
                serverUrlInput = server,
                userId = userId,
                username = username,
                token = token,
                tokenExpiresAt = expires,
            ).onSuccess { account ->
                // The token is never logged; the account id is not a secret.
                report("seed OK: account=${account.id} user=${account.username} server=${account.serverUrl}")
                if (launch) {
                    startActivity(
                        Intent(this@DebugSeedActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                }
            }.onFailure { error ->
                report("seed FAILED: ${error.message}")
            }
            finish()
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** grep this in logcat to assert the probe actually seeded. */
        const val TAG = "MageHandDebugSeed"

        const val EXTRA_SERVER = "server"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_EXPIRES = "expires"
        const val EXTRA_LAUNCH = "launch"

    }
}
