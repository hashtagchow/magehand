package com.hashtagchow.magehand

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.hashtagchow.magehand.core.data.auth.LegacyTokenStorePurge
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Application entry point and Hilt dependency-injection root.
 *
 * Also supplies Coil's singleton [ImageLoader]. The network fetcher is registered
 * explicitly rather than left to artifact auto-discovery: portraits are the only
 * remote images the app loads, and a silently missing fetcher would show up as
 * "every portrait failed", which is indistinguishable from the HeroForge
 * configurator links the table's sheets actually carry (docs/verification/WP5.md §2).
 */
@HiltAndroidApp
class MageHandApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var legacyTokenStorePurge: LegacyTokenStorePurge

    /**
     * Runs the WP3→WP8 token-store purge *before* anything can read an account.
     *
     * `runBlocking` on the main thread is normally indefensible; here it is the
     * point. `MainViewModel.startDestination` resolves "login or main" from the
     * first emission of `activeAccount`, so a purge that completed asynchronously
     * would race the decision it exists to change. The work is a `File.exists()`
     * on every launch after the first, and on the one launch where it is not, it is
     * a handful of row deletes on a table that holds at most a few accounts.
     */
    override fun onCreate() {
        super.onCreate()
        val purged = runBlocking { legacyTokenStorePurge.runIfNeeded() }
        if (purged) {
            Log.i(
                "MageHand",
                "Legacy (WP3) encrypted token store found and removed; accounts cleared. " +
                    "Sign in again — see docs/verification/WP8.md.",
            )
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
