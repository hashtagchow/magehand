package com.hashtagchow.magehand.core.data.characters

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.hashtagchow.magehand.core.data.connection.AccountConnection
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConnectionState

/** Where the currently rendered list came from. Drives the offline banner in screen 2. */
enum class CharacterListSource {
    /** Nothing yet — first run, no cache, sub not ready. */
    NONE,

    /** The cached list; the live subscription has not gone ready yet. */
    CACHE,

    /** Straight off the live `characterList` subscription. */
    LIVE,
}

/**
 * Everything screen 2 needs in one value (docs/design/04-screens-ux.md §2).
 *
 * @param characters ordered for display — see [DefaultCharacterListRepository].
 * @param connection the DDP connection state; the status chip renders it verbatim.
 * @param error a *non-fatal* problem worth telling the user about (sub error,
 *   timeout). The cached list keeps rendering underneath it.
 * @param isRefreshing true while a `characterList` sub is in flight — drives the
 *   pull-to-refresh spinner.
 */
data class CharacterListState(
    val characters: List<CharacterSummary> = emptyList(),
    val source: CharacterListSource = CharacterListSource.NONE,
    val cachedAt: Long? = null,
    val connection: ConnectionState = ConnectionState.OFFLINE,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val signedIn: Boolean = false,
)

/**
 * The character list of the active account, live over DDP.
 *
 * One subscription (`characterList`) per connection, shared by every collector:
 * the flow is [SharingStarted.WhileSubscribed] with a grace period, so navigating
 * to a character and back does not churn the subscription.
 */
interface CharacterListRepository {

    val state: StateFlow<CharacterListState>

    /**
     * Pull-to-refresh: stop and re-run the `characterList` subscription
     * (docs/design/04-screens-ux.md §2). A no-op when no account is active.
     */
    fun refresh()
}

/**
 * Production [CharacterListRepository].
 *
 * Mapping rules, all verified against the live table server on 2026-08-17
 * (docs/verification/WP5.md §2 records the raw documents):
 *
 * - the publication yields `creatures`, plus a `users` document for the signed-in
 *   user and a `_subscriptionData` bookkeeping document. Only `creatures` is read;
 * - `isOwnedByMe` compares `owner` to the **live** `DdpClient.userId` rather than
 *   to the stored `Account.userId`, so an account row seeded with the wrong user id
 *   (the debug seeder, WP5 §5) cannot produce wrong ownership badges;
 * - ordering is by name, case-insensitive, so the list does not reshuffle when the
 *   server replays documents in a different order after a reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCharacterListRepository(
    private val connectionManager: DdpConnectionManager,
    private val cache: CharacterCache,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : CharacterListRepository {

    /** Bumped by [refresh]; each new value restarts the subscription. */
    private val refreshRequests = MutableStateFlow(0)

    override val state: StateFlow<CharacterListState> =
        connectionManager.connection
            .flatMapLatest { connection ->
                if (connection == null) flowOf(CharacterListState()) else stateFor(connection)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS), CharacterListState())

    override fun refresh() {
        refreshRequests.update { it + 1 }
    }

    private fun stateFor(connection: AccountConnection): Flow<CharacterListState> = channelFlow {
        val accountId = connection.account.id
        val client = connection.client
        val state = MutableStateFlow(CharacterListState(signedIn = true))

        // Cache first: the list is on screen before the socket is even open.
        cache.read(accountId)?.let { cached ->
            state.update {
                it.copy(
                    characters = cached.characters,
                    source = CharacterListSource.CACHE,
                    cachedAt = cached.cachedAt,
                )
            }
        }

        launch { state.collect { send(it) } }

        launch {
            client.connectionState.collect { connectionState ->
                state.update { it.copy(connection = connectionState) }
            }
        }

        launch {
            refreshRequests.collectLatest {
                state.update { it.copy(isRefreshing = true, error = null) }
                runSubscription(client, accountId, state)
            }
        }

        awaitClose { }
    }

    private suspend fun runSubscription(
        client: com.hashtagchow.magehand.core.ddp.DdpClient,
        accountId: String,
        state: MutableStateFlow<CharacterListState>,
    ) {
        var subscription: com.hashtagchow.magehand.core.ddp.DdpSubscription? = null
        try {
            // LIVE means handshake *and* resume-login are done; subscribing earlier
            // would be answered with a `nosub` for an unauthenticated user.
            client.awaitLive()
            val sub = client.subscribe(SUBSCRIPTION_NAME).also { subscription = it }
            sub.awaitReady()

            // `awaitReady()` returning implies the documents are already in the
            // mirror (docs/verification/WP2.md §7), so the first combine emission
            // is the complete list, not a partial one.
            combine(
                client.mirror.documentsFlow(CREATURES_COLLECTION),
                client.userId,
            ) { creatures, userId -> toSummaries(creatures, userId) }
                .collect { summaries ->
                    cache.write(accountId, summaries, now())
                    state.update {
                        it.copy(
                            characters = summaries,
                            source = CharacterListSource.LIVE,
                            cachedAt = now(),
                            error = null,
                            isRefreshing = false,
                        )
                    }
                }
        } catch (e: DdpError) {
            state.update { it.copy(error = e.reason ?: e.error, isRefreshing = false) }
        } catch (e: Exception) {
            // Timeouts and socket failures: keep whatever is on screen, say why.
            // CancellationException is re-thrown by the runtime check below.
            if (e is kotlinx.coroutines.CancellationException) throw e
            state.update { it.copy(error = e.message ?: "Could not load your characters.", isRefreshing = false) }
        } finally {
            // Cancellation (account switch, refresh) still has to release the
            // server-side subscription, hence NonCancellable.
            withContext(NonCancellable) { subscription?.stop() }
        }
    }

    private fun toSummaries(
        creatures: Map<String, JsonObject>,
        myUserId: String?,
    ): List<CharacterSummary> = creatures.values
        .map { document -> document.toCharacterSummary(myUserId) }
        .sortedBy { it.name.lowercase() }

    private companion object {
        const val SUBSCRIPTION_NAME = "characterList"
        const val CREATURES_COLLECTION = "creatures"

        /**
         * Opening a character and coming back must not tear the subscription down
         * and pay for a fresh one; five seconds covers a navigation round trip.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}

/**
 * Maps one `creatures` document to a [CharacterSummary].
 *
 * Internal rather than private so a future WP4/WP6 test can pin the mapping
 * against `docs/fixtures/`.
 */
internal fun JsonObject.toCharacterSummary(myUserId: String?): CharacterSummary {
    val owner = string("owner").orEmpty()
    val writers = stringArray("writers")
    val isOwnedByMe = myUserId != null && owner == myUserId
    return CharacterSummary(
        creatureId = string(ID_FIELD).orEmpty(),
        name = string("name")?.takeIf { it.isNotBlank() } ?: UNNAMED,
        alignment = string("alignment"),
        gender = string("gender"),
        // `avatarPicture` is what DiceCloud's own list uses; `picture` is the full
        // portrait. Prefer the avatar and fall back, and treat blanks as absent.
        picture = string("avatarPicture") ?: string("picture"),
        owner = owner,
        isOwnedByMe = isOwnedByMe,
        writers = writers,
        // FR-19 decision 18: "a card is editable iff owner == me || writers.contains(me)".
        //
        // Against the **live** user id, not the stored `Account.userId`, for `isOwnedByMe`'s
        // reason — an account row seeded with the wrong id (the debug seeder, WP5 §5) must
        // not be able to hand out an edit capability. A `null` id is "we are not logged in
        // yet", which answers false to both halves; that is the fail-closed direction, and
        // the state resolves itself the moment `login` lands.
        isEditableByMe = myUserId != null && (isOwnedByMe || myUserId in writers),
    )
}

private const val ID_FIELD = "_id"
private const val UNNAMED = "Unnamed character"

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * A `creatures` field that is an array of user ids — today only `writers` (decision 16:
 * *"Membership REALITY is the per-creature `readers`/`writers` arrays"*).
 *
 * Everything that is not a non-blank JSON string is dropped rather than coerced. The field is
 * absent on most creatures and this app has never written it, so the shapes reaching here are
 * whatever DiceCloud and its own clients have put on the sheet over the years — and the one
 * outcome that must not be possible is a malformed entry becoming an id that some `contains`
 * check later matches. `emptyList()` for an absent or unusable field is the same fail-closed
 * answer [toCharacterSummary] gives for a null user id.
 */
private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }
        .orEmpty()
