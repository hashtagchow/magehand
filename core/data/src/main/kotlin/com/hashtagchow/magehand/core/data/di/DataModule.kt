package com.hashtagchow.magehand.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import okhttp3.OkHttpClient
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.account.ActiveAccountStore
import com.hashtagchow.magehand.core.data.account.DataStoreActiveAccountStore
import com.hashtagchow.magehand.core.data.account.DefaultAccountRepository
import com.hashtagchow.magehand.core.data.api.DiceCloudApi
import com.hashtagchow.magehand.core.data.api.OkHttpDiceCloudApi
import com.hashtagchow.magehand.core.data.auth.AndroidWebViewSessionStore
import com.hashtagchow.magehand.core.data.auth.LegacyTokenStorePurge
import com.hashtagchow.magehand.core.data.auth.KeystoreTokenStore
import com.hashtagchow.magehand.core.data.auth.TokenStore
import com.hashtagchow.magehand.core.data.auth.WebViewSessionStore
import com.hashtagchow.magehand.core.data.characters.CharacterCache
import com.hashtagchow.magehand.core.data.characters.CharacterListRepository
import com.hashtagchow.magehand.core.data.characters.DefaultCharacterListRepository
import com.hashtagchow.magehand.core.data.characters.RoomCharacterCache
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.data.connection.DefaultDdpConnectionManager
import com.hashtagchow.magehand.core.data.db.AccountDao
import com.hashtagchow.magehand.core.data.db.CharacterDao
import com.hashtagchow.magehand.core.data.db.LocalCharacterDao
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.db.SnapshotDao
import com.hashtagchow.magehand.core.data.db.ThemePrefDao
import com.hashtagchow.magehand.core.data.db.TrackerPrefDao
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
import com.hashtagchow.magehand.core.data.local.LocalOpenCharacterFactory
import com.hashtagchow.magehand.core.ddp.OkHttpDdpSocketFactory
import com.hashtagchow.magehand.core.data.session.DefaultOpenCharacterFactory
import com.hashtagchow.magehand.core.data.session.OpenCharacterFactory
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.DataStoreAppSettingsStore
import com.hashtagchow.magehand.core.data.settings.DataStoreEquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.DataStoreInventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.DataStoreSelectedRollStore
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Wiring for everything WP3 owns.
 *
 * Every binding key is either a `:core:data` type or a JDK/Android type. Third-party
 * types this module happens to use — `OkHttpClient`, `DataStore<Preferences>` — are
 * deliberately **not** bindings: `okhttp` and `datastore-preferences` are
 * `implementation` dependencies here, so a binding of those types would force
 * `:app`'s Hilt component processing to resolve classes that are not on its
 * classpath. Consumers get [DiceCloudApi], [TokenStore], [ActiveAccountStore] and
 * [AccountRepository] — interfaces — and nothing leaks.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDiceCloudApi(): DiceCloudApi = OkHttpDiceCloudApi(newApiClient())

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MageHandDatabase =
        Room.databaseBuilder(context, MageHandDatabase::class.java, MageHandDatabase.NAME)
            // No `fallbackToDestructiveMigration` anywhere: dropping the `accounts` table
            // would orphan every Keystore-held token, which is keyed by `accounts.id`.
            .addMigrations(*MageHandDatabase.MIGRATIONS)
            .build()

    @Provides
    fun provideAccountDao(database: MageHandDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideCharacterDao(database: MageHandDatabase): CharacterDao = database.characterDao()

    @Provides
    fun provideSnapshotDao(database: MageHandDatabase): SnapshotDao = database.snapshotDao()

    @Provides
    fun provideTrackerPrefDao(database: MageHandDatabase): TrackerPrefDao = database.trackerPrefDao()

    @Provides
    fun provideThemePrefDao(database: MageHandDatabase): ThemePrefDao = database.themePrefDao()

    /**
     * The snapshot cache. WP4's types all take plain constructors so they can be built by
     * hand in tests and by the sibling work package's own wiring; this binding exists only
     * because the store is a natural singleton (one Room table, one eviction policy).
     */
    @Provides
    @Singleton
    fun provideSnapshotStore(snapshotDao: SnapshotDao, api: DiceCloudApi): SnapshotStore =
        SnapshotStore(snapshotDao = snapshotDao, api = api)

    /**
     * WP8 replaced WP3's `EncryptedPrefsTokenStore`: `androidx.security:security-crypto`
     * deprecated `EncryptedSharedPreferences`/`MasterKey` in 1.1.0 with no replacement,
     * so the Keystore is used directly (docs/verification/WP8.md §3).
     */
    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore =
        KeystoreTokenStore(context)

    @Provides
    @Singleton
    fun provideLegacyTokenStorePurge(
        @ApplicationContext context: Context,
        accountDao: AccountDao,
        activeAccountStore: ActiveAccountStore,
    ): LegacyTokenStorePurge = LegacyTokenStorePurge(context, accountDao, activeAccountStore)

    /**
     * Sign-out's WebView cleanup (docs/design/05-security.md §"WebView SSO" — the
     * localStorage residue WP5 found).
     */
    @Provides
    @Singleton
    fun provideWebViewSessionStore(@ApplicationContext context: Context): WebViewSessionStore =
        AndroidWebViewSessionStore(context)

    @Provides
    @Singleton
    fun provideActiveAccountStore(@ApplicationContext context: Context): ActiveAccountStore =
        DataStoreActiveAccountStore(preferences(context))

    /**
     * FR-6's `show_toggles` (docs/design/09-local-characters.md decision 9).
     *
     * Shares [preferences] with the active-account store rather than opening a second file:
     * one more `.preferences_pb` for one boolean would be a second thing to migrate, back up
     * and reason about, and DataStore's own contract is per-*file*, not per-key.
     */
    @Provides
    @Singleton
    fun provideAppSettingsStore(@ApplicationContext context: Context): AppSettingsStore =
        DataStoreAppSettingsStore(preferences(context))

    /**
     * FR-7's per-character roll selection.
     *
     * Shares [preferences] with the other two stores for the reason stated above: DataStore's
     * contract is per-*file*, and a third `.preferences_pb` would be a third thing to migrate
     * and back up. The three interfaces over it stay separate because their *contracts* differ
     * — app-wide, active-account, per-character — see `SelectedRollStore`'s KDoc.
     */
    @Provides
    @Singleton
    fun provideSelectedRollStore(@ApplicationContext context: Context): SelectedRollStore =
        DataStoreSelectedRollStore(preferences(context))

    /**
     * FR-10's per-item equippability overrides (11 decision 2).
     *
     * The same [preferences] file again, and now for a fourth interface — which is the point at
     * which it is worth restating why that is not sprawl: DataStore's contract is per-*file*,
     * so one more `.preferences_pb` would be one more thing to migrate, back up and reason
     * about, while the interfaces over it stay separate because their *contracts* differ. This
     * one and [SelectedRollStore] deliberately share both the file and the key-namespace shape,
     * because they also share both reaping paths — see `EquippableOverrideStore`'s KDoc.
     */
    @Provides
    @Singleton
    fun provideEquippableOverrideStore(
        @ApplicationContext context: Context,
    ): EquippableOverrideStore = DataStoreEquippableOverrideStore(preferences(context))

    /**
     * FR-14's per-character inventory section arrangement (12 decision 5).
     *
     * The same [preferences] file for a fifth interface, and the argument has not changed: one
     * file to migrate and back up, several contracts over it. This is the *third* store with the
     * per-character key shape and both reaping paths — see `InventoryLayoutStore`'s KDoc for why
     * the three are three files rather than one generic store, and note that the two provider
     * methods above are what keeps them sharing a file rather than sharing a type.
     */
    @Provides
    @Singleton
    fun provideInventoryLayoutStore(
        @ApplicationContext context: Context,
    ): InventoryLayoutStore = DataStoreInventoryLayoutStore(preferences(context))

    /**
     * The snapshot store, character cache and two pref DAOs are here for
     * [DefaultAccountRepository.signOut] alone — it is the one path that knows an account
     * has ended, and each of these owns rows keyed by `accountId` that nothing else could
     * ever reap. None of them creates a cycle: they resolve from the database and the API,
     * neither of which depends on [AccountRepository].
     */
    @Provides
    @Singleton
    fun provideAccountRepository(
        api: DiceCloudApi,
        accountDao: AccountDao,
        tokenStore: TokenStore,
        activeAccountStore: ActiveAccountStore,
        webViewSessionStore: WebViewSessionStore,
        snapshotStore: SnapshotStore,
        characterCache: CharacterCache,
        trackerPrefDao: TrackerPrefDao,
        themePrefDao: ThemePrefDao,
        selectedRollStore: SelectedRollStore,
        equippableOverrideStore: EquippableOverrideStore,
        inventoryLayoutStore: InventoryLayoutStore,
    ): AccountRepository = DefaultAccountRepository(
        api = api,
        accountDao = accountDao,
        tokenStore = tokenStore,
        activeAccountStore = activeAccountStore,
        webViewSessionStore = webViewSessionStore,
        snapshotStore = snapshotStore,
        characterCache = characterCache,
        trackerPrefDao = trackerPrefDao,
        themePrefDao = themePrefDao,
        selectedRollStore = selectedRollStore,
        equippableOverrideStore = equippableOverrideStore,
        inventoryLayoutStore = inventoryLayoutStore,
    )

    // ---- WP5: live connection + character list -------------------------------

    /**
     * The one DDP connection, following the active account.
     *
     * `@Singleton` is load-bearing twice over: it is what makes "one socket per
     * active account" true, and it is what stops a second manager from opening a
     * second socket that nothing would ever close.
     */
    @Provides
    @Singleton
    fun provideDdpConnectionManager(
        accountRepository: AccountRepository,
    ): DdpConnectionManager = DefaultDdpConnectionManager(
        accountRepository = accountRepository,
        scope = newAppScope("ddp-connection"),
        // The whole reason [baseHttpClient] exists: every account switch builds a new
        // DdpClient, and each one used to bring its own dispatcher and connection pool
        // that nothing ever shut down.
        clientFactory = DefaultDdpConnectionManager.sharedClientFactory(
            OkHttpDdpSocketFactory.webSocketClient(baseHttpClient),
        ),
    )

    /**
     * WP5's `TODO(WP4)` here is **closed**: schema v2 landed with WP4, so the character
     * list is cached in Room rather than in a process-lifetime map. Two user-visible
     * consequences: a cold start renders the last known party instead of a spinner, and
     * `characters.lastOpenedAt` exists, which is what 04's "start destination: last-used
     * character's Tracker" reads.
     */
    @Provides
    @Singleton
    fun provideCharacterCache(characterDao: CharacterDao): CharacterCache =
        RoomCharacterCache(characterDao)

    @Provides
    @Singleton
    fun provideCharacterListRepository(
        connectionManager: DdpConnectionManager,
        cache: CharacterCache,
    ): CharacterListRepository = DefaultCharacterListRepository(
        connectionManager = connectionManager,
        cache = cache,
        scope = newAppScope("character-list"),
    )

    // ---- WP6: one open character screen --------------------------------------

    /**
     * Not `@Singleton`: a factory is stateless, and each [OpenCharacterFactory.open] hands
     * back an object whose lifetime is one character screen, not the process.
     */
    @Provides
    fun provideOpenCharacterFactory(
        connectionManager: DdpConnectionManager,
        accountRepository: AccountRepository,
        snapshotStore: SnapshotStore,
        trackerPrefDao: TrackerPrefDao,
        themePrefDao: ThemePrefDao,
        characterCache: CharacterCache,
    ): OpenCharacterFactory = DefaultOpenCharacterFactory(
        connectionManager = connectionManager,
        accountRepository = accountRepository,
        snapshotStore = snapshotStore,
        trackerPrefDao = trackerPrefDao,
        themePrefDao = themePrefDao,
        characterCache = characterCache,
    )

    // ---- FR-5: local characters (docs/design/09-local-characters.md) ----------

    @Provides
    fun provideLocalCharacterDao(database: MageHandDatabase): LocalCharacterDao =
        database.localCharacterDao()

    /**
     * The on-device list and the creation form's save path.
     *
     * `@Singleton` because it is stateless apart from the DAO and the two injected seams, and
     * one instance keeps the `now`/`newId` overrides in one place rather than letting a second
     * construction site quietly pick the defaults.
     *
     * The three per-character stores are for the delete path: a local character's remembered
     * roll, its equippability overrides and its inventory layout are DataStore keys, not rows,
     * so nothing cascades them and sign-out is forbidden from reaping them — see
     * [LocalCharacterRepository.delete].
     */
    @Provides
    @Singleton
    fun provideLocalCharacterRepository(
        dao: LocalCharacterDao,
        selectedRollStore: SelectedRollStore,
        equippableOverrideStore: EquippableOverrideStore,
        inventoryLayoutStore: InventoryLayoutStore,
    ): LocalCharacterRepository =
        LocalCharacterRepository(dao, selectedRollStore, equippableOverrideStore, inventoryLayoutStore)

    /**
     * Not `@Singleton`, for the same reason [provideOpenCharacterFactory] is not: each open
     * hands back an object whose lifetime is one character screen.
     */
    @Provides
    fun provideLocalOpenCharacterFactory(
        dao: LocalCharacterDao,
        // FR-9's local delete reaps the deleted row's equippability override — a DataStore key
        // no `ON DELETE CASCADE` can follow. See `LocalOpenCharacter.removeItem`.
        equippableOverrideStore: EquippableOverrideStore,
    ): LocalOpenCharacterFactory =
        LocalOpenCharacterFactory(dao, equippableOverrideStore)

    /**
     * Application-lifetime scopes are created here rather than bound, for the same
     * classpath reason as [newHttpClient]: `CoroutineScope` is a
     * `kotlinx-coroutines` type and this module's coroutines dependency is
     * `implementation`. Nothing cancels these — they die with the process, which is
     * exactly the lifetime "one connection per active account" needs.
     */
    private fun newAppScope(name: String): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(name))

    /**
     * **The process's one preferences DataStore**, for the same reason [baseHttpClient] is
     * the process's one OkHttp client — and here it is a correctness rule rather than a
     * resource one: DataStore throws if a second instance is created over a file that
     * already has one, because two instances cannot see each other's writes.
     *
     * `@Singleton` on a `@Provides` used to carry that guarantee while `ActiveAccountStore`
     * was the only consumer. It stopped carrying it the moment a second consumer existed:
     * two `@Singleton` bindings are two singletons, each of which would have built its own
     * DataStore over `magehand_prefs`. Memoizing here — not binding `DataStore<Preferences>`,
     * which the module KDoc explains would drag a `datastore-preferences` type onto `:app`'s
     * Hilt classpath — is what makes "one per file" true again.
     */
    @Volatile
    private var preferences: DataStore<Preferences>? = null

    private fun preferences(context: Context): DataStore<Preferences> =
        preferences ?: synchronized(this) {
            preferences ?: PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(DataStoreActiveAccountStore.PREFS_NAME)
            }.also { preferences = it }
        }

    /**
     * **The process's one OkHttp client.**
     *
     * An `OkHttpClient` owns a dispatcher thread pool and a connection pool, and
     * releases neither until an idle timer fires or someone shuts the executor down.
     * Both the REST API and every DDP websocket therefore derive from this single
     * instance via `newBuilder()`, which keeps the pools shared while letting each use
     * set its own timeouts. Nothing shuts it down, and nothing needs to: there is one,
     * and it lives as long as the process.
     *
     * Still not a `@Provides` — the WP3 note below stands. `okhttp` is an
     * `implementation` dependency here, so binding an `OkHttpClient` would force
     * `:app`'s Hilt component processing to resolve a class that is not on its
     * classpath. A `private val` inside the module gives the sharing without the
     * binding, which is why the "promote it to a real binding" plan is no longer
     * needed to close the leak.
     */
    private val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * The REST view of [baseHttpClient]: same dispatcher, same connection pool,
     * different timeouts.
     */
    private fun newApiClient(): Call.Factory = baseHttpClient.newBuilder()
        // A creature snapshot is ~1.1 MB and the server force-recomputes a stale
        // sheet before answering, so the read timeout has to be generous.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}
