package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FR-18's stored scale (docs/design/14-large-screen-arc.md decisions 1-3), against a **real**
 * DataStore on a real file — same reasoning as `SelectedRollStoreTest`: the claim is "the size
 * I chose is the size the app opens at tomorrow", and a fake map passes that claim without
 * ever touching a disk.
 *
 * The unknown-value test is the one that could not be written any other way. It writes a
 * string this build has never heard of *directly into the preferences file*, which is exactly
 * what a downgrade from a future version with one more step leaves behind, and then asserts the
 * app still opens at the size every previous build rendered at. FR-38 makes that hypothetical
 * concrete in the other direction: `"70"` is on disk for anyone who picks the new floor and is
 * then rolled back to 1.13.1, and that build must open at 100%.
 */
class AppSettingsUiScaleTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /**
     * The key by its real name, written out here rather than read from the store, so the
     * last test in this file is an independent check on it rather than a tautology.
     */
    private val storedKey = stringPreferencesKey("ui_scale")

    /**
     * Runs [block] against a store over [file], then fully tears the store down.
     *
     * The `cancelAndJoin` is load-bearing for the same reason it is in `SelectedRollStoreTest`:
     * DataStore refuses two live instances over one file, and the restart assertions below are
     * precisely two instances over one file.
     */
    private fun <T> withStore(file: File, block: suspend (AppSettingsStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                block(DataStoreAppSettingsStore(dataStore))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    /** Writes a raw value under the real key, bypassing the store's own API. */
    private fun writeRaw(file: File, value: String) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            PreferenceDataStoreFactory.create(scope = scope) { file }
                .edit { it[storedKey] = value }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun prefsFile(): File = File(temp.root, "test.preferences_pb")

    @Test
    fun `an unset scale reads as the default, which is the pre-1_7_0 rendering`() {
        assertEquals(UiScale.DEFAULT, withStore(prefsFile()) { it.uiScale.first() })
        assertEquals(1.0f, UiScale.DEFAULT.factor, 0f)
    }

    @Test
    fun `a chosen scale survives a restart of the store`() {
        val file = prefsFile()

        withStore(file) { it.setUiScale(UiScale.LARGE_150) }

        // A second instance over the same file — the closest a JVM test gets to the app being
        // killed and reopened, and the only shape in which "persisted" is actually asserted.
        assertEquals(UiScale.LARGE_150, withStore(file) { it.uiScale.first() })
    }

    @Test
    fun `every step round-trips, not just the one that was convenient to test`() {
        UiScale.entries.forEach { scale ->
            val file = File(temp.root, "${scale.key}.preferences_pb")
            withStore(file) { it.setUiScale(scale) }
            assertEquals(scale, withStore(file) { it.uiScale.first() })
        }
    }

    @Test
    fun `a stored value this build does not know degrades to the default`() {
        val file = prefsFile()

        // What a downgrade leaves behind: a key written by a build with a step this one has
        // never had. Also covers a corrupted value, which is indistinguishable from here.
        writeRaw(file, "200")

        assertEquals(
            "an unknown stored scale must open the app, not scale it to something invented",
            UiScale.DEFAULT,
            withStore(file) { it.uiScale.first() },
        )
        assertEquals(UiScale.DEFAULT, UiScale.fromKey(null))
        assertEquals(UiScale.DEFAULT, UiScale.fromKey(""))
    }

    @Test
    fun `the key is the name 14 names, and the stored form is a string`() {
        val file = prefsFile()

        withStore(file) { it.setUiScale(UiScale.LARGE_125) }

        // Pinned because the key is on user devices: renaming it is a migration, not an edit.
        // Read back through the raw key, so a rename in the store fails *here* rather than
        // silently resetting everybody's scale on upgrade.
        val raw = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                PreferenceDataStoreFactory.create(scope = scope) { file }.data.first()[storedKey]
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
        assertEquals("125", raw)
    }

    @Test
    fun `the seven steps are addendum 3's seven steps, with decision 3's text zoom`() {
        // The factors are the feature. Another step, or a changed factor, is a design change
        // and fails here first. FR-38 ruling 1 adds the three below 1.0.
        assertEquals(
            listOf("70", "80", "90", "default", "110", "125", "150"),
            UiScale.entries.map { it.key },
        )
        assertEquals(
            listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f),
            UiScale.entries.map { it.factor },
        )
        // 14 decision 3: `(100 * f).toInt()`. Float truncation is why these are asserted as
        // values rather than trusted as arithmetic — 1.1f * 100 is 110.000002, and a `toInt()`
        // on the wrong side of a rounding change would ship 109% to the sheet. The sub-1.0
        // steps are the same hazard from the other side: 0.7f * 100 is 69.999999 in binary,
        // and a `toInt()` truncation there would send the sheet 69%.
        assertEquals(listOf(70, 80, 90, 100, 110, 125, 150), UiScale.entries.map { it.textZoom })
    }

    /**
     * **Ascending order is part of the contract, not a coincidence of declaration.**
     *
     * The settings control renders `entries` in declaration order and adds nothing of its own
     * (FR-38 ruling 1: *"the control renders `entries` in order"*), so a step inserted in the
     * wrong place ships a chip row reading 70 / 80 / Default / 90 / 110 — which no test that
     * only checks membership would catch.
     */
    @Test
    fun `the steps are declared ascending, because the control renders them in that order`() {
        val factors = UiScale.entries.map { it.factor }
        assertEquals("the steps must be declared in ascending order", factors.sorted(), factors)
        assertEquals("no two steps may share a factor", factors.size, factors.toSet().size)
        assertEquals(
            "no two steps may share a stored key",
            UiScale.entries.size,
            UiScale.entries.map { it.key }.toSet().size,
        )
    }

    /**
     * **The floor is 0.7 and the ceiling is 1.5**, and both are stated rather than left to the
     * list above to imply.
     *
     * 14 decision 1 originally required `f >= 1.0`, and this file used to assert it. Addendum 3
     * withdraws that clause (FR-38 ruling 2): the factor is the user's explicit choice and
     * multiplies the system settings in both directions. What survives is the *floor* — at 0.7
     * a 48 dp target measures about 34 dp, and a step below that would be an app that cannot be
     * operated rather than one that is merely dense.
     */
    @Test
    fun `no step goes below the 0_7 floor addendum 3 sets`() {
        UiScale.entries.forEach { scale ->
            assertTrue(
                "${scale.key} is below addendum 3's floor: ${scale.factor}",
                scale.factor >= 0.7f,
            )
            assertTrue(
                "${scale.key} is above the top step: ${scale.factor}",
                scale.factor <= 1.5f,
            )
        }
        assertEquals(0.7f, UiScale.entries.first().factor, 0f)
    }

    /**
     * `fromKey` on the new keys, and on a key from a build that does not exist.
     *
     * FR-38 ruling 1 asks for this by name: a user on 1.14.0 who picks 70% and is then rolled
     * back to 1.13.1 has `"70"` on disk, and 1.13.1 has never heard of it. That build opens at
     * 100% — which is the whole reason `fromKey` degrades instead of failing, and is asserted
     * here from the other direction: this build must *not* degrade a key it does know.
     */
    @Test
    fun `fromKey knows the new keys and still degrades one it does not`() {
        assertEquals(UiScale.SMALL_70, UiScale.fromKey("70"))
        assertEquals(UiScale.SMALL_80, UiScale.fromKey("80"))
        assertEquals(UiScale.SMALL_90, UiScale.fromKey("90"))
        // Every step, so the round trip through the stored form is total rather than sampled.
        UiScale.entries.forEach { assertEquals(it, UiScale.fromKey(it.key)) }

        assertEquals(UiScale.DEFAULT, UiScale.fromKey("999"))
        assertEquals(UiScale.DEFAULT, UiScale.fromKey(null))
    }
}
