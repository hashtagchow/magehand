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
import com.hashtagchow.magehand.core.ddp.DdpClientConfig
import com.hashtagchow.magehand.core.ddp.OkHttpDdpSocketFactory
import com.hashtagchow.magehand.core.model.Account
import okhttp3.OkHttpClient

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
    private val clientFactory: (Account, suspend () -> String?) -> DdpClient = sharedClientFactory(),
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

    companion object {

        /**
         * The production [clientFactory]: every account's [DdpClient] is built on **one**
         * `OkHttpClient`.
         *
         * The bug this closes: the old default called `DdpClient.okHttp(url)` with no
         * client, so each account switch default-constructed a fresh `OkHttpClient`
         * carrying its own dispatcher thread pool and connection pool. `DdpClient.close()`
         * shuts down its own executor but — correctly, since it does not own it — not
         * OkHttp's, so every switch stranded a pool set on a ~60 s idle timer. Sharing is
         * the architectural fix rather than teaching `close()` to tear the client down,
         * because sharing is also what OkHttp is designed for, and a client that shuts
         * down something it was handed is a worse contract than one that never does.
         *
         * @param httpClient the process's client. `DataModule` passes the same base the
         *   REST API uses, so there is exactly one dispatcher and one connection pool in
         *   the app; the default is for tests and for callers with no DI graph.
         * @param config the client configuration every account's socket is built with. Present
         *   so that `DataModule` can attach a debug-only log sink (`DebugLogSinks`) without
         *   the manager having to know what a `DdpClientConfig` is for; the default is the
         *   production one, sink included at `{}`. Captured by [build]'s default value, so a
         *   test that supplies its own [build] is deliberately unaffected by it.
         * @param build the `url → client` seam, injectable purely so a test can observe
         *   *which* `OkHttpClient` each account was given.
         */
        internal fun sharedClientFactory(
            httpClient: OkHttpClient = OkHttpDdpSocketFactory.defaultClient(),
            config: DdpClientConfig = DdpClientConfig(),
            build: (String, OkHttpClient, suspend () -> String?) -> DdpClient =
                { url, client, tokenProvider ->
                    DdpClient.okHttp(
                        url = url,
                        config = config,
                        httpClient = client,
                        resumeTokenProvider = tokenProvider,
                    )
                },
        ): (Account, suspend () -> String?) -> DdpClient = { account, tokenProvider ->
            build(websocketUrlFor(account.serverUrl), httpClient, tokenProvider)
        }
    }
}
