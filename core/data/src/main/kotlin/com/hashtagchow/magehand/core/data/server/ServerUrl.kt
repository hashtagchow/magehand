package com.hashtagchow.magehand.core.data.server

/**
 * Why a user-entered server address was rejected. [message] is UI-ready copy —
 * the login screen (WP5) shows it verbatim.
 */
enum class ServerUrlProblem(val message: String) {
    /** Nothing (or only whitespace) was entered. */
    EMPTY("Enter your DiceCloud server address."),

    /**
     * The user explicitly asked for `http://`. v1 is https-only
     * (docs/design/05-security.md; `usesCleartextTraffic=false` in the manifest),
     * so this is refused loudly rather than silently upgraded.
     */
    INSECURE_SCHEME(
        "MageHand connects over https only. Remove the \"http://\" prefix, or use an https address.",
    ),

    /** Some scheme other than http/https, e.g. `wss://` or `ftp://`. */
    UNSUPPORTED_SCHEME("Only https addresses are supported."),

    /** `https://user:pass@host` — credentials belong on the sign-in screen, not in the URL. */
    CREDENTIALS_IN_URL(
        "Remove the username and password from the address — you sign in on the next screen.",
    ),

    /** The host is empty, malformed, non-ASCII, or a bare single label such as "dnd". */
    MALFORMED_HOST("That doesn't look like a server address."),

    /** A port was given but is not a number in 1..65535. */
    INVALID_PORT("That port number is not valid."),
}

/** Outcome of [normalizeServerUrl]. */
sealed interface ServerUrlResult {
    /** [origin] is a normalized `https://host[:port]` string, safe to persist and to build URLs from. */
    data class Valid(val origin: String) : ServerUrlResult

    data class Invalid(val problem: ServerUrlProblem) : ServerUrlResult
}

/** The normalized origin, or `null` if the input was rejected. */
fun ServerUrlResult.originOrNull(): String? = (this as? ServerUrlResult.Valid)?.origin

private const val HTTPS_PREFIX = "https://"
private const val HTTP_PREFIX = "http://"

/** Hosts that are legitimately a single label (no dot). */
private val SINGLE_LABEL_ALLOWLIST = setOf("localhost")

private val HOSTNAME_LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

/**
 * Turn whatever the user typed into a canonical https origin.
 *
 * Accepts `dnd.example-table.com`, `https://dicecloud.com/`, `DICECLOUD.COM:8443/character/x?q=1#f`
 * and yields `https://dnd.example-table.com`, `https://dicecloud.com`,
 * `https://dicecloud.com:8443` respectively.
 *
 * Rules:
 * - a missing scheme means https (the common case: the user types a bare hostname);
 * - an explicit `http://` is **rejected**, not upgraded, so the user is never
 *   silently given different security properties than they asked for;
 * - path, query and fragment are discarded — the app only ever needs the origin;
 * - the host is lower-cased; the default port 443 is dropped, any other port kept;
 * - embedded credentials are rejected.
 *
 * Pure and total: no I/O, no DNS, no Android APIs — which is what makes it
 * exhaustively unit-testable on the JVM.
 */
fun normalizeServerUrl(input: String): ServerUrlResult {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ServerUrlResult.Invalid(ServerUrlProblem.EMPTY)

    // Scheme handling. Compare case-insensitively: "HTTPS://x" is a valid URL.
    val lower = trimmed.lowercase()
    val withoutScheme: String = when {
        lower.startsWith(HTTPS_PREFIX) -> trimmed.substring(HTTPS_PREFIX.length)
        lower.startsWith(HTTP_PREFIX) -> return ServerUrlResult.Invalid(ServerUrlProblem.INSECURE_SCHEME)
        // Any other "scheme://" prefix, e.g. wss:// or ftp://.
        SCHEME_PREFIX.containsMatchIn(trimmed) ->
            return ServerUrlResult.Invalid(ServerUrlProblem.UNSUPPORTED_SCHEME)
        // A bare "scheme:" with no slashes (mailto:, javascript:) is not a server address.
        trimmed.substringBefore('/').contains(':') && !PORT_ONLY.containsMatchIn(trimmed.substringBefore('/')) ->
            return ServerUrlResult.Invalid(ServerUrlProblem.UNSUPPORTED_SCHEME)
        else -> trimmed
    }

    // Strip path / query / fragment; whatever remains is the authority.
    val authority = withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')

    if (authority.isEmpty()) return ServerUrlResult.Invalid(ServerUrlProblem.MALFORMED_HOST)
    if (authority.contains('@')) return ServerUrlResult.Invalid(ServerUrlProblem.CREDENTIALS_IN_URL)
    if (authority.any { it.isWhitespace() }) return ServerUrlResult.Invalid(ServerUrlProblem.MALFORMED_HOST)

    val hostRaw: String
    val portPart: String?
    val colonCount = authority.count { it == ':' }
    when (colonCount) {
        0 -> {
            hostRaw = authority
            portPart = null
        }
        1 -> {
            hostRaw = authority.substringBefore(':')
            portPart = authority.substringAfter(':')
        }
        // Bare IPv6 (multiple colons) is not supported: it cannot carry a public
        // TLS certificate name in any setup this app targets.
        else -> return ServerUrlResult.Invalid(ServerUrlProblem.MALFORMED_HOST)
    }

    val port: Int? = if (portPart == null) {
        null
    } else {
        val parsed = portPart.toIntOrNull()
        if (portPart.isEmpty() || parsed == null || parsed !in 1..65535) {
            return ServerUrlResult.Invalid(ServerUrlProblem.INVALID_PORT)
        }
        parsed
    }

    val host = hostRaw.lowercase().removeSuffix(".")
    if (!isValidHost(host)) return ServerUrlResult.Invalid(ServerUrlProblem.MALFORMED_HOST)

    val origin = buildString {
        append(HTTPS_PREFIX)
        append(host)
        // 443 is the https default — carrying it would make two spellings of one
        // origin, and account rows are keyed on (serverUrl, userId).
        if (port != null && port != 443) {
            append(':')
            append(port)
        }
    }
    return ServerUrlResult.Valid(origin)
}

private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val PORT_ONLY = Regex("^[^:]+:\\d+$")

private fun isValidHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    if (host.any { it.code > 127 }) return false // no IDN/punycode handling in v1
    if (IPV4.matches(host)) {
        return host.split('.').all { (it.toIntOrNull() ?: return false) in 0..255 }
    }
    val labels = host.split('.')
    if (labels.any { it.isEmpty() || it.length > 63 || !HOSTNAME_LABEL.matches(it) }) return false
    if (labels.size == 1) return host in SINGLE_LABEL_ALLOWLIST
    // A trailing all-numeric label would be an invalid TLD (and a malformed IPv4).
    return labels.last().none { it.isDigit() } || labels.last().any { it.isLetter() }
}
