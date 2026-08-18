package com.hashtagchow.magehand.core.data.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.ddp.DdpClient
import com.hashtagchow.magehand.core.model.Account

/**
 * The live DDP connection belonging to the **active account**, and nothing else.
 *
 * docs/design/01-architecture.md gives `DdpConnection` the scope "one per active
 * account". This is that object's owner: it watches
 * [AccountRepository.activeAccount] and keeps exactly zero or one [DdpClient]
 * alive, closing the previous one before opening the next. Sign-out therefore
 * closes the socket as a consequence of the account disappearing — there is no
 * second code path that could forget to.
 */
interface DdpConnectionManager {

    /** The connection for the active account, or `null` when no account is active. */
    val connection: StateFlow<AccountConnection?>

    /**
     * Reconnect the current client — the documented follow-up to storing a fresh
     * token after an [com.hashtagchow.magehand.core.data.api.ApiException.TokenRejected]
     * (docs/design/01-architecture.md, "Error handling doctrine").
     */
    fun restart()
}

/**
 * An account paired with its live client. Handed out as a unit so no caller can
 * ever read a client and an account that disagree about who is signed in.
 */
class AccountConnection(
    val account: Account,
    val client: DdpClient,
) : AutoCloseable {
    /** `wss://…/websocket` derived from the account's https origin. */
    val webSocketUrl: String get() = websocketUrlFor(account.serverUrl)

    override fun close() = client.close()

    override fun toString(): String = "AccountConnection(account=${account.id}, url=$webSocketUrl)"
}

/**
 * `https://dnd.example-table.com` → `wss://dnd.example-table.com/websocket`
 * (docs/design/02-ddp-and-api.md — the endpoint littleguy's nginx already
 * upgrades). The input is always a normalized origin from `normalizeServerUrl`,
 * so this is a scheme swap and a suffix, not a URL parser.
 */
fun websocketUrlFor(serverOrigin: String): String =
    serverOrigin.removeSuffix("/").replaceFirst("https://", "wss://") + "/websocket"

/**
 * Production [DdpConnectionManager].
 *
 * [clientFactory] is injected so tests can hand back a client driven by
 * `:core:ddp`'s fake websocket instead of opening a real socket.
 *
 * @param scope an application-lifetime scope. The collector lives as long as the
 *   process; there is nothing to cancel because the last account switch always
 *   leaves the correct state behind.
 */
class DefaultDdpConnectionManager(
    private val accountRepository: AccountRepository,
    private val scope: CoroutineScope,
    private val clientFactory: (Account, suspend () -> String?) -> DdpClient = { account, tokenProvider ->
        DdpClient.okHttp(
            url = websocketUrlFor(account.serverUrl),
            resumeTokenProvider = tokenProvider,
        )
    },
) : DdpConnectionManager {

    private val _connection = MutableStateFlow<AccountConnection?>(null)
    override val connection: StateFlow<AccountConnection?> = _connection.asStateFlow()

    init {
        scope.launch {
            accountRepository.activeAccount
                // `activeAccount` re-emits whenever `lastUsedAt` is bumped. Only an
                // identity or server change may tear down a working socket.
                .map { it?.let { a -> ConnectionKey(a.id, a.serverUrl) to a } }
                .distinctUntilChanged { old, new -> old?.first == new?.first }
                .collect { keyed ->
                    _connection.value?.close()
                    _connection.value = keyed?.second?.let { account ->
                        val client = clientFactory(account) { accountRepository.tokenFor(account.id) }
                        client.start()
                        AccountConnection(account, client)
                    }
                }
        }
    }

    override fun restart() {
        _connection.value?.client?.restart()
    }

    private data class ConnectionKey(val accountId: String, val serverUrl: String)
}
