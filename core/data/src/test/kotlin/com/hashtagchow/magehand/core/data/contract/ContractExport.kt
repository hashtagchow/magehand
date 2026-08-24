package com.hashtagchow.magehand.core.data.contract

import com.hashtagchow.magehand.core.data.connection.websocketUrlFor
import com.hashtagchow.magehand.core.data.session.DdpCreatureFeed
import com.hashtagchow.magehand.core.data.server.normalizeServerUrl
import com.hashtagchow.magehand.core.data.server.originOrNull
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.tracker.InventoryEngine
import com.hashtagchow.magehand.core.data.tracker.TrackerEngine
import com.hashtagchow.magehand.core.data.write.RestType
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.data.write.WriteOperation
import com.hashtagchow.magehand.core.data.write.WriteQueueConfig
import com.hashtagchow.magehand.core.ddp.DdpClient
import com.hashtagchow.magehand.core.ddp.DdpClientConfig
import com.hashtagchow.magehand.core.ddp.ExponentialBackoff
import com.hashtagchow.magehand.core.ddp.MeteorId
import com.hashtagchow.magehand.core.ddp.ejsonParams
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.DamageDefense
import com.hashtagchow.magehand.core.model.EquipGroup
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryContainer
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.ItemCatalog
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RollModifier
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.Wallet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Emits `contract-export/` — MageHand's probe-verified server contract, as data.
 *
 * ### What this is for
 *
 * The sibling **WebHand** web app targets parity with this one against the same DiceCloud
 * server, and every server fact in this repo was bought with a live probe: `description` must
 * be an object, `order` is mandatory on an insert, `equip` reparents, `damage {set}` takes the
 * *remaining* value, `adjustQuantity increment` is a consumption amount, death saves are spell
 * slots with no reset rule, soft-removed properties keep streaming. A second client
 * re-deriving those from the same server would re-learn each of them the same way MageHand did
 * — by shipping the bug first. So the contract is exported instead, and vendored
 * (webhand design 01 §6).
 *
 * ### The one rule this file is built around
 *
 * **Nothing here may be hand-written JSON.** Every wire frame is recorded off a real
 * [DdpClient] driven through [ContractDdpRecorder]; every discovery output is whatever
 * [TrackerEngine] and [InventoryEngine] returned when run on the fixture; the catalog is
 * [ItemCatalog]'s own list; the tag sets are [InventoryEngine]'s own constants; the rate
 * spacings are [WriteOp]'s own. A hand-typed vector is a second implementation of the
 * protocol, and a second implementation is a thing that can be wrong on its own — silently,
 * because nothing would ever compare it to the first.
 *
 * The few places that could not be generated are marked `"generated": false` in the output
 * and listed in the export's README, so a consumer can tell a recorded fact from a stated one.
 *
 * ### Public safety
 *
 * The export is vendored into a repo that may be mirrored publicly, so it is built only from
 * [ContractFixtures] — synthetic sheets, seeded ids from the production generator, no live
 * capture, no party data, no private hostname, no token. `ContractExportTest` re-checks that
 * with `tools/public-gate.sh`'s own pattern list rather than trusting this paragraph.
 */
object ContractExport {

    /**
     * Bumped when the *shape* of any exported document changes. Consumers pin on it.
     *
     * **2** — `ddp/timings.json` added, and `ddp/handshake.json`'s steps replaced the single
     * `expectedReply` object with an `expectedReplies` ARRAY. The rename is deliberate: login
     * completes on two frames, not one, and a consumer still reading `expectedReply` should
     * fail on a missing key rather than silently keep the one-frame reading this bump exists
     * to correct.
     *
     * **3** — `ddp/timings.json` gained a `resubscribe` object: the reconnect replay's stagger
     * and its single retry delay. Bumped rather than added quietly *because* a consumer that
     * misses it is the specific harm — a sibling client that reconnects N subscriptions in one
     * tick is the storm against the shared 50-per-10 s subscription bucket that these two knobs
     * exist to prevent, and it costs the whole table, not the client that ships it. A pin on the
     * version is how a vendored copy finds out there is something new to port.
     */
    const val SCHEMA_VERSION: Int = 3

    /** Where the export lives, relative to the repository root. */
    const val DIRECTORY: String = "contract-export"

    const val MANIFEST: String = "manifest.json"

    /**
     * A fixed EJSON timestamp for the login result.
     *
     * 2027-01-01T00:00:00Z. A constant rather than "now" because the export is a committed
     * golden file: a wall clock in it would make every regeneration a diff.
     */
    private const val TOKEN_EXPIRES_MILLIS: Long = 1_798_761_600_000L

    /**
     * The client mints subscription ids with `MeteorId.random()`, so a recorded `sub` frame
     * carries a value that changes every run. The recorded id is replaced by this placeholder
     * — and `ContractExportTest` asserts the *real* one is a valid Meteor id first, so the
     * substitution is a normalization of proven shape rather than an invention.
     */
    const val SUB_ID_PLACEHOLDER: String = "<client-generated-meteor-id>"

    /** Same story for the heartbeat's `hb-<8 chars>` id. */
    const val PING_ID_PLACEHOLDER: String = "<client-generated-heartbeat-id>"

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    // =======================================================================
    // Entry point
    // =======================================================================

    /**
     * Builds the whole export as `relative path → file content`.
     *
     * @param sourceCommit the git sha the export was generated at. Provenance only — see
     *   `ContractExportTest` for why it is inherited rather than recomputed when pinning.
     * @param generatedOn an ISO-8601 date. The only wall-clock-derived value in the export.
     */
    fun generate(sourceCommit: String, generatedOn: String): Map<String, String> {
        val recording = record()

        val files = linkedMapOf(
            "ddp/handshake.json" to render(handshake(recording)),
            "ddp/timings.json" to render(timings()),
            "ddp/subscriptions.json" to render(subscriptions(recording)),
            "ddp/rate-limits.json" to render(rateLimits()),
            "ddp/method-vectors.json" to render(methodVectors(recording)),
            "domain/rules.json" to render(rules()),
            "domain/discovery-vectors.json" to render(discoveryVectors()),
            "item-catalog.json" to render(itemCatalog()),
        )
        files["README.md"] = readme()
        files[MANIFEST] = render(manifest(files, sourceCommit, generatedOn))
        return files
    }

    /** Trailing newline: every other text file in this repo has one, and diffs are nicer. */
    private fun render(document: JsonObject): String = json.encodeToString(JsonObject.serializer(), document) + "\n"

    // =======================================================================
    // Recording — the production DdpClient's own frames
    // =======================================================================

    /** Everything one scripted session produced, in the order the client sent it. */
    class Recording(
        val connectFrame: JsonObject,
        val loginFrame: JsonObject,
        /**
         * Both frames the server sent to complete `login`, in the order they arrived.
         *
         * Recorded rather than described. This used to be a hand-built `result` object, and
         * hand-building it is exactly how the export came to state a one-frame login while
         * the production client was waiting for two — the under-recording webhand's WP-B
         * found.
         */
        val loginReplyFrames: List<JsonObject>,
        val pongFrame: JsonObject,
        val pingFrame: JsonObject,
        val rawPingId: String,
        val subscriptionFrames: Map<String, JsonObject>,
        val rawSubscriptionIds: List<String>,
        /** Vector name → the `method` frame the client sent for it. */
        val methodFrames: Map<String, JsonObject>,
    )

    private fun record(): Recording {
        val recorder = ContractDdpRecorder(ContractFixtures.userId, TOKEN_EXPIRES_MILLIS)
        val methodFrames = LinkedHashMap<String, JsonObject>()
        val subFrames = LinkedHashMap<String, JsonObject>()
        val rawSubIds = ArrayList<String>()

        val pongFrame = DdpClient(
            socketFactory = recorder,
            // Heartbeat off: a ping landing between two method frames would reorder the
            // recording run to run. The ping/pong pair is captured explicitly below instead.
            config = DdpClientConfig(heartbeatInterval = Duration.ZERO),
            resumeTokenProvider = { ContractFixtures.resumeToken },
        ).use { client ->
            runBlocking {
                client.connect()

                // Both publications this app uses (02 §Publications we use).
                for ((name, params) in listOf<Pair<String, List<JsonElement>>>(
                    CHARACTER_LIST to emptyList(),
                    SINGLE_CHARACTER to ejsonParams(ContractFixtures.creatureId),
                )) {
                    val sub = client.subscribe(name, params)
                    sub.awaitReady()
                    rawSubIds += sub.id
                    subFrames[name] = recorder.awaitFrame {
                        it.frameString("msg") == "sub" && it.frameString("id") == sub.id
                    }
                }

                // `call` returns only after both `result` and `updated`, so by the time it
                // does the frame is already recorded. Taking it by INDEX rather than by
                // matching on its contents keeps two vectors that happen to produce the same
                // frame — there are none today — from collapsing onto one recording.
                for (vector in VECTORS) {
                    val before = recorder.framesOf("method").size
                    client.call(vector.op.method, vector.op.params)
                    val sent = recorder.framesOf("method")
                    check(sent.size == before + 1) {
                        "vector ${vector.name} sent ${sent.size - before} method frames, expected 1"
                    }
                    methodFrames[vector.name] = sent[before]
                }

                // The server-initiated half of the heartbeat: it pings, we pong.
                recorder.pushServerPing(SERVER_PING_ID)
                recorder.awaitFrame {
                    it.frameString("msg") == "pong" && it.frameString("id") == SERVER_PING_ID
                }
            }
        }

        // A second, short-lived client with the heartbeat ON, purely to record a real
        // client-initiated `ping`. Kept out of the session above so the method frames stay in
        // a fixed order — see the `heartbeatInterval = ZERO` note.
        val pinger = ContractDdpRecorder(ContractFixtures.userId, TOKEN_EXPIRES_MILLIS)
        val pingFrame = DdpClient(
            socketFactory = pinger,
            config = DdpClientConfig(heartbeatInterval = HEARTBEAT_PROBE_INTERVAL),
            resumeTokenProvider = { ContractFixtures.resumeToken },
        ).use { heartbeatClient ->
            runBlocking {
                heartbeatClient.connect()
                pinger.awaitFrame { it.frameString("msg") == "ping" }
            }
        }

        val frames = recorder.frames()
        val loginFrame = frames.first {
            it.frameString("msg") == "method" && it.frameString("method") == ContractDdpRecorder.LOGIN
        }
        val loginId = loginFrame.frameString("id") ?: error("the recorded login frame carries no id")
        val loginReplies = recorder.repliesForMethod(loginId)
        check(loginReplies.map { it.frameString("msg") } == listOf("updated", "result")) {
            "login must complete on `updated` then `result` (WP2 §3.1), recorded " +
                loginReplies.map { it.frameString("msg") }
        }
        return Recording(
            connectFrame = frames.first { it.frameString("msg") == "connect" },
            loginFrame = loginFrame,
            loginReplyFrames = loginReplies,
            pongFrame = pongFrame,
            pingFrame = pingFrame,
            rawPingId = pingFrame.frameString("id").orEmpty(),
            subscriptionFrames = subFrames,
            rawSubscriptionIds = rawSubIds,
            methodFrames = methodFrames,
        )
    }

    /**
     * The `singleCharacter` publication name, straight off the production feed's constant.
     */
    private const val SINGLE_CHARACTER = DdpCreatureFeed.SUBSCRIPTION

    /**
     * The `characterList` publication name — **restated, not reused.**
     *
     * `DefaultCharacterListRepository` keeps it in a `private companion object`, so there is
     * no production constant this module can read. Rather than widen a production visibility
     * to suit an exporter — which would be a product change made for a test's convenience —
     * the name is written here and the export marks it `nameGenerated: false`. The `sub`
     * *frame* around it is still recorded off the real client, so only this one string is a
     * stated fact; `ContractExportTest` asserts the recorded frame carries it.
     */
    private const val CHARACTER_LIST = "characterList"

    private const val SERVER_PING_ID = "server-ping-1"
    private val HEARTBEAT_PROBE_INTERVAL: Duration = 50.milliseconds

    /** Replaces a frame's `id` with [placeholder]; see [SUB_ID_PLACEHOLDER]. */
    private fun JsonObject.withNormalizedId(placeholder: String): JsonObject =
        JsonObject(toMutableMap().apply { put("id", JsonPrimitive(placeholder)) })

    // =======================================================================
    // ddp/handshake.json
    // =======================================================================

    private fun handshake(recording: Recording): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put(
            "purpose",
            "The DDP connect/login exchange, recorded off the production DdpClient. " +
                "Frames marked `sent` are what the client puts on the wire; `expectedReplies` " +
                "lists EVERY frame the step completes on, in order — a list rather than a " +
                "single object because `login` is a method, and a method completes on two.",
        )
        put("endpoint", endpoint())
        put(
            "sequence",
            buildJsonArray {
                add(
                    step(
                        name = "connect",
                        note = "DDP version negotiation. Always the first frame on a new socket, " +
                            "including after a reconnect — a Meteor session is per-socket.",
                        sent = recording.connectFrame,
                        expectedReplies = listOf(
                            buildJsonObject {
                                put("msg", "connected")
                                put("session", "<server-generated session id>")
                            },
                        ),
                        // The recorded reply carries the fake's own session id, which is not a
                        // server fact — the placeholder above says more than recording it would.
                        repliesGenerated = false,
                        completesOn = "The single `connected` frame.",
                    ),
                )
                add(
                    step(
                        name = "login",
                        note = "Resume-token login. The token is the same string `POST /api/login` " +
                            "returns and is also the REST bearer. It comes from a limited per-user " +
                            "pool and may be evicted before `tokenExpires`, so an auth failure is a " +
                            "normal outcome and must route to re-login rather than to a retry.",
                        sent = recording.loginFrame,
                        // RECORDED, not described — see Recording.loginReplyFrames.
                        expectedReplies = recording.loginReplyFrames,
                        repliesGenerated = true,
                        completesOn = "BOTH frames. `login` is an ordinary Meteor method and obeys " +
                            "the method-completion rule below: the production client holds the call " +
                            "open until `result` AND `updated` have both landed, and treats them as " +
                            "two independent flags rather than a sequence.",
                        quirk = "The live server sends `updated` BEFORE `result` for `login` — the " +
                            "reverse of every other method here, and the reverse of design 02's own " +
                            "transcript (observed 2026-08-17, docs/verification/WP2.md §3.1). The " +
                            "frames above are recorded in that live order. Two consequences for a " +
                            "second client: do not wait for `result` first and then start waiting " +
                            "for `updated`, and do not treat an early `updated` as belonging to " +
                            "some other call. `updated` is also sent for a FAILED login (the 403 " +
                            "path), so waiting for both never hangs on an error.",
                    ),
                )
                add(
                    step(
                        name = "heartbeat.clientPing",
                        note = "Client-initiated DDP ping. The id is minted per ping as " +
                            "`hb-` plus 8 unmistakable characters and is normalized here.",
                        sent = recording.pingFrame.withNormalizedId(PING_ID_PLACEHOLDER),
                        expectedReplies = listOf(
                            buildJsonObject {
                                put("msg", "pong")
                                put("id", PING_ID_PLACEHOLDER)
                            },
                        ),
                        repliesGenerated = false,
                        completesOn = "The matching `pong`, within the pong deadline in " +
                            "ddp/timings.json. No pong means the socket is dead.",
                    ),
                )
                add(
                    step(
                        name = "heartbeat.serverPing",
                        note = "The other direction: the server pings, the client echoes the id back. " +
                            "A client that does not answer is dropped.",
                        sent = recording.pongFrame,
                        expectedReplies = emptyList(),
                        repliesGenerated = false,
                        completesOn = "Nothing. The client's `pong` IS the reply.",
                    ),
                )
            },
        )
        put(
            "methodCompletion",
            "A method is complete only when BOTH `result` (its return value or error) and " +
                "`updated` (the server's writes flushed to this client) have arrived. Acting on " +
                "`result` alone reads a mirror the write has not landed in yet. EITHER ORDER is " +
                "legal and both occur on this server — see the `login` step's quirk — so the two " +
                "must be tracked as independent flags and never as a sequence. This applies to " +
                "`login` exactly as it does to every vector in ddp/method-vectors.json; the " +
                "deadline for both frames is `methodTimeoutMillis` in ddp/timings.json.",
        )
        put(
            "reconnect",
            "A dropped socket means a new socket, a fresh `connect`, a fresh `login` with a " +
                "re-read token, and every subscription re-sent with its ORIGINAL id. Server " +
                "state is per-session. In-flight methods are NOT replayed: most writes are " +
                "increments, and replaying one whose result was never seen corrupts the sheet.",
        )
        put("ejson", ejsonNote())
        put("meteorIds", meteorIdNote())
    }

    /**
     * One step of the handshake.
     *
     * `generated` describes the `sent` frame, which is always recorded off the production
     * client. `repliesGenerated` describes `expectedReplies` separately, because the two
     * halves have different provenance: login's replies are the recorded frames that actually
     * satisfied the client, while the others are stated shapes standing in for values the
     * fake server invents (a session id) or already normalizes (a ping id).
     */
    private fun step(
        name: String,
        note: String,
        sent: JsonObject,
        expectedReplies: List<JsonElement>,
        repliesGenerated: Boolean,
        completesOn: String,
        quirk: String? = null,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("note", note)
        put("generated", true)
        put("sent", sent)
        put("expectedReplies", JsonArray(expectedReplies))
        put("repliesGenerated", repliesGenerated)
        put("completesOn", completesOn)
        quirk?.let { put("quirk", it) }
    }

    private fun endpoint(): JsonObject {
        // Built with the production URL helpers rather than by string-concatenation here, so
        // the export cannot state a URL shape the app does not actually use.
        val origin = normalizeServerUrl(EXAMPLE_HOST).originOrNull()
            ?: error("the production normalizer rejected $EXAMPLE_HOST")
        return buildJsonObject {
            put(
                "note",
                "The origin is whatever the user typed — the app hardcodes no server. " +
                    "`$EXAMPLE_HOST` is the public DiceCloud instance and is used here only as " +
                    "a worked example of the URL shapes.",
            )
            put("exampleOrigin", origin)
            put("websocketUrl", websocketUrlFor(origin))
            put("loginPath", "/api/login")
            put("snapshotPath", "/api/creature/{creatureId}")
            put(
                "snapshotNote",
                "`GET /api/creature/:id` with an `Authorization: Bearer <resume token>` header " +
                    "returns `{creatures, creatureProperties, creatureVariables}` and forces a " +
                    "recompute if the sheet is stale. It is the same document shape the " +
                    "`singleCharacter` subscription streams, which is what lets one parser serve " +
                    "the live mirror and the offline snapshot.",
            )
            put("transportSecurity", "https/wss only. A plaintext origin is rejected by the normalizer.")
        }
    }

    private const val EXAMPLE_HOST = "dicecloud.com"

    private fun ejsonNote(): JsonObject = buildJsonObject {
        put(
            "note",
            "DiceCloud uses exactly one EJSON extension: dates, as `{\"\$date\": <epoch millis>}`. " +
                "Everything else in its collections is plain JSON. Documents are best stored " +
                "verbatim — leaving dates wrapped keeps a live mirror byte-comparable with a REST " +
                "snapshot, so round-tripping a document back onto the wire is the identity.",
        )
        put("dateKey", "\$date")
        put("example", buildJsonObject { put("\$date", TOKEN_EXPIRES_MILLIS) })
    }

    private fun meteorIdNote(): JsonObject = buildJsonObject {
        put("length", 17)
        put("alphabet", MeteorId.UNMISTAKABLE_CHARS)
        put(
            "note",
            "Meteor's UNMISTAKABLE_CHARS — no 0, 1, I, O, l, U or V. Every id in this export is " +
                "drawn from this alphabet by the production generator with a fixed seed, so the " +
                "vectors exercise real id validators while containing no real identifier.",
        )
    }

    // =======================================================================
    // ddp/timings.json
    // =======================================================================

    /**
     * The reconnect schedule, **probed off the production [ExponentialBackoff]** rather than
     * restated from its constructor defaults.
     *
     * `initialMillis`, `maxMillis`, `factor` and `jitterRatio` are `private val` constructor
     * parameters, so no field on the class can be read and no accessor exists. Widening one to
     * suit an exporter would be a product change made for a test's convenience — the argument
     * [CHARACTER_LIST]'s KDoc already makes — and retyping the four numbers here would put a
     * second copy of the schedule in the repo, which is the thing this whole file refuses.
     *
     * So the class is *driven* instead. `delayMillis` is `lo + random.nextDouble() * (hi - lo)`,
     * so a [Random] pinned to `0.0` returns an attempt's exact lower bound and one pinned to
     * `1.0` its exact upper bound. Both bounds are therefore computed by the production class
     * from its own defaults, and the four parameters are arithmetic over those bounds:
     *
     * ```
     *   base(n)     = (lo(n) + hi(n)) / 2      — while unclamped, the jitter is symmetric
     *   initial     = base(0)
     *   factor      = base(1) / base(0)
     *   max         = hi(saturated)            — the cap the schedule flattens against
     *   jitterRatio = (hi(0) - lo(0)) / (2 * base(0))
     * ```
     *
     * Every step is `check`ed, so an unexpected clamp fails the export rather than publishing
     * a wrong number, and `ContractExportTest` re-samples the *actually configured* policy
     * (`DdpClientConfig().backoff`, on a real [Random]) against the exported bounds.
     */
    class BackoffSchedule(
        val initialMillis: Long,
        val factor: Double,
        val maxMillis: Long,
        val jitterRatio: Double,
        val saturatesAtAttempt: Int,
        /** Attempt index → the exact `(min, max)` delay the policy can return for it. */
        val bounds: List<Pair<Long, Long>>,
    )

    /** A [Random] that always yields the bottom of the range; see [BackoffSchedule]. */
    private object FloorRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = 0.0
    }

    /** A [Random] that always yields the top of the range; see [BackoffSchedule]. */
    private object CeilRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = 1.0
    }

    /** Enough attempts to show the ramp AND the cap, plus one repeat that proves it flattened. */
    const val BACKOFF_ATTEMPTS: Int = 8

    /** Past `ExponentialBackoff`'s own exponent clamp, so the base is certainly saturated. */
    private const val BACKOFF_SATURATED_ATTEMPT: Int = 63

    val backoffSchedule: BackoffSchedule by lazy {
        check(DdpClientConfig().backoff is ExponentialBackoff) {
            "the export describes ExponentialBackoff; DdpClientConfig now defaults to something else"
        }
        val low = ExponentialBackoff(random = FloorRandom)
        val high = ExponentialBackoff(random = CeilRandom)
        val bounds = (0 until BACKOFF_ATTEMPTS).map { low.delayMillis(it) to high.delayMillis(it) }

        val cap = high.delayMillis(BACKOFF_SATURATED_ATTEMPT)
        val saturates = bounds.indices.firstOrNull { it + 1 < bounds.size && bounds[it] == bounds[it + 1] }
            ?: error("the schedule did not flatten within $BACKOFF_ATTEMPTS attempts; raise BACKOFF_ATTEMPTS")
        check(bounds[saturates].second == cap) {
            "the flattened attempt's ceiling ${bounds[saturates].second} is not the cap $cap"
        }

        // The derivation is only valid on attempts the cap has not touched.
        check(saturates >= 2) { "attempts 0 and 1 must be unclamped to derive initial and factor" }
        val base0 = (bounds[0].first + bounds[0].second) / 2.0
        val base1 = (bounds[1].first + bounds[1].second) / 2.0
        check(base0 > 0 && base0 == base0.toLong().toDouble()) { "initial delay $base0 is not a whole millisecond" }

        BackoffSchedule(
            initialMillis = base0.toLong(),
            factor = base1 / base0,
            maxMillis = cap,
            jitterRatio = (bounds[0].second - bounds[0].first) / (2 * base0),
            saturatesAtAttempt = saturates,
            bounds = bounds,
        )
    }

    /**
     * The connection's clocks — the facts WebHand's WP-B was otherwise porting by eye.
     *
     * A protocol contract that states the frames but not the deadlines is only half a
     * contract: a client that waits 5 s for a handshake this one gives 20 s will call a slow
     * venue's wifi a failure, and one that pings on a schedule the server's own idle reaper
     * disagrees with gets dropped for being quiet. Every value here is read off, or probed
     * out of, the production configuration objects — none is typed twice.
     */
    private fun timings(): JsonObject {
        val config = DdpClientConfig()
        val schedule = backoffSchedule
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put(
                "purpose",
                "Every timeout, interval and delay the production client runs on, exported from " +
                    "the configuration objects that hold them. Consumers should treat these as " +
                    "the values MageHand is known to work against this server with, not as " +
                    "protocol minimums — the server publishes none.",
            )
            put(
                "connection",
                buildJsonObject {
                    put("generated", true)
                    put("source", "DdpClientConfig() — the production defaults, read at export time")
                    put("handshakeTimeoutMillis", config.handshakeTimeout.inWholeMilliseconds)
                    put(
                        "handshakeTimeoutNote",
                        "Covers the socket open AND the `connect`/`connected` round trip together, " +
                            "not just one of them. Exceeding it drops the socket into the reconnect " +
                            "schedule below.",
                    )
                    put("methodTimeoutMillis", config.methodTimeout.inWholeMilliseconds)
                    put(
                        "methodTimeoutNote",
                        "The budget for a method to produce BOTH `result` and `updated` — see " +
                            "ddp/handshake.json#methodCompletion. It is not a per-frame timeout: a " +
                            "`result` that arrives with no `updated` behind it still times the call " +
                            "out. `login` runs on this same budget. A timeout raises a connection " +
                            "error rather than a method error, and the call is NOT retried — most " +
                            "writes here are increments, and replaying one whose result was never " +
                            "seen corrupts the sheet.",
                    )
                    put("heartbeatIntervalMillis", config.heartbeatInterval.inWholeMilliseconds)
                    put(
                        "heartbeatIntervalNote",
                        "How often the client sends its own DDP `ping`. Zero disables client-side " +
                            "pings entirely, which is a supported configuration — the server pings " +
                            "too, and answering that is mandatory either way.",
                    )
                    put("pongDeadlineMillis", config.heartbeatTimeout.inWholeMilliseconds)
                    put(
                        "pongDeadlineNote",
                        "How long the client waits for the `pong` matching its own `ping` before " +
                            "declaring the socket dead and reconnecting. Strictly shorter than the " +
                            "interval, so a missed pong is diagnosed before the next ping is due " +
                            "and two probes can never be outstanding at once.",
                    )
                },
            )
            put(
                "reconnectBackoff",
                buildJsonObject {
                    put("generated", true)
                    put("policy", "ExponentialBackoff")
                    put(
                        "source",
                        "Probed out of the production class by driving it with a Random pinned to " +
                            "each end of its jitter range — the four parameters are private " +
                            "constructor defaults with no accessor. See ContractExport.BackoffSchedule.",
                    )
                    put("initialMillis", schedule.initialMillis)
                    put("factor", schedule.factor)
                    put("maxMillis", schedule.maxMillis)
                    put("jitterRatio", schedule.jitterRatio)
                    put("saturatesAtAttempt", schedule.saturatesAtAttempt)
                    put(
                        "formula",
                        "delay(n) = clamp(initial * factor^n, ..max) spread uniformly over " +
                            "base ± base*jitterRatio, re-clamped to ..max. Attempt numbering is " +
                            "0-based and resets on a successful connect.",
                    )
                    put(
                        "schedule",
                        buildJsonArray {
                            for ((attempt, range) in schedule.bounds.withIndex()) {
                                add(
                                    buildJsonObject {
                                        put("attempt", attempt)
                                        put("minDelayMillis", range.first)
                                        put("maxDelayMillis", range.second)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "jitterNote",
                        "The jitter is not decoration. A whole table of players reconnects the " +
                            "instant the venue's wifi hiccups, and an unjittered schedule marches " +
                            "all of them at the server in lockstep at 1 s, 2 s, 4 s. Do not port " +
                            "the schedule and drop the spread.",
                    )
                    put(
                        "authNote",
                        "A REJECTED resume token stops the loop instead of backing off — the client " +
                            "parks in an auth-failed state and waits for a fresh token. Retrying a " +
                            "credential the server just refused only spends the rate limit.",
                    )
                },
            )
            put(
                "resubscribe",
                buildJsonObject {
                    put("generated", true)
                    put("source", "DdpClientConfig() — the production defaults, read at export time")
                    put("staggerMillis", config.resubscribeStagger.inWholeMilliseconds)
                    put(
                        "staggerNote",
                        "Spacing between the subscriptions replayed after a reconnect, paid " +
                            "BETWEEN sends and never before the first — a client with one " +
                            "subscription pays nothing. Port this. The server's subscription " +
                            "rate limit is 50 per 10 s GLOBAL ACROSS ALL USERS, so the danger is " +
                            "not one client's burst but the shape a router blip makes: every " +
                            "client at the table reconnects in the same second and each fires N " +
                            "subs in one tick. A client that replays N-at-once spends a bucket " +
                            "the whole table draws on, and the client that loses that race is " +
                            "usually not the one that caused it.",
                    )
                    put("retryDelayMillis", config.resubscribeRetryDelay.inWholeMilliseconds)
                    put("maxRetries", 1)
                    put(
                        "retryDelayNote",
                        "A `nosub` answering a REPLAYED subscription is re-sent once, after this " +
                            "delay, with the same sub id. At 50 per 10 s the bucket refills at " +
                            "five per second, so one second is the shortest wait that has " +
                            "demonstrably made room. Exactly one retry: congestion that survives " +
                            "a second of drain is a verdict, not congestion, and a client that " +
                            "keeps re-sending a refusal is the storm this is meant to avoid.",
                    )
                    put(
                        "appliesTo",
                        "The reconnect replay ONLY. A fresh `sub` from user navigation pays " +
                            "neither the stagger nor the retry: the entry burst is permitted " +
                            "(six subs is roughly 12% of the window) and a `nosub` answering a " +
                            "fresh subscribe is a verdict about the request, which must surface " +
                            "rather than be argued with.",
                    )
                },
            )
            put(
                "rateLimit",
                buildJsonObject {
                    put("windowMillis", RATE_WINDOW_MILLIS)
                    put("windowMillisGenerated", false)
                    put("retryAfterRateLimitMillis", WriteQueueConfig().rateLimitBackoffMillis)
                    put("retryAfterRateLimitMillisGenerated", true)
                    put(
                        "note",
                        "After a `too-many-requests` the write queue waits one full window and " +
                            "retries ONCE, never in a loop. The wait equals the window on purpose: " +
                            "a shorter one re-enters the same window that just refused the call. " +
                            "Per-method spacings live in ddp/rate-limits.json; the window figure is " +
                            "restated here from design 02 for the same reason it is marked stated " +
                            "there — no constant in this codebase holds it.",
                    )
                },
            )
        }
    }

    // =======================================================================
    // ddp/subscriptions.json
    // =======================================================================

    private fun subscriptions(recording: Recording): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put(
            "purpose",
            "The two publications this client subscribes to, with the `sub` frame recorded off " +
                "the production client. `id` is minted per subscription and normalized here.",
        )
        put(
            "subscriptions",
            buildJsonArray {
                add(
                    subscription(
                        name = CHARACTER_LIST,
                        params = "none",
                        yields = "Every creature the user owns or may view. Drives the character " +
                            "selector; a DM sees the whole party.",
                        frame = recording.subscriptionFrames.getValue(CHARACTER_LIST),
                        // See CHARACTER_LIST's KDoc: the production constant is private, so the
                        // name is restated here while the frame around it is recorded.
                        nameGenerated = false,
                    ),
                )
                add(
                    subscription(
                        name = SINGLE_CHARACTER,
                        params = "[creatureId]",
                        yields = "One creature plus all of its creatureProperties and " +
                            "creatureVariables, live. Soft-removed properties are included — see " +
                            "domain/rules.json#softRemove.",
                        frame = recording.subscriptionFrames.getValue(SINGLE_CHARACTER),
                        nameGenerated = true,
                    ),
                )
            },
        )
        put(
            "lifecycle",
            "`added` (many) → `ready` → `changed`/`removed` as the sheet moves. A `ready` is " +
                "meaningful: Meteor sends the whole initial set before it, so a ready " +
                "subscription is a complete sheet and not a partial one. `unsub` stops it.",
        )
        put(
            "unsubFrame",
            buildJsonObject {
                put("msg", "unsub")
                put("id", SUB_ID_PLACEHOLDER)
            },
        )
    }

    private fun subscription(
        name: String,
        params: String,
        yields: String,
        frame: JsonObject,
        nameGenerated: Boolean,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("params", params)
        put("yields", yields)
        put("generated", true)
        put("nameGenerated", nameGenerated)
        put("frame", frame.withNormalizedId(SUB_ID_PLACEHOLDER))
    }

    // =======================================================================
    // ddp/rate-limits.json
    // =======================================================================

    /**
     * The server's rate-limit classes, as data.
     *
     * The **spacings** are read off production [WriteOp]s rather than restated, so this table
     * cannot drift from what the queue actually enforces. The server's calls-per-window
     * figures are from design 02's method catalog and are marked `generated: false` — no
     * constant in this codebase holds them, because the queue only ever needs the spacing.
     * `ContractExportTest` asserts `spacing × calls == window`, which is the arithmetic that
     * ties the stated half to the generated half.
     */
    private fun rateLimits(): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("windowMillis", RATE_WINDOW_MILLIS)
        put(
            "classes",
            buildJsonArray {
                add(
                    rateClass(
                        name = "damage",
                        callsPerWindow = 20,
                        spacingMillis = WriteOp.DAMAGE_SPACING_MILLIS,
                        methods = listOf("creatureProperties.damage"),
                        note = "The only fast lane the server grants. HP and slot taps are the " +
                            "one interaction a player repeats quickly.",
                    ),
                )
                add(
                    rateClass(
                        name = "default",
                        callsPerWindow = 5,
                        spacingMillis = WriteOp.SLOW_SPACING_MILLIS,
                        methods = listOf(
                            "creatureProperties.adjustQuantity",
                            "creatureProperties.flipToggle",
                            "creatureProperties.update",
                            "creatureProperties.equip",
                            "creatureProperties.insert",
                            "creatureProperties.softRemove",
                            "creatureProperties.restore",
                            "organize.organizeDoc",
                            "creature.methods.rest",
                        ),
                        note = "Everything that is not damage.",
                    ),
                )
            },
        )
        put(
            "clientRules",
            buildJsonArray {
                add(
                    JsonPrimitive(
                        "Enforce spacing PER METHOD NAME, not globally: a rest must not be slowed " +
                            "down by slot taps, and an undo that inverts into a different method " +
                            "waits in that method's own lane.",
                    ),
                )
                add(
                    JsonPrimitive(
                        "Coalesce rapid taps on the same property into one `increment` with the " +
                            "summed value, and drop a pair that cancels to zero entirely.",
                    ),
                )
                add(
                    JsonPrimitive(
                        "Re-derive a coalesced op's user-facing label from the MERGED sign: a spend " +
                            "then a larger restore is a restore, and filing it as a spend puts a lie " +
                            "on the undo stack.",
                    ),
                )
                add(
                    JsonPrimitive(
                        "The server answers an exceeded limit with a `too-many-requests` error. " +
                            "Back off and retry once after the window; do not retry blind.",
                    ),
                )
            },
        )
    }

    private const val RATE_WINDOW_MILLIS = 5_000L

    private fun rateClass(
        name: String,
        callsPerWindow: Int,
        spacingMillis: Long,
        methods: List<String>,
        note: String,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("callsPerWindow", callsPerWindow)
        put("callsPerWindowGenerated", false)
        put("minSpacingMillis", spacingMillis)
        put("minSpacingMillisGenerated", true)
        put("methods", JsonArray(methods.map { JsonPrimitive(it) }))
        put("note", note)
    }

    // =======================================================================
    // ddp/method-vectors.json
    // =======================================================================

    /**
     * One exported wire vector: a name, the production [WriteOp] behind it, and the reason it
     * is in the export.
     */
    class Vector(
        val name: String,
        val op: WriteOp,
        val note: String,
        /** True where this vector exists to pin a probe-established server quirk. */
        val quirk: String? = null,
    )

    /** The synthetic sheet the insert/move vectors resolve their parents and orders against. */
    private val inventorySheet: CreatureSheet by lazy {
        CreatureSheet.fromSnapshotJson(ContractFixtures.inventorySheetBody(), ContractFixtures.creatureId)
    }

    private val trackerSheet: CreatureSheet by lazy {
        CreatureSheet.fromSnapshotJson(ContractFixtures.trackerSheetBody(), ContractFixtures.creatureId)
    }

    /** The rows the write vectors are built from — produced by the engine, not written down. */
    private val trackerBoard: TrackerBoard by lazy { TrackerEngine.build(trackerSheet) }

    private val spellSlot: TrackedResource by lazy {
        trackerBoard.slots.first { it.propertyId == ContractFixtures.spellSlotL1Id }
    }

    private val hitPoints: TrackedResource by lazy {
        trackerBoard.hp ?: error("the tracker fixture must express hit points")
    }

    private val potion: TrackedResource by lazy {
        TrackerEngine.build(inventorySheet).allItems.first { it.propertyId == ContractFixtures.potionId }
    }

    /** Where a new item goes on this sheet — resolved by the production rule, not hardcoded. */
    private val insertTarget: InventoryEngine.InsertTarget by lazy {
        InventoryEngine.insertTarget(inventorySheet) ?: error("the inventory fixture must offer an insert target")
    }

    private val moveIntoBackpack: InventoryEngine.InsertTarget by lazy {
        InventoryEngine.moveTarget(inventorySheet, ContractFixtures.backpackId)
            ?: error("the inventory fixture must offer a container to move into")
    }

    private val tinderboxLocation: InventoryEngine.InsertTarget by lazy {
        InventoryEngine.currentLocation(inventorySheet, ContractFixtures.tinderboxId)
            ?: error("the inventory fixture must place the tinderbox somewhere")
    }

    val VECTORS: List<Vector> by lazy {
        listOf(
            // --- creatureProperties.damage -------------------------------------------
            Vector(
                name = "damage.spendSpellSlot",
                op = WriteOp.spend(spellSlot),
                note = "Spend one level-1 slot.",
                quirk = "DiceCloud stores consumption as DAMAGE (`value = total − damage`), so " +
                    "spending a charge is `increment +1` — the number goes UP as the row goes DOWN.",
            ),
            Vector(
                name = "damage.restoreSpellSlot",
                op = WriteOp.restore(spellSlot),
                note = "Un-spend one level-1 slot; the inverse of the vector above.",
            ),
            Vector(
                name = "damage.takeDamage",
                op = WriteOp.takeDamage(hitPoints, 7),
                note = "Take 7 damage. Identical call shape to spending a charge — only the " +
                    "user-facing label differs, which is why the label is chosen at the call site " +
                    "and never re-inferred from the sign.",
            ),
            Vector(
                name = "damage.heal",
                op = WriteOp.heal(hitPoints, 5),
                note = "Heal 5. The server clamps at full.",
            ),
            Vector(
                name = "damage.setRemaining",
                op = WriteOp.setValue(hitPoints, 20),
                note = "Set the row to 20 hit points.",
                quirk = "`operation: 'set'` takes the REMAINING value, not the damage. Design 03 " +
                    "originally said `value: total − desired`; the live probe says otherwise " +
                    "(`set 5` on a 20-point row produced `value: 5, damage: 15`). Following the " +
                    "old reading sets a row to its own complement, and 'heal to full' — where " +
                    "`total − desired == 0` — would drop the character to 0 HP.",
            ),

            // --- creatureProperties.adjustQuantity ------------------------------------
            Vector(
                name = "adjustQuantity.consumeItem",
                op = WriteOp.consumeItem(potion),
                note = "Drink one potion.",
                quirk = "`increment` is a CONSUMPTION amount here, exactly as on `damage`: `+1` " +
                    "removes one. Design 03's write table said `-1` for 'drink potion' and is " +
                    "wrong — a live probe moved a quantity 5 → 7 on two `-1` calls.",
            ),
            Vector(
                name = "adjustQuantity.addItem",
                op = WriteOp.adjust(potion, 2),
                note = "Acquire two more of the same item: a NEGATIVE increment.",
            ),
            Vector(
                name = "adjustQuantity.setQuantity",
                op = WriteOp.setValue(potion, 5),
                note = "Set the stack to exactly 5. As on `damage`, `set` takes the value the row " +
                    "should end up showing.",
            ),

            // --- creatureProperties.flipToggle ----------------------------------------
            Vector(
                name = "flipToggle.flip",
                op = WriteOp.flip(flippableToggle),
                note = "Flip a condition/buff toggle. Callers MUST check flippability first: the " +
                    "server refuses a computed toggle with `Can't flip a toggle that is computed`, " +
                    "and a chip that always errors is worse than one that does not respond. A " +
                    "toggle is manual iff its document carries `enabled` or `disabled`.",
            ),

            // --- creatureProperties.equip ---------------------------------------------
            Vector(
                name = "equip.equip",
                op = WriteOp.equip(ContractFixtures.halfPlateId, equipped = true, currentlyEquipped = false),
                note = "Put an item on.",
                quirk = "`equip` REPARENTS the property: equipping moves it under the " +
                    "`equipment`-tagged folder, unequipping under `carried`, and the original " +
                    "parent is never restored. That is DiceCloud's own web UI's behaviour. Group " +
                    "the inventory UI by STATE rather than by the folder tree and the reparenting " +
                    "is invisible; render the tree and every equip looks like a teleport.",
            ),
            Vector(
                name = "equip.unequip",
                op = WriteOp.equip(ContractFixtures.longswordId, equipped = false, currentlyEquipped = true),
                note = "Take an item off. The inverse of an equip is the opposite `equip` call and " +
                    "NOTHING ELSE — it returns the equipped state, never the folder.",
            ),

            // --- creatureProperties.insert --------------------------------------------
            Vector(
                name = "insert.catalogItem",
                op = WriteOp.insertItem(
                    spec = NewItemSpec.of(ItemCatalog.byId("torch") ?: error("catalog lost its torch")),
                    parentId = insertTarget.parentId,
                    order = insertTarget.order,
                    parentCollection = insertTarget.parentCollection,
                ),
                note = "Create an item from the built-in catalog. `parentRef` and `order` are " +
                    "resolved against the sheet at call time by the production insert rule, not " +
                    "remembered — `equip` moves items between the very folders it resolves.",
                quirk = "TWO probe-established requirements in one frame. `order` is MANDATORY " +
                    "inside `creatureProperty` (`400: Order is required` without it), and " +
                    "`description` must be an OBJECT `{text: \"…\"}` — a bare string is rejected " +
                    "with `400: Description must be of type Object`. The `{text, value, hash, " +
                    "inlineCalculations}` wrapper is the schema's INPUT type; the server fills the " +
                    "computed siblings in around `text`.",
            ),
            Vector(
                name = "insert.customItem",
                op = WriteOp.insertItem(
                    spec = NewItemSpec(
                        name = "Bag of Curious Sand",
                        quantity = 1,
                        weightLb = 2.0,
                        valueGp = 0.5,
                        description = "It hums faintly when the moon is up.",
                    ),
                    parentId = insertTarget.parentId,
                    order = insertTarget.order,
                    parentCollection = insertTarget.parentCollection,
                ),
                note = "The custom-form path. Fields the player did not state are OMITTED rather " +
                    "than zero-filled: a `weight: 0` item claims to weigh nothing, while an item " +
                    "with no weight field has simply not been weighed.",
            ),
            Vector(
                name = "insert.coin",
                op = WriteOp.insertItem(
                    spec = NewItemSpec.ofCoin(CoinKind.COPPER, 1),
                    parentId = insertTarget.parentId,
                    order = insertTarget.order,
                    parentCollection = insertTarget.parentCollection,
                ),
                note = "The wallet's first increment on a denomination the sheet does not carry. " +
                    "Currency has no first-class model: a coin is an ordinary item carrying a " +
                    "`platinum`/`gold`/`silver`/`copper` tag. This path states no description, " +
                    "which is why it survived the string-description bug that broke every custom " +
                    "add — a reminder that a green suite proves only what it exercises.",
            ),

            // --- creatureProperties.softRemove / restore -------------------------------
            Vector(
                name = "softRemove.item",
                op = WriteOp.removeItem(ContractFixtures.tinderboxId, targetName = "Tinderbox"),
                note = "Delete an item.",
                quirk = "There is NO hard delete on this API. `softRemove` sets `removed: true`; " +
                    "the document survives and KEEPS BEING DELIVERED to clients over both REST " +
                    "and the subscription. Everything that lists, sums or counts properties must " +
                    "filter it. The upside is that a delete is genuinely reversible.",
            ),
            Vector(
                name = "restore.item",
                op = WriteOp.removeItem(ContractFixtures.tinderboxId, targetName = "Tinderbox").inverse
                    ?: error("softRemove must invert into restore"),
                note = "Undo of the above — clears `removed`.",
                quirk = "The one op whose inverse is a DIFFERENT METHOD. Since rate limiting is " +
                    "keyed per method name, the undo waits in `restore`'s own lane rather than " +
                    "behind the `softRemove` that caused it.",
            ),

            // --- organize.organizeDoc --------------------------------------------------
            Vector(
                name = "organizeDoc.moveIntoContainer",
                op = WriteOp.moveItem(
                    propertyId = ContractFixtures.tinderboxId,
                    parentId = moveIntoBackpack.parentId,
                    order = moveIntoBackpack.order,
                    previousParentId = tinderboxLocation.parentId,
                    previousOrder = tinderboxLocation.order,
                    parentCollection = moveIntoBackpack.parentCollection,
                    previousParentCollection = tinderboxLocation.parentCollection,
                    targetName = "Tinderbox",
                ),
                note = "Move a property into another parent.",
                quirk = "`docRef`, `parentRef` and `order` — note that the item is named by a " +
                    "`{id, collection}` REF, not by a bare `_id`, which is the shape that differs " +
                    "from every other method here. Only UNEQUIPPED items may be moved: `equip` " +
                    "reparents on its own schedule, so an equipped item would have two writers of " +
                    "one field and the next equip tap would silently undo the player's move. The " +
                    "inverse must carry the OLD location — a destination says nothing about where " +
                    "a thing came from.",
            ),

            // --- creature.methods.rest -------------------------------------------------
            Vector(
                name = "rest.short",
                op = WriteOp.rest(ContractFixtures.creatureId, RestType.SHORT_REST),
                note = "Short rest. Addressed to the CREATURE, not to a property.",
            ),
            Vector(
                name = "rest.long",
                op = WriteOp.rest(ContractFixtures.creatureId, RestType.LONG_REST),
                note = "Long rest. NOT undoable — the server applies every reset and every " +
                    "trigger, and no inverse call could put the sheet back. Confirm before " +
                    "sending, rather than offering an undo that cannot work.",
            ),
        )
    }

    private val flippableToggle: ConditionToggle by lazy {
        trackerBoard.activeToggles.first { it.propertyId == ContractFixtures.rageToggleId }
    }

    /** Methods design 02 catalogs that this client deliberately never calls. */
    private val UNCALLED_METHODS: List<Triple<String, String, String>> = listOf(
        Triple(
            "creatureProperties.update",
            "{_id, path: [...], value}",
            "Rename and pin-side edits. REJECTS the paths `type`, `order`, `parent`, `ancestors` " +
                "and `damage` — which is why consumption goes through `damage` and reparenting " +
                "through `organizeDoc`. No wire vector: MageHand has no caller, so none could be " +
                "recorded rather than invented.",
        ),
        Triple(
            "creatures.insertCreature",
            "{name, gender?, alignment?, allowedLibraries…}",
            "Character creation. MageHand hands this to the server's own web UI in a WebView. " +
                "A live probe established that `startingLevel` is required — `{name}` alone is a " +
                "schema failure. No wire vector, for the same reason as above.",
        ),
        Triple(
            "creatures.update",
            "{_id, path, value}",
            "Creature-level fields. No caller, no vector.",
        ),
    )

    private fun methodVectors(recording: Recording): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put(
            "purpose",
            "One canonical `method` frame per catalogued call, recorded off the production " +
                "DdpClient driven by the production WriteOp factories. `frame.id` is the client's " +
                "own per-session method counter and carries no meaning beyond matching the reply.",
        )
        put(
            "vectors",
            buildJsonArray {
                for (vector in VECTORS) {
                    add(vectorDocument(vector, recording.methodFrames.getValue(vector.name)))
                }
            },
        )
        put(
            "documentedNotCalled",
            buildJsonArray {
                for ((name, params, note) in UNCALLED_METHODS) {
                    add(
                        buildJsonObject {
                            put("method", name)
                            put("params", params)
                            put("note", note)
                            put("generated", false)
                        },
                    )
                }
            },
        )
    }

    private fun vectorDocument(vector: Vector, frame: JsonObject): JsonObject = buildJsonObject {
        put("name", vector.name)
        put("method", vector.op.method)
        put("generated", true)
        put("frame", frame)
        put("minSpacingMillis", vector.op.minSpacingMillis)
        put("rateClass", if (vector.op.minSpacingMillis == WriteOp.DAMAGE_SPACING_MILLIS) "damage" else "default")
        put("coalesceKey", vector.op.coalesceKey?.let { JsonPrimitive(coalesceShape(it)) } ?: JsonNull)
        put("isBarrier", vector.op.isBarrier)
        put("undoable", vector.op.inverse != null)
        put(
            "inverseParams",
            vector.op.inverse?.let { inverse ->
                buildJsonObject {
                    put("method", inverse.method)
                    put("params", JsonArray(inverse.params))
                }
            } ?: JsonNull,
        )
        put("note", vector.note)
        vector.quirk?.let { put("quirk", it) }
    }

    /**
     * A coalesce key with its property id replaced by a placeholder.
     *
     * The key's *shape* — `"<class>:<propertyId>"` — is the contract; the id in it is a
     * fixture value that would only invite a consumer to match on it.
     */
    private fun coalesceShape(key: String): String = key.substringBefore(':') + ":<propertyId>"

    // =======================================================================
    // domain/rules.json
    // =======================================================================

    private fun rules(): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put(
            "purpose",
            "The domain rules a second client has to get right, with every set and every number " +
                "read off the production constants that implement them.",
        )

        put("discovery", discoveryRules())
        put("softRemove", softRemoveRule())
        put("equippable", equippableRule())
        put("wallet", walletRule())
        put("quantity", quantityRule())
        put("writeSemantics", writeSemanticsRule())
    }

    private fun discoveryRules(): JsonObject = buildJsonObject {
        put(
            "note",
            "Discovery runs over one creature's `creatureProperties`. Every rule but the toggle " +
                "rule skips `inactive: true` and `removed: true`.",
        )
        put(
            "spellSlots",
            buildJsonObject {
                put("match", "type == 'attribute' && attributeType == 'spellSlot'")
                put(
                    "exclusions",
                    buildJsonArray {
                        add(
                            JsonPrimitive(
                                "reset == null — THE death-save filter. 'Succeeded Saves' and " +
                                    "'Failed Saves' are stored as spell slots with no reset rule. " +
                                    "A client that misses this puts two phantom slot rows on every " +
                                    "tracker.",
                            ),
                        )
                        add(JsonPrimitive("total == 0 — slot levels the character cannot reach yet."))
                    },
                )
                put(
                    "resetValues",
                    JsonArray(ResetRule.entries.map { JsonPrimitive(it.wireValue) }),
                )
                put("level", "`spellSlotLevel`, falling back to a leading ordinal parsed from the name.")
            },
        )
        put(
            "resources",
            buildJsonObject {
                put("match", "type == 'attribute' && attributeType == 'resource'")
                put("include", "total > 0 || value > 0")
            },
        )
        put(
            "hitPoints",
            buildJsonObject {
                put("match", "type == 'attribute' && variableName == 'hitPoints'")
                put("tempMatch", "variableName == 'tempHitPoints'")
                put("note", "Found by `variableName`, NOT by `attributeType`.")
            },
        )
        put(
            "toggles",
            buildJsonObject {
                put("match", "type == 'toggle'")
                put(
                    "note",
                    "The one rule that does NOT skip `inactive`: for a toggle, off is the state " +
                        "being rendered, and dropping switched-off toggles makes them unreachable. " +
                        "Skipped instead when `removed`, `deactivatedByAncestor` or " +
                        "`deactivatedByToggle`.",
                )
                put(
                    "flippable",
                    "The document carries `enabled` or `disabled`. A toggle with neither is driven " +
                        "by its `condition` calculation and the server refuses to flip it. Design " +
                        "03's `showUI == true` rule matches nothing on any real sheet.",
                )
            },
        )
        put(
            "concentration",
            "An enabled toggle or buff whose name or tags mention 'concentration' drives the " +
                "banner; buffs are included because that is how DiceCloud models an ongoing spell.",
        )
        put(
            "remaining",
            "`value` when the server published it, else `total − damage`. Never compute over a " +
                "value the server already stated.",
        )
    }

    private fun softRemoveRule(): JsonObject = buildJsonObject {
        put("field", CreatureSheet.REMOVED)
        put(
            "rule",
            "Soft-removed properties are DELIVERED TO CLIENTS by both `GET /api/creature/:id` and " +
                "the `singleCharacter` subscription. Nothing about the transport removes them; " +
                "only a filter does. Every list, sum and count must drop `removed: true`.",
        )
        put(
            "consequence",
            "A server-computed rollup (a container's `carriedWeight`/`contentsWeight`) CANNOT be " +
                "removed-filtered — it is computed over the server's own subtree and no client can " +
                "subtract a soft-deleted item back out of it. So a grand carried total must be the " +
                "client's own sum end to end; mixing filtered sums with unfiltered rollups produces " +
                "a number that is neither, and it is the number a capacity bar gets drawn against.",
        )
    }

    private fun equippableRule(): JsonObject = buildJsonObject {
        put(
            "rule",
            "An item is equippable iff it is live (not removed, not inactive) AND " +
                "(`equipped == true` OR its `tags` ∪ `libraryTags` intersect the equippable tag " +
                "set). Tag matching is case-insensitive, trimmed, and WHOLE-TAG — never a " +
                "substring test on the word 'weapon', which would sweep in 'weapon proficiency' " +
                "and 'spellcasting focus'.",
        )
        put("weaponTags", JsonArray(InventoryEngine.WEAPON_TAGS.sorted().map { JsonPrimitive(it) }))
        put("armorTags", JsonArray(InventoryEngine.ARMOR_TAGS.sorted().map { JsonPrimitive(it) }))
        put("equippableTags", JsonArray(InventoryEngine.EQUIPPABLE_TAGS.sorted().map { JsonPrimitive(it) }))
        put(
            "dataDefect1",
            "The SRD's Half Plate carries `medium armor` and NOT the bare tag `armor`. This is why " +
                "the set is enumerated and must never be collapsed to the bare word: a rule keyed " +
                "on it refuses an equip control to a suit of armor.",
        )
        put(
            "dataDefect2",
            "The `equipped` disjunct is load-bearing. Hand-made items players have already equipped " +
                "carry no taxonomy at all, and without the disjunct equipping one would be a " +
                "one-way door — the control that put it on would vanish the moment it went on.",
        )
        put(
            "residual",
            "A known FALSE NEGATIVE, not a wrong answer: once such an untagged item is taken off it " +
                "matches neither half and loses its control. The remedy is a per-item user " +
                "override, NEVER a name heuristic — a heuristic that guesses 'knife' is wrong " +
                "about 'A Little Bag of Sand' on the same sheet.",
        )
        put(
            "groups",
            buildJsonObject {
                put(
                    "note",
                    "Carried subdivides into Weapons · Armor · Gear. Weapon tags take precedence " +
                        "over armor tags; everything else is gear, INCLUDING overridden items — an " +
                        "override buys the control, not a claim about what the thing is.",
                )
                put("values", JsonArray(EquipGroup.entries.map { JsonPrimitive(it.name) }))
            },
        )
    }

    private fun walletRule(): JsonObject = buildJsonObject {
        put(
            "note",
            "DiceCloud has NO currency model. Coins are ordinary `item` properties distinguished " +
                "only by a tag and adjusted through the same `adjustQuantity` method as a potion. " +
                "A denomination the sheet lacks still renders — at zero, with a null propertyId — " +
                "and the first increment on it CREATES the item.",
        )
        put(
            "coins",
            buildJsonArray {
                for (coin in CoinKind.entries) {
                    add(
                        buildJsonObject {
                            put("kind", coin.name)
                            put("tag", coin.tag)
                            put("abbreviation", coin.abbreviation)
                            put("valueGp", coin.valueGp)
                            put("weightLb", coin.weightLb)
                            put("itemNameOnCreate", coin.itemName)
                        },
                    )
                }
            },
        )
        put(
            "weightNote",
            "The per-coin weight is used ONLY when this client creates a coin the sheet did not " +
                "have. A coin the sheet already carries keeps whatever weight the sheet gave it, " +
                "including none — overwriting a player's number with a rulebook constant is not a " +
                "client's job.",
        )
        put("stacking", "Several items may carry the same coin tag; the row sums them and writes to the first.")
        put("exchange", "No exchange or make-change arithmetic. A gp total line is client-computed and displayed only.")
    }

    private fun quantityRule(): JsonObject = buildJsonObject {
        put(
            "rule",
            "An item with NO `quantity` field is ONE of the thing, in every engine that reads it. " +
                "DiceCloud omits the field on singletons.",
        )
        put(
            "why",
            "This was `?: 0` in one engine and `?: 1` in another, so one property produced two " +
                "different quantities depending on which tab was looking: a potion the inventory " +
                "listed as ×1 was a consumable the tracker showed as 0, with its minus greyed out. " +
                "The weight argument settles it — a sheet of unquantified gear would otherwise " +
                "weigh nothing.",
        )
        put("pinnedBy", "domain/discovery-vectors.json#inventory-discovery, where both boards are built over one input.")
    }

    private fun writeSemanticsRule(): JsonObject = buildJsonObject {
        put(
            "consumptionIsDamage",
            "`value = total − damage`. Spending a charge is `damage {increment, +1}`; restoring is " +
                "`{increment, -1}`. `adjustQuantity` takes a consumption amount too, so `+1` " +
                "removes one of an item.",
        )
        put(
            "setIsRemaining",
            "`operation: 'set'` on BOTH methods takes the value the row should end up showing, not " +
                "the damage.",
        )
        put("restNotUndoable", "`creature.methods.rest` applies every reset and trigger. Confirm before; never offer undo.")
        put(
            "operations",
            JsonArray(WriteOperation.entries.map { JsonPrimitive(it.wireValue) }),
        )
        put(
            "restTypes",
            JsonArray(RestType.entries.map { JsonPrimitive(it.wireValue) }),
        )
        put(
            "trackerKinds",
            JsonArray(TrackerKind.entries.map { JsonPrimitive(it.name) }),
        )
    }

    // =======================================================================
    // domain/discovery-vectors.json
    // =======================================================================

    private fun discoveryVectors(): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put(
            "purpose",
            "Input creature-property documents paired with the output the production engines " +
                "produced for them. The expected halves are RUN, never written down — a " +
                "hand-computed expectation is a second implementation of the rule.",
        )
        put(
            "vectors",
            buildJsonArray {
                add(
                    discoveryVector(
                        name = "tracker-discovery",
                        purpose = "Every tracker discovery rule and, more importantly, every " +
                            "exclusion. The excluded properties are present in the input and " +
                            "absent from the output — a vector carrying only the survivors would " +
                            "prove nothing about the filter.",
                        exercises = listOf(
                            "spellSlot discovery with levels",
                            "death-save exclusion: reset == null, as an omitted key AND as an explicit JSON null",
                            "total == 0 slot exclusion",
                            "removed: true exclusion (the document is in the input)",
                            "inactive: true exclusion",
                            "resource discovery, and the total == 0 && value == 0 exclusion",
                            "hitPoints / tempHitPoints by variableName",
                            "toggle flippability: enabled|disabled present vs a computed toggle",
                            "concentration banner from a toggle name",
                        ),
                        input = ContractFixtures.trackerSheetBody(),
                        expected = buildJsonObject {
                            put("trackerBoard", TrackerEngine.build(trackerSheet).toJson())
                        },
                    ),
                )
                add(
                    discoveryVector(
                        name = "inventory-discovery",
                        purpose = "The inventory board, plus the TRACKER board over the same " +
                            "input. Both engines on one sheet is what makes the missing-quantity " +
                            "agreement machine-checkable rather than a claim in prose.",
                        exercises = listOf(
                            "equippable rule: tag-set match, the Half Plate `medium armor` defect, " +
                                "the equipped-but-untagged rescue, and the unequipped-untagged residual",
                            "removed filtering: a 100 lb soft-removed item that must not reach any sum",
                            "wallet coin handling, including a denomination the sheet does not carry",
                            "container rollups are section-header data; the grand total is the client's own sum",
                            "missing quantity reads 1 in BOTH engines",
                            "carry capacity from the strength attribute",
                        ),
                        input = ContractFixtures.inventorySheetBody(),
                        expected = buildJsonObject {
                            put("inventoryBoard", InventoryEngine.build(inventorySheet).toJson())
                            put("trackerBoard", TrackerEngine.build(inventorySheet).toJson())
                            put(
                                "crossEngineQuantity",
                                buildJsonObject {
                                    put("propertyId", ContractFixtures.singletonId)
                                    put("note", "The property carries no `quantity` field at all.")
                                    put(
                                        "inventoryQuantity",
                                        InventoryEngine.build(inventorySheet).allItems
                                            .first { it.propertyId == ContractFixtures.singletonId }.quantity,
                                    )
                                    put(
                                        "trackerQuantity",
                                        TrackerEngine.build(inventorySheet).allItems
                                            .first { it.propertyId == ContractFixtures.singletonId }.value,
                                    )
                                },
                            )
                        },
                    ),
                )
                add(
                    softRemovedVector(),
                )
            },
        )
    }

    /**
     * A vector whose whole subject is that soft-removed documents arrive and must be dropped.
     *
     * Separate from the two big vectors because it states the fact as *counts*: the input
     * holds N properties, discovery returns fewer, and the difference is exactly the removed
     * ones. That is a claim a consumer's test can assert in one line.
     */
    private fun softRemovedVector(): JsonObject {
        val body = ContractFixtures.inventorySheetBody()
        val sheet = CreatureSheet.fromSnapshotJson(body, ContractFixtures.creatureId)
        val board = InventoryEngine.build(sheet)
        return buildJsonObject {
            put("name", "soft-removed-still-streams")
            put(
                "purpose",
                "The soft-delete fact, as arithmetic. The input is the inventory fixture; the " +
                    "numbers below are what the production sheet and engine report over it.",
            )
            put("inputRef", "inventory-discovery")
            put(
                "expected",
                buildJsonObject {
                    put("propertiesDelivered", sheet.propertyList.size)
                    put("propertiesLive", sheet.livePropertyList.size)
                    put("softRemovedDelivered", sheet.propertyList.size - sheet.livePropertyList.size)
                    put("removedPropertyIds", JsonArray(listOf(JsonPrimitive(ContractFixtures.removedItemId))))
                    put(
                        "removedItemAbsentFromBoard",
                        board.allItems.none { it.propertyId == ContractFixtures.removedItemId },
                    )
                    put("carriedWeightLb", board.carriedWeightLb)
                    put(
                        "carriedWeightNote",
                        "The soft-removed item weighs 100 lb. A client that skipped the filter " +
                            "would report a carried weight 100 higher than this number — which is " +
                            "the number the capacity bar is drawn against.",
                    )
                },
            )
        }
    }

    private fun discoveryVector(
        name: String,
        purpose: String,
        exercises: List<String>,
        input: JsonObject,
        expected: JsonObject,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("purpose", purpose)
        put("generated", true)
        put("exercises", JsonArray(exercises.map { JsonPrimitive(it) }))
        put("input", input)
        put("expected", expected)
    }

    // =======================================================================
    // Board serialization
    // =======================================================================
    //
    // The one place in this file that is hand-written mapping code, and it is deliberately
    // dumb: it copies fields off the model objects the engines returned. It computes nothing.
    // The alternative — marking the `:core:model` data classes @Serializable — would put a
    // serialization dependency into a module whose build file states, in as many words, that
    // it is dependency-free by design, and would make the wire names of a public contract a
    // side effect of Kotlin property names. Explicit is also stable: field order here is the
    // export's field order, and a renamed model property fails to compile rather than
    // silently renaming a key WebHand reads.

    private fun TrackerBoard.toJson(): JsonObject = buildJsonObject {
        put("hp", hp?.toJson() ?: JsonNull)
        put("tempHp", tempHp?.toJson() ?: JsonNull)
        put("slots", JsonArray(slots.map { it.toJson() }))
        put("resources", JsonArray(resources.map { it.toJson() }))
        put("allItems", JsonArray(allItems.map { it.toJson() }))
        put("pinnedItems", JsonArray(pinnedItems.map { it.toJson() }))
        put("activeToggles", JsonArray(activeToggles.map { it.toJson() }))
        put("defenses", JsonArray(defenses.map { it.toJson() }))
        put("rolls", JsonArray(rolls.map { it.toJson() }))
        put("concentratingOn", concentratingOn?.let { JsonPrimitive(it) } ?: JsonNull)
        put("isEmpty", isEmpty)
    }

    private fun TrackedResource.toJson(): JsonObject = buildJsonObject {
        put("propertyId", propertyId)
        put("kind", kind.name)
        put("name", name)
        put("value", value)
        put("total", total)
        put("reset", reset?.wireValue?.let { JsonPrimitive(it) } ?: JsonNull)
        put("spellSlotLevel", spellSlotLevel?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sortOrder", sortOrder)
        put("pinned", pinned)
    }

    private fun ConditionToggle.toJson(): JsonObject = buildJsonObject {
        put("propertyId", propertyId)
        put("name", name)
        put("enabled", enabled)
        put("flippable", flippable)
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        put("sortOrder", sortOrder)
        put("pinned", pinned)
        put("shownByDefault", shownByDefault)
    }

    private fun DamageDefense.toJson(): JsonObject = buildJsonObject {
        put("propertyId", propertyId)
        put("kind", kind.name)
        put("damageTypes", JsonArray(damageTypes.map { JsonPrimitive(it) }))
        put("name", name)
        put("sortOrder", sortOrder)
    }

    private fun RollModifier.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("modifier", modifier)
        put("advantage", advantage.name)
        put("sortOrder", sortOrder)
    }

    private fun InventoryBoard.toJson(): JsonObject = buildJsonObject {
        put("wallet", wallet.toJson())
        put("equipped", JsonArray(equipped.map { it.toJson() }))
        put("containers", JsonArray(containers.map { it.toJson() }))
        put("carried", JsonArray(carried.map { it.toJson() }))
        put("carriedWeightLb", carriedWeightLb)
        put("capacityLb", capacityLb?.let { JsonPrimitive(it) } ?: JsonNull)
        put("isOverCapacity", isOverCapacity)
        put("attunedCount", attunedCount)
        put("hasAttunementData", hasAttunementData)
        put("totalValueGp", totalValueGp)
        put("isEmpty", isEmpty)
    }

    private fun Wallet.toJson(): JsonObject = buildJsonObject {
        put(
            "rows",
            JsonArray(
                rows.map { row ->
                    buildJsonObject {
                        put("coin", row.coin.name)
                        put("abbreviation", row.coin.abbreviation)
                        put("quantity", row.quantity)
                        put("headQuantity", row.headQuantity)
                        put("propertyId", row.propertyId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("isAbsent", row.isAbsent)
                        put("weightLb", row.weightLb?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("valueGp", row.valueGp)
                        put("totalWeightLb", row.totalWeightLb)
                    }
                },
            ),
        )
        put("totalGp", totalGp)
        put("weightLb", weightLb)
        put("isEmpty", isEmpty)
    }

    private fun InventoryItem.toJson(): JsonObject = buildJsonObject {
        put("propertyId", propertyId)
        put("name", name)
        put("quantity", quantity)
        put("weightLb", weightLb?.let { JsonPrimitive(it) } ?: JsonNull)
        put("valueGp", valueGp?.let { JsonPrimitive(it) } ?: JsonNull)
        put("description", description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("equipped", equipped)
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        put("libraryTags", JsonArray(libraryTags.map { JsonPrimitive(it) }))
        put("requiresAttunement", requiresAttunement?.let { JsonPrimitive(it) } ?: JsonNull)
        put("attuned", attuned?.let { JsonPrimitive(it) } ?: JsonNull)
        put("containerId", containerId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sortOrder", sortOrder)
        put("isEquippable", isEquippable)
        put("equipGroup", equipGroup.name)
        put("totalWeightLb", totalWeightLb)
        put("totalValueGp", totalValueGp)
    }

    private fun InventoryContainer.toJson(): JsonObject = buildJsonObject {
        put("propertyId", propertyId)
        put("name", name)
        put("quantity", quantity)
        put("weightLb", weightLb?.let { JsonPrimitive(it) } ?: JsonNull)
        put("valueGp", valueGp?.let { JsonPrimitive(it) } ?: JsonNull)
        put("rollupWeightLb", rollupWeightLb?.let { JsonPrimitive(it) } ?: JsonNull)
        put("rollupValueGp", rollupValueGp?.let { JsonPrimitive(it) } ?: JsonNull)
        put("ownWeightLb", ownWeightLb)
        put("contentsWeightLb", contentsWeightLb)
        put("contentsValueGp", contentsValueGp)
        put("displayWeightLb", displayWeightLb)
        put("sortOrder", sortOrder)
        put("contents", JsonArray(contents.map { it.toJson() }))
    }

    // =======================================================================
    // item-catalog.json
    // =======================================================================

    private fun itemCatalog(): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put(
            "purpose",
            "The built-in add-item catalog, exported from the production list. Vendor it; do not " +
                "retype it.",
        )
        put("generated", true)
        put(
            "licence",
            "Every name, weight and price is from the System Reference Document 5.1 (Wizards of " +
                "the Coast), published under CC-BY-4.0 and previously the OGL 1.0a. Attribution " +
                "is this field. Nothing here is from any non-SRD source and nothing may be added " +
                "to it from one.",
        )
        put(
            "unitsNote",
            "`weightLb` is pounds per unit; `valueGp` is gold pieces per unit, with SRD costs in " +
                "sp/cp converted (1 sp = 0.1, 1 cp = 0.01). A weight the SRD leaves as an em dash " +
                "is 0.0 — the SRD's own claim that the item is too light to track, NOT a missing " +
                "measurement. Contrast an item on a sheet, where a null weight means unknown.",
        )
        put(
            "orderNote",
            "Alphabetical by name, because the user is scanning for a name they already know.",
        )
        put("tagAdventuringGear", ItemCatalog.TAG_ADVENTURING_GEAR)
        put(
            "categories",
            buildJsonArray {
                for (category in CatalogCategory.entries) {
                    add(
                        buildJsonObject {
                            put("name", category.name)
                            put("storedValue", category.storedValue)
                            put("equipGroup", category.equipGroup.name)
                        },
                    )
                }
            },
        )
        put("defaultCategory", CatalogCategory.DEFAULT.name)
        put(
            "categoryNote",
            "Every entry is GEAR today, and that is the honest answer rather than an oversight: " +
                "this is the SRD's Adventuring Gear table and nothing else. Ammunition is what a " +
                "weapon consumes rather than a weapon, and a flask of oil is a USE of an item, not " +
                "a class of one. The field is the capture point for the day the list gains a " +
                "longsword.",
        )
        put("entryCount", ItemCatalog.entries.size)
        put(
            "entries",
            buildJsonArray {
                for (entry in ItemCatalog.entries) {
                    add(
                        buildJsonObject {
                            put("id", entry.id)
                            put("name", entry.name)
                            put("weightLb", entry.weightLb)
                            put("valueGp", entry.valueGp)
                            put("tags", JsonArray(entry.tags.map { JsonPrimitive(it) }))
                            put("category", entry.category.name)
                            put("description", entry.description)
                            put("defaultQuantity", entry.defaultQuantity)
                        },
                    )
                }
            },
        )
    }

    // =======================================================================
    // manifest.json + README.md
    // =======================================================================

    private fun manifest(
        files: Map<String, String>,
        sourceCommit: String,
        generatedOn: String,
    ): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("producer", "magehand")
        put("producerTask", "./gradlew exportContract")
        put("sourceCommit", sourceCommit)
        put("generatedOn", generatedOn)
        put(
            "note",
            "This manifest is the source of truth for consumers: verify every file against its " +
                "sha256 before trusting it, and re-sync when `sourceCommit` moves. `sourceCommit` " +
                "names the commit the export was regenerated AT, which is by construction the " +
                "parent of the commit carrying these bytes.",
        )
        put(
            "targetService",
            buildJsonObject {
                put("vendor", "DiceCloud v2")
                put("upstream", "ThaumRystra/DiceCloud, `master` branch")
                put(
                    "branchWarning",
                    "GitHub's DEFAULT branch is `develop` and its method signatures DIFFER. " +
                        "Every fact in this export was verified against `master` and, where the " +
                        "quirk fields say so, against a live server.",
                )
                put("publicInstance", "https://$EXAMPLE_HOST")
            },
        )
        put(
            "files",
            buildJsonArray {
                for ((path, content) in files) {
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("bytes", bytes.size)
                            put("sha256", sha256(bytes))
                        },
                    )
                }
            },
        )
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readme(): String = buildString {
        appendLine("# MageHand contract export")
        appendLine()
        appendLine("Machine-generated. **Do not edit these files by hand** — run `./gradlew exportContract`")
        appendLine("in the MageHand repository and commit the result. A golden-file test in MageHand's")
        appendLine("JVM suite fails if the committed bytes and a fresh regeneration disagree, which is")
        appendLine("the guarantee that makes vendoring this directory safe.")
        appendLine()
        appendLine("## What is in here")
        appendLine()
        appendLine("| File | Contents |")
        appendLine("|---|---|")
        appendLine("| `manifest.json` | Schema version, source commit, and a sha256 per file. **Verify against this before trusting anything else.** |")
        appendLine("| `ddp/handshake.json` | The connect/login/heartbeat exchange, recorded off the production DDP client. |")
        appendLine("| `ddp/timings.json` | Every timeout, heartbeat interval, pong deadline, reconnect-backoff step and re-subscribe spacing the client runs on. |")
        appendLine("| `ddp/subscriptions.json` | The two publications used, with recorded `sub` frames. |")
        appendLine("| `ddp/rate-limits.json` | The server's rate-limit classes and the client rules that stay inside them. |")
        appendLine("| `ddp/method-vectors.json` | One canonical method frame per catalogued call, plus its inverse and its rate class. |")
        appendLine("| `domain/rules.json` | Discovery, soft-delete, equippability, wallet, quantity and write semantics. |")
        appendLine("| `domain/discovery-vectors.json` | Input sheets paired with the output the production engines produced. |")
        appendLine("| `item-catalog.json` | The built-in add-item catalog and its categories. |")
        appendLine()
        appendLine("## How to read it")
        appendLine()
        appendLine("Anything carrying `\"generated\": true` was **recorded or computed by running MageHand's")
        appendLine("own production code** — the DDP client framed those bytes, the discovery engines")
        appendLine("produced those outputs. Anything carrying `\"generated\": false` is a *stated* fact with")
        appendLine("no caller in MageHand to record (the methods it never invokes, and the server's")
        appendLine("calls-per-window figures, which no constant in the codebase holds).")
        appendLine()
        appendLine("Some documents split the flag where the two halves have different provenance. In")
        appendLine("`ddp/handshake.json` a step's `generated` describes the frame it **sent** and")
        appendLine("`repliesGenerated` describes `expectedReplies` separately — the `login` step's")
        appendLine("replies are the recorded frames that actually satisfied the client, while a step")
        appendLine("whose reply would only carry an invented session or ping id states a placeholder")
        appendLine("shape instead. `expectedReplies` is a LIST because method completion takes two")
        appendLine("frames, not one; read `completesOn` before assuming a step ends at the first.")
        appendLine()
        appendLine("## New in schema 3 — read this before you port the reconnect")
        appendLine()
        appendLine("`ddp/timings.json` now carries a **`resubscribe`** object: `staggerMillis` and")
        appendLine("`retryDelayMillis`, with `maxRetries: 1`. They are not tuning preferences and they")
        appendLine("are not optional for a well-behaved client.")
        appendLine()
        appendLine("The server's subscription rate limit is **50 per 10 seconds, global across every")
        appendLine("user** — one bucket for the whole table. The failure it produces is not \"my")
        appendLine("reconnect was slow\"; it is a `nosub` on somebody else's card. A client that replays")
        appendLine("all of its subscriptions in one tick after a router blip — which is exactly when")
        appendLine("every client reconnects at once — spends that shared bucket, and the client that")
        appendLine("loses the race is usually not the one that caused it. Space the replay, retry a")
        appendLine("refused replay **once**, and pay neither on a fresh subscription. See the notes on")
        appendLine("each field for the reasoning and the arithmetic.")
        appendLine()
        appendLine("Fields named `quirk` mark behaviour established by a **live probe** against a running")
        appendLine("server, usually after shipping the obvious reading and watching it fail. Those are the")
        appendLine("expensive lines in this directory. Read them before writing a client.")
        appendLine()
        appendLine("## Identifiers and privacy")
        appendLine()
        appendLine("Every sheet, id and token in here is **synthetic**. Ids are minted by MageHand's own")
        appendLine("Meteor id generator from a fixed seed, so they are drawn from the real")
        appendLine("`UNMISTAKABLE_CHARS` alphabet — real enough to exercise a validator, and provably not")
        appendLine("anybody's data. No live capture, no character sheet, no server hostname beyond the")
        appendLine("public DiceCloud instance, and no credential appears in this directory.")
    }
}
