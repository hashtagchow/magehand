package com.hashtagchow.magehand.core.data.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.api.ApiException
import com.hashtagchow.magehand.core.data.api.LoginSession
import com.hashtagchow.magehand.core.data.auth.StoredToken
import com.hashtagchow.magehand.core.data.characters.CharacterCache
import com.hashtagchow.magehand.core.data.characters.RoomCharacterCache
import com.hashtagchow.magehand.core.data.db.AccountEntity
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.db.ThemePrefEntity
import com.hashtagchow.magehand.core.data.db.TrackerPrefEntity
import com.hashtagchow.magehand.core.data.fake.FakeAccountDao
import com.hashtagchow.magehand.core.data.fake.FakeActiveAccountStore
import com.hashtagchow.magehand.core.data.fake.FakeDiceCloudApi
import com.hashtagchow.magehand.core.data.fake.FakeTokenStore
import com.hashtagchow.magehand.core.data.fake.FakeWebViewSessionStore
import com.hashtagchow.magehand.core.data.server.ServerUrlProblem
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.model.CharacterSummary

/**
 * Robolectric, because sign-out's teardown reaches four per-account stores and three of
 * them are Room. Faking them would prove that four methods were *called*; running them
 * against real SQLite proves the rows are gone, which is the claim
 * docs/STORE-RELEASE.md's data-deletion answer makes. The `accounts` table itself stays
 * on [FakeAccountDao] — every test above this one predates the database and none of them
 * is about SQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAccountRepositoryTest {

    private val api = FakeDiceCloudApi()
    private val dao = FakeAccountDao()
    private val tokens = FakeTokenStore()
    private val active = FakeActiveAccountStore()
    private val webViewSessions = FakeWebViewSessionStore()

    private var clock = 1_000L
    private var idCounter = 0

    private lateinit var database: MageHandDatabase
    private lateinit var snapshots: SnapshotStore
    private lateinit var characters: CharacterCache
    private lateinit var repository: DefaultAccountRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Unconfined for `SnapshotStoreTest`'s reason: a TestDispatcher built outside
        // `runTest` has a scheduler nothing here advances, so `withContext` would deadlock.
        snapshots = SnapshotStore(
            snapshotDao = database.snapshotDao(),
            api = api,
            ioDispatcher = Dispatchers.Unconfined,
            now = { clock },
        )
        characters = RoomCharacterCache(database.characterDao(), Dispatchers.Unconfined)
        repository = DefaultAccountRepository(
            api = api,
            accountDao = dao,
            tokenStore = tokens,
            activeAccountStore = active,
            webViewSessionStore = webViewSessions,
            snapshotStore = snapshots,
            characterCache = characters,
            trackerPrefDao = database.trackerPrefDao(),
            themePrefDao = database.themePrefDao(),
            now = { clock },
            newId = { "acct-${++idCounter}" },
        )
    }

    @After
    fun tearDown() = database.close()

    // ---- addAccount ----------------------------------------------------------

    @Test
    fun `addAccount normalizes the url, stores the token and persists the row`() = runTest {
        api.loginResult = { LoginSession("meteor-user-1", "resume-token-1", 99L) }

        val account = repository.addAccount("DND.Example.COM/", "DungeonMaster", "hunter2")
            .getOrThrow()

        // The row carries the normalized origin, not what was typed.
        assertEquals("https://dnd.example.com", account.serverUrl)
        assertEquals("meteor-user-1", account.userId)
        assertEquals("DungeonMaster", account.username)
        assertEquals("acct-1", account.id)
        assertEquals(1_000L, account.addedAt)
        assertEquals(1_000L, account.lastUsedAt)

        // The API was called with the normalized origin too.
        assertEquals("https://dnd.example.com", api.loginCalls.single().first)

        assertEquals(listOf(account), repository.accounts.first())
        assertEquals(StoredToken("resume-token-1", 99L), tokens.read("acct-1"))
        assertEquals("acct-1", active.current())
    }

    @Test
    fun `the token is stored only in the TokenStore, never in the account row`() = runTest {
        api.loginResult = { LoginSession("u1", "resume-token-1", null) }
        repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()

        val row = dao.findById("acct-1")!!
        assertTrue(
            "an account row must not contain the token: $row",
            !row.toString().contains("resume-token-1"),
        )
        assertEquals("resume-token-1", repository.tokenFor("acct-1"))
    }

    @Test
    fun `addAccount rejects an http url before calling the api`() = runTest {
        val failure = repository.addAccount("http://dicecloud.com", "dm", "p").exceptionOrNull()

        assertTrue(failure is ApiException.InvalidServerUrl)
        assertEquals(ServerUrlProblem.INSECURE_SCHEME, (failure as ApiException.InvalidServerUrl).problem)
        assertTrue("no login should have been attempted", api.loginCalls.isEmpty())
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `wrong credentials leave no account row and no token`() = runTest {
        api.failWithInvalidCredentials()

        val failure = repository.addAccount("dicecloud.com", "dm", "wrong").exceptionOrNull()

        assertTrue(failure is ApiException.InvalidCredentials)
        assertTrue(dao.getAll().isEmpty())
        assertTrue(tokens.accountIds().isEmpty())
        assertNull(active.current())
    }

    @Test
    fun `an unreachable server surfaces as ServerUnreachable, distinct from bad credentials`() = runTest {
        api.loginResult = { throw ApiException.ServerUnreachable("https://nope.example.com", null) }

        val failure = repository.addAccount("nope.example.com", "dm", "p").exceptionOrNull()

        assertTrue(failure is ApiException.ServerUnreachable)
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `signing in again as the same user on the same server updates in place`() = runTest {
        api.loginResult = { LoginSession("u1", "token-A", 10L) }
        val first = repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()

        clock = 2_000L
        api.loginResult = { LoginSession("u1", "token-B", 20L) }
        val second = repository.addAccount("https://dicecloud.com/", "dm", "p").getOrThrow()

        assertEquals("same account id — not a duplicate", first.id, second.id)
        assertEquals(1, dao.getAll().size)
        assertEquals("addedAt is preserved", 1_000L, second.addedAt)
        assertEquals("lastUsedAt moves", 2_000L, second.lastUsedAt)
        assertEquals("the token is replaced", StoredToken("token-B", 20L), tokens.read(first.id))
    }

    @Test
    fun `the same user on two different servers is two accounts`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()

        clock = 2_000L
        api.loginResult = { LoginSession("u1", "t2", null) }
        repository.addAccount("dnd.example.com", "dm", "p").getOrThrow()

        assertEquals(2, dao.getAll().size)
        assertEquals(2, tokens.accountIds().size)
    }

    @Test
    fun `different users on one server are two accounts`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        repository.addAccount("dicecloud.com", "alice", "p").getOrThrow()

        clock = 2_000L
        api.loginResult = { LoginSession("u2", "t2", null) }
        repository.addAccount("dicecloud.com", "bob", "p").getOrThrow()

        assertEquals(2, dao.getAll().size)
    }

    // ---- re-login -------------------------------------------------------------

    @Test
    fun `reLogin replaces the stored token and keeps the account id`() = runTest {
        api.loginResult = { LoginSession("u1", "expired-token", 10L) }
        val account = repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()
        val savesAfterAdd = tokens.saveCount

        clock = 5_000L
        api.loginResult = { LoginSession("u1", "fresh-token", 50L) }
        val refreshed = repository.reLogin(account.id, "p").getOrThrow()

        assertEquals(account.id, refreshed.id)
        assertEquals(StoredToken("fresh-token", 50L), tokens.read(account.id))
        assertEquals("exactly one further token write", savesAfterAdd + 1, tokens.saveCount)
        assertEquals(1, dao.getAll().size)
        // Re-login reuses the stored server origin, so the user is not asked for it again.
        assertEquals("https://dicecloud.com", api.loginCalls.last().first)
    }

    @Test
    fun `reLogin with another users credentials fails and leaves the old token untouched`() = runTest {
        api.loginResult = { LoginSession("u1", "token-A", null) }
        val account = repository.addAccount("dicecloud.com", "alice", "p").getOrThrow()

        api.loginResult = { LoginSession("SOMEONE-ELSE", "token-B", null) }
        val failure = repository.reLogin(account.id, "bobs-password").exceptionOrNull()

        assertTrue(failure is ApiException.AccountMismatch)
        assertEquals(StoredToken("token-A", null), tokens.read(account.id))
        assertEquals("u1", dao.findById(account.id)!!.userId)
    }

    @Test
    fun `reLogin on an unknown account fails with NotFound`() = runTest {
        val failure = repository.reLogin("no-such-account", "p").exceptionOrNull()
        assertTrue(failure is ApiException.NotFound)
    }

    // ---- active account --------------------------------------------------------

    @Test
    fun `setActiveAccount selects the account and bumps lastUsedAt`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        val first = repository.addAccount("dicecloud.com", "alice", "p").getOrThrow()
        clock = 2_000L
        api.loginResult = { LoginSession("u2", "t2", null) }
        val second = repository.addAccount("dicecloud.com", "bob", "p").getOrThrow()

        assertEquals(second.id, repository.activeAccountId.first())

        clock = 3_000L
        repository.setActiveAccount(first.id)

        assertEquals(first.id, repository.activeAccountId.first())
        assertEquals(3_000L, dao.findById(first.id)!!.lastUsedAt)
        // The list is most-recently-used first, so the newly selected account leads.
        assertEquals(first.id, repository.accounts.first().first().id)
        assertEquals(first.id, repository.activeAccount.first()!!.id)
    }

    @Test
    fun `setActiveAccount on an unknown id is a no-op`() = runTest {
        repository.setActiveAccount("ghost")
        assertNull(active.current())
    }

    @Test
    fun `activeAccount reads as none when the selected id no longer exists`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        val account = repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()
        assertNotNull(repository.activeAccount.first())

        dao.deleteById(account.id) // removed behind the repository's back
        assertNull(repository.activeAccount.first())
    }

    // ---- sign-out ----------------------------------------------------------------

    @Test
    fun `signOut deletes the token and the row`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        val account = repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()

        repository.signOut(account.id)

        assertNull(tokens.read(account.id))
        assertNull(dao.findById(account.id))
        assertTrue(repository.accounts.first().isEmpty())
        assertNull(repository.activeAccountId.first())
        assertNull(repository.tokenFor(account.id))
    }

    @Test
    fun `signOut clears the WebView storage for that account's origin`() = runTest {
        // The SSO mechanism puts the token in the origin's localStorage in the clear
        // (docs/design/05-security.md); WP5 found it surviving sign-out.
        api.loginResult = { LoginSession("u1", "t1", null) }
        val account = repository.addAccount("DICECLOUD.com/", "dm", "p").getOrThrow()

        repository.signOut(account.id)

        // The *normalized* origin, which is the one the WebView was ever pointed at.
        assertEquals(listOf("https://dicecloud.com"), webViewSessions.clearedOrigins)
    }

    @Test
    fun `signOut of an account that does not exist does not clear anyone's WebView`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()

        repository.signOut("ghost")

        assertTrue(webViewSessions.clearedOrigins.isEmpty())
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `signOut leaves no snapshot, character, tracker pref or theme pref behind`() = runTest {
        // The row is deleted too, and `accounts.id` is a fresh UUID on the next sign-in,
        // so anything missed here is unreachable forever: no sweep can name the account
        // to delete it. That is the unbounded growth this test exists to stop, and it is
        // also what makes docs/STORE-RELEASE.md's "sign out deletes it" answer true.
        api.loginResult = { LoginSession("u1", "t1", null) }
        val account = repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()
        seedLocalDataFor(account.id)

        assertNotNull("the seed must be real for this test to mean anything", snapshotFor(account.id))

        repository.signOut(account.id)

        assertNull("the cached sheet must be gone", snapshotFor(account.id))
        assertNull("the character list must be gone", characters.read(account.id))
        assertTrue(
            "the pin/hide overrides must be gone",
            database.trackerPrefDao().get(account.id, CREATURE_ID).isEmpty(),
        )
        assertNull("the accent colour must be gone", database.themePrefDao().find(account.id, CREATURE_ID))
    }

    @Test
    fun `signOut deletes only the signed-out account's local data`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        val alice = repository.addAccount("dicecloud.com", "alice", "p").getOrThrow()
        clock = 2_000L
        api.loginResult = { LoginSession("u2", "t2", null) }
        val bob = repository.addAccount("dicecloud.com", "bob", "p").getOrThrow()
        seedLocalDataFor(alice.id)
        seedLocalDataFor(bob.id)

        repository.signOut(alice.id)

        assertNull(snapshotFor(alice.id))
        assertNotNull("bob's cached sheet is not alice's to delete", snapshotFor(bob.id))
        assertNotNull(characters.read(bob.id))
        assertEquals(1, database.trackerPrefDao().get(bob.id, CREATURE_ID).size)
        assertNotNull(database.themePrefDao().find(bob.id, CREATURE_ID))
    }

    /** One row in every per-account store, so a missed `deleteForAccount` cannot pass. */
    private suspend fun seedLocalDataFor(accountId: String) {
        snapshots.store(accountId, CREATURE_ID, SNAPSHOT_BODY)
        characters.write(accountId, listOf(CharacterSummary(CREATURE_ID, "Sabriel")), clock)
        database.trackerPrefDao().upsert(
            TrackerPrefEntity(accountId, CREATURE_ID, "prop-1", pinned = true, hidden = false, sortIndex = null),
        )
        database.themePrefDao().upsert(ThemePrefEntity(accountId, CREATURE_ID, "#7F5AF0"))
    }

    private suspend fun snapshotFor(accountId: String) = snapshots.load(accountId, CREATURE_ID)

    @Test
    fun `signing out of the active account promotes the next most recently used`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        val alice = repository.addAccount("dicecloud.com", "alice", "p").getOrThrow()
        clock = 2_000L
        api.loginResult = { LoginSession("u2", "t2", null) }
        val bob = repository.addAccount("dicecloud.com", "bob", "p").getOrThrow()

        assertEquals(bob.id, repository.activeAccountId.first())
        repository.signOut(bob.id)

        assertEquals(alice.id, repository.activeAccountId.first())
        assertEquals("alice's token must survive", "t1", repository.tokenFor(alice.id))
    }

    @Test
    fun `signing out of a background account leaves the active selection alone`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        val alice = repository.addAccount("dicecloud.com", "alice", "p").getOrThrow()
        clock = 2_000L
        api.loginResult = { LoginSession("u2", "t2", null) }
        val bob = repository.addAccount("dicecloud.com", "bob", "p").getOrThrow()

        repository.signOut(alice.id)

        assertEquals(bob.id, repository.activeAccountId.first())
        assertEquals(1, dao.getAll().size)
    }

    // ---- misc ---------------------------------------------------------------------

    @Test
    fun `getAccount round-trips a persisted row`() = runTest {
        api.loginResult = { LoginSession("u1", "t1", null) }
        val account = repository.addAccount("dicecloud.com", "dm", "p").getOrThrow()

        assertEquals(account, repository.getAccount(account.id))
        assertNull(repository.getAccount("ghost"))
    }

    @Test
    fun `accounts flow is ordered most recently used first`() = runTest {
        dao.upsert(AccountEntity("a", "https://x.example.com", "u1", "alice", 1, 10))
        dao.upsert(AccountEntity("b", "https://x.example.com", "u2", "bob", 1, 30))
        dao.upsert(AccountEntity("c", "https://x.example.com", "u3", "carol", 1, 20))

        assertEquals(listOf("b", "c", "a"), repository.accounts.first().map { it.id })
    }

    private companion object {
        const val CREATURE_ID = "FakeCreature23456"
        const val SNAPSHOT_BODY =
            """{"creatures":[{"_id":"FakeCreature23456"}],"creatureProperties":[],"creatureVariables":{}}"""
    }
}
