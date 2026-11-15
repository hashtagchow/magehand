package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
     * FR-6: whether the tracker shows the conditions section — applied buffs (FR-53) and
     * condition toggles — and the customize sheet shows its CONDITIONS group.
     *
     * **Default `true`** since FR-57 (operator ruling, 2026-11-15), reversing 09 decision 9's
     * original `false`. FR-53 put applied buffs in this section, and a buff is never build
     * machinery: off by default, a player who cast an applied buff such as Shield saw the AC
     * change and nothing on screen that said why or could end it. Only *active* toggles draw
     * (FR-1), so the old reason no longer outweighs that.
     *
     * **Retroactive without a migration**: the read is `stored ?: default`, so an install that
     * never touched the switch has no entry and turns on at upgrade. A stored `false` is
     * honoured because the only writer of the key is the player's own tap on the Settings
     * switch ([setShowToggles] has no other caller) — keep it that way, or a stored value stops
     * meaning "the player chose this" and FR-57 R2's one-time reset becomes necessary.
     *
     * The default lives *here*, not at each read site, so a caller cannot accidentally opt a
     * screen into the opposite answer by forgetting it.
     */
    val showToggles: Flow<Boolean>

    suspend fun setShowToggles(value: Boolean)

    /**
     * FR-18: how much bigger than the system's own scale the whole UI renders
     * (docs/design/14-large-screen-arc.md decisions 1-2).
     *
     * App-level and not per-character on purpose — 14 decision 2 puts it "beside FR-6's
     * `show_toggles`", and this interface's contract is exactly that class of preference.
     * A per-character scale would also mean the character list, settings and the login
     * screen had no scale at all, which is where a tablet user first meets the problem.
     *
     * Defaults to [UiScale.DEFAULT] via [UiScale.fromKey], so an unset — or unreadable —
     * value renders at the scale every build before 1.7.0 rendered at.
     */
    val uiScale: Flow<UiScale>

    suspend fun setUiScale(value: UiScale)

    /**
     * FR-44 R3: whether the tracker shows the **limited-use abilities** section — `action` and
     * `spell` rows carrying a use count, discovered by shape (`TrackerKind.LIMITED_USE`).
     *
     * **Default `true`**. R3 states why in one line: *"the table asked for the rows, the switch
     * is for people who did not"* — a feature that ships off is a feature nobody finds. This was
     * once deliberately the opposite of [showToggles]' default; FR-57 (2026-11-15) applied the
     * same sentence to that switch, so the two now agree.
     *
     * The default lives here, next to [DEFAULT_SHOW_TOGGLES] and for the same reason: a caller
     * that forgets it cannot accidentally opt a screen into the opposite answer.
     */
    val showLimitedUses: Flow<Boolean>

    suspend fun setShowLimitedUses(value: Boolean)

    companion object {
        /**
         * FR-57's "default **ON**" (operator, 2026-11-15; was 09 decision 9's `false`), stated
         * once.
         */
        const val DEFAULT_SHOW_TOGGLES: Boolean = true

        /** FR-44 R3's "default **ON**", stated once. */
        const val DEFAULT_SHOW_LIMITED_USES: Boolean = true
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

    /**
     * FR-18. The degrade-to-default lives in [UiScale.fromKey] rather than here, so the
     * *reader* of a value this store never wrote — a downgraded install, a corrupted
     * preference — gets the same answer as a fresh install by construction.
     */
    override val uiScale: Flow<UiScale> =
        dataStore.data.map { UiScale.fromKey(it[KEY_UI_SCALE]) }

    override suspend fun setUiScale(value: UiScale) {
        dataStore.edit { it[KEY_UI_SCALE] = value.key }
    }

    /** FR-44 R3, in [showToggles]'s shape — one boolean in the same preferences file. */
    override val showLimitedUses: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHOW_LIMITED_USES] ?: AppSettingsStore.DEFAULT_SHOW_LIMITED_USES }

    override suspend fun setShowLimitedUses(value: Boolean) {
        dataStore.edit { it[KEY_SHOW_LIMITED_USES] = value }
    }

    companion object {
        /** 09 decision 9 names the key `show_toggles`; this is that name, unchanged. */
        private val KEY_SHOW_TOGGLES = booleanPreferencesKey("show_toggles")

        /** 14 decision 2's "app-level string", in the same preferences file. */
        private val KEY_UI_SCALE = stringPreferencesKey("ui_scale")

        /**
         * FR-44 R3. Named for the switch, in `show_toggles`' snake_case — the two are read as a
         * pair by anyone inspecting the preferences file, and an install that has never seen the
         * switch has no entry at all, which is what makes
         * [AppSettingsStore.DEFAULT_SHOW_LIMITED_USES] the answer on upgrade.
         */
        private val KEY_SHOW_LIMITED_USES = booleanPreferencesKey("show_limited_uses")
    }
}
