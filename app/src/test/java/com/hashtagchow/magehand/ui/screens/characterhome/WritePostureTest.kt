package com.hashtagchow.magehand.ui.screens.characterhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import java.io.File
import java.lang.reflect.Method

/**
 * The write posture, as a regression test rather than as a promise.
 *
 * ### What replaced what
 *
 * WP6 shipped `ReadOnlyPostureTest`, whose claim was "the UI cannot write **at all**". WP7
 * makes the tracker writable, so that test was *designed* to fail on this commit
 * (docs/verification/WP6.md §10) and this is its deliberate replacement. The claim narrows
 * but does not weaken:
 *
 * > **Every server write the app makes goes through the `WriteQueue`, and the UI layer
 * > cannot construct a DiceCloud method call at all.**
 *
 * That matters because everything that keeps the tracker safe lives *in* the queue —
 * LIVE-only refusal, the 250 ms / 1 s rate gates, coalescing, the optimistic overlay and
 * its rollback, and the undo stack (docs/design/02-ddp-and-api.md §Client rule;
 * docs/verification/WP4.md). A composable that could reach `DdpClient.call` would bypass
 * all six at once, and nothing else in the build would notice.
 *
 * ### Four assertions, in order of strength
 *
 * 1. **`:app`'s bytecode contains no DiceCloud mutation method name.** Decisive and
 *    comment-proof: a Kotlin string literal survives into the class file's constant pool,
 *    KDoc does not. If nobody in `:app` can *name* `creatureProperties.damage`, nobody in
 *    `:app` can send it — whatever they hold a reference to.
 * 2. **`:app`'s bytecode never references `core.data.write`.** The queue, the ops and the
 *    overlay are `:core:data`'s vocabulary. `:core:data` exports them (`api(project)`), so
 *    this is a discipline the classpath cannot enforce — hence the test.
 * 3. **[OpenCharacter]'s mutating surface is exactly the allow-list below.** A positive
 *    assertion, so widening the seam is a conscious edit here rather than a silent one
 *    there. Blind to overloads by construction — a name set cannot see arity or parameter
 *    types — which is what assertion 4 is for.
 * 4. **Every overload of that surface is exactly `allowedMutatorSignatures`.** A method
 *    already on the allow-list can still grow a second, differently-typed entry point — the
 *    `ExactQuantity` episode — and widen what `:app` can express with no edit assertion 3
 *    would catch. This is the same positive-assertion discipline, one erased signature
 *    finer.
 */
class WritePostureTest {

    /**
     * Every mutating method in docs/design/02-ddp-and-api.md's catalog, plus the two
     * insert methods the app must never touch.
     *
     * `login` and the read-only publications are deliberately absent: `:app` legitimately
     * names those (WP5's connection manager path), and a test that failed on them would be
     * asserting the wrong thing.
     */
    private val mutationMethods = listOf(
        "creatureProperties.damage",
        "creatureProperties.adjustQuantity",
        "creatureProperties.flipToggle",
        "creatureProperties.update",
        // FR-8: the real name, from the 2026-08-19 probe. The doc said `equipItem` and
        // nothing had ever called it; `creatureProperties.equip` is what the server answers
        // to, and docs/design/02-ddp-and-api.md is corrected in the same cycle. This string
        // is also a strict superset of the old one as a substring test, so the scan below
        // still catches `equipItem` if it ever reappears.
        "creatureProperties.equip",
        "creatureProperties.insert",
        // FR-9's three (docs/design/12-inventory-layout.md decisions 7 and 8). `softRemove`
        // and `restore` are the delete and its inverse — the only deletion this server offers,
        // and the reason a delete is undoable at all — and `organizeDoc` reparents a property.
        // All three are exactly the kind of call that must never be reachable from a
        // composable: two of them destroy or relocate a player's property with one method
        // invocation and no rate gate, no confirm and no undo entry of their own.
        "creatureProperties.softRemove",
        "creatureProperties.restore",
        "organize.organizeDoc",
        // FR-28's two (docs/design/17-use-action.md decision 7). These are the reason this list
        // exists at all, more than any entry above it: `doAction` runs a property's whole effect
        // tree — spending attributes and items, incrementing `usesUsed`, appending to the party
        // log and posting to a Discord webhook (probe U4) — with ONE method invocation, no
        // inverse of any kind, and no server-side refusal to fall back on (`doAction` returns
        // null for every outcome, probe U1). A composable that could name either string could
        // spend a player's resources and announce it to their table, past the confirm dialog,
        // past the single-flight latch and past the client-side prepared gate that is the only
        // gate there is.
        "creatureProperties.doAction",
        "creatureProperties.doCastSpell",
        "creature.methods.rest",
        "creatures.insertCreature",
        "creatures.update",
    )

    /** The intents `:app` is allowed to have. Adding one is an edit to this list. */
    private val allowedMutators = setOf(
        "spend",
        "restore",
        "changeHitPoints",
        "setHitPoints",
        "adjustItem",
        // FR-8's three, added deliberately per this list's own rule. Each is an *intent*, not
        // a method: `setEquipped` and `addItem` name what the player did, and `adjustCoins`
        // exists separately from `adjustItem` only because a wallet row may have no backing
        // property yet (docs/design/10-inventory.md decision 5). None of them lets `:app`
        // name a DDP method — the first two assertions above are what prove that.
        "setEquipped",
        "addItem",
        "adjustCoins",
        // FR-9's two, added deliberately per this list's own rule (12 decisions 7 and 8).
        // Both are *intents*: `removeItem` names what the player did and says nothing about
        // whether the storage soft-removes or deletes — which is exactly the difference
        // between the two implementations behind this interface — and `moveItem` takes an
        // `InventoryMoveTarget`, a container or the carried root, rather than a DDP
        // `parentRef`. Neither lets `:app` name a method; the two assertions above prove it.
        "removeItem",
        "moveItem",
        // FR-23's one, added deliberately per this list's own rule — and, unusually, **with
        // written authorization**: docs/design/15-polish-batch.md decision 21 overrides that
        // batch's own decision 9 ("no new intents") *for FR-23 only*, in as many words:
        // "`WritePostureTest`'s catalog is DELIBERATELY extended per its own 'adding one is an
        // edit to this list' rule."
        //
        // It could not have been composed from the entries above it, which is the bar this list
        // exists to enforce. `spend`/`restore` are increments against a row that counts *down*;
        // `setHitPoints` names one property. A death save is two properties written to two
        // absolutes together — decision 20's clear-on-heal is exactly that pair-shaped write —
        // and expressing it through the existing entries would have meant either two half-writes
        // the undo stack could separate, or teaching `spend` about a row whose semantics invert
        // every assumption it makes.
        //
        // It is still an *intent*, not a method: `:app` says "these are the marks now" and says
        // nothing about `creatureProperties.damage`, its `operation:'set'`, or the fact that one
        // implementation writes two DDP calls and the other writes two SQLite columns. The first
        // two assertions above are what prove that, and they are unchanged.
        //
        // FR-22 remains zero-new-intents, per decision 9 — its direct entry is an overload of
        // `adjustItem`/`adjustCoins`, which adds no name to this set.
        "setDeathSaves",
        // FR-28's two, added deliberately per this list's own rule and — like `setDeathSaves` —
        // **with written authorization**: docs/design/17-use-action.md decision 7 overrides 16
        // decision 7's "zero new writes" in as many words: "new OpenCharacter intents
        // `useAction(actionId)` and `castSpell(spellId, slotId?, ritual)` — WritePostureTest's
        // name AND signature catalogs deliberately extended".
        //
        // Neither could have been composed from the entries above it, and the bar is cleared more
        // clearly here than anywhere else in this list. Every other intent writes A NUMBER TO A
        // PROPERTY THIS APP CAN NAME — `spend` an increment, `setHitPoints` an absolute,
        // `adjustItem` a quantity. A use asks the server to run an effect tree whose contents this
        // app deliberately does not know, precisely so that it never becomes a second
        // implementation of DiceCloud's rules engine (10 decision 3's grand-total lesson, in write
        // form). There is no `spend` that could express it: working out what to spend is the thing
        // being delegated.
        //
        // They are still *intents*, not methods. `:app` says "use this action" and says nothing
        // about `creatureProperties.doAction`, its `targetIds` array, or the fact that one
        // implementation makes a DDP call and the other does nothing at all (a local character has
        // no effect trees — `LocalOpenCharacter.useAction`). The first two assertions above prove
        // that, and they are unchanged except for gaining two more strings to scan for.
        "useAction",
        "castSpell",
        "toggle",
        "rest",
        "undoLastWrite",
        // Local Room rows, not the server — kept in the list so the assertion can be
        // "these and nothing else" rather than "these plus whatever else looks harmless".
        "setOverride",
        "setOverrides",
        "clearOverride",
        "setAccentColor",
        "captureSnapshot",
        "close",
    )

    /**
     * The same allow-list as [allowedMutators], but as erased JVM signatures —
     * `name(paramSimpleNames)` — rather than bare names.
     *
     * **The `ExactQuantity` episode.** FR-22's direct entry gave `adjustItem` and
     * `adjustCoins` a second, `ExactQuantity`-typed overload without adding a name to
     * [allowedMutators] at all — the name-set assertion is blind to an overload, by
     * construction, because a name set cannot see arity or parameter types. That is a real
     * way to widen what `:app` can express through an intent already on the allow-list, with
     * no edit anywhere the reviewer would see it as a widening. This list makes an overload
     * cost the same deliberate edit a new intent already costs; [allowedMutators] stays
     * beside it because a bare name is still the faster read for "what can this app do at
     * all", and losing that would be a readability regression for no gain in coverage.
     */
    private val allowedMutatorSignatures = setOf(
        "spend(TrackedResource,int)",
        "restore(TrackedResource,int)",
        "changeHitPoints(int)",
        "setHitPoints(int)",
        "adjustItem(TrackedResource,int)",
        "adjustItem(TrackedResource,ExactQuantity)",
        "setEquipped(String,boolean,boolean,String)",
        "addItem(NewItemSpec)",
        "adjustCoins(WalletRow,int)",
        "adjustCoins(WalletRow,ExactQuantity)",
        "removeItem(String,String)",
        "moveItem(String,InventoryMoveTarget,String)",
        "setDeathSaves(int,int)",
        // FR-28. `useAction` takes the id alone; `castSpell` takes the id plus the player's two
        // choices — which slot to spend (17 decision 3's upcast picker) and whether to cast it as
        // a ritual. Neither takes a `WriteOp`, a `parentRef` or anything else from `:core:data`'s
        // vocabulary; the fifth assertion below re-checks that no `core.ddp` type leaked in.
        "useAction(String)",
        "castSpell(String,String,boolean)",
        "toggle(ConditionToggle)",
        "rest(RestKind)",
        "undoLastWrite(Continuation)",
        "setOverride(TrackerOverride,Continuation)",
        "setOverrides(List,Continuation)",
        "clearOverride(String,Continuation)",
        "setAccentColor(String,Continuation)",
        "captureSnapshot(Continuation)",
        "close(Continuation)",
    )

    private val writePackagePath = "com/hashtagchow/magehand/core/data/write"

    @Test
    fun `the app module cannot name a DiceCloud mutation`() {
        val classes = appClassFiles()
        val offenders = classes.mapNotNull { file ->
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            val hits = mutationMethods.filter { text.contains(it) }
            if (hits.isEmpty()) null else "${file.name}: $hits"
        }

        assertTrue(
            "a class in :app names a DiceCloud mutation method directly, which means it " +
                "could send one without the WriteQueue: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the app module holds no reference to the write package`() {
        val offenders = appClassFiles().mapNotNull { file ->
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            if (text.contains(writePackagePath)) file.name else null
        }

        assertTrue(
            "a class in :app references core.data.write — WriteOp/WriteQueue are " +
                ":core:data's vocabulary and the UI must go through OpenCharacter's " +
                "intents instead: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the UI's handle on a character exposes exactly the intents we decided on`() {
        val mutators = OpenCharacter::class.java.methods
            .filterNot { it.isSynthetic }
            .map { it.name }
            // Kotlin compiles a `val` to a JavaBeans getter — `get…` for most, `is…` for
            // Booleans — so this drops the read surface and leaves the intents. No member
            // of the allow-list is named that way, which is what makes the filter safe.
            .filterNot { it.matches(PROPERTY_GETTER) }
            .toSet()

        assertEquals(
            "OpenCharacter's mutating surface changed. If that is deliberate, add the new " +
                "intent to allowedMutators and say why in the work package's verification doc.",
            allowedMutators,
            mutators,
        )
    }

    @Test
    fun `the UI's handle on a character exposes exactly the overloads we decided on`() {
        val signatures = OpenCharacter::class.java.methods
            .filterNot { it.isSynthetic }
            .filterNot { it.name.matches(PROPERTY_GETTER) }
            .map { it.erasedSignature() }
            .toSet()

        assertEquals(
            "OpenCharacter's overload shape changed. A new or widened overload can extend " +
                "what :app can express through an existing intent without changing the " +
                "name-set above at all — see allowedMutatorSignatures' KDoc. If this is " +
                "deliberate, add the new signature there and say why.",
            allowedMutatorSignatures,
            signatures,
        )
    }

    @Test
    fun `no intent leaks a write type or a socket into the UI's signature`() {
        val leaked = OpenCharacter::class.java.methods.flatMap { method ->
            method.signatureTypes
                .filter { it.name.startsWith("com.hashtagchow.magehand.core.ddp") }
                .map { "${method.name}: ${it.name}" }
        }

        assertTrue(
            "OpenCharacter exposes a DDP type; the UI would then be able to call a method " +
                "itself: $leaked",
            leaked.isEmpty(),
        )
    }

    /**
     * `:app`'s own compiled classes.
     *
     * Gradle runs a module's unit tests with `user.dir` set to the module directory, so
     * `build/` is a fixed relative path from here — but *where under it* the compiler
     * writes is not stable. WP1's hard-coded `build/tmp/kotlin-classes/<variant>`
     * stopped existing when WP8 moved to AGP 9's built-in Kotlin, which writes to
     * `build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`.
     * The `size > 50` guard below is what caught that instead of letting the posture
     * test pass on an empty scan, so the fix is to *discover* the outputs rather than
     * name them — the next toolchain move then costs nothing.
     *
     * Missing output is a **failure**, not a skip: a posture test that quietly passes
     * because it scanned nothing is worse than no posture test.
     */
    private fun appClassFiles(): List<File> {
        val moduleDir = File(System.getProperty("user.dir") ?: ".")
        val buildDir = File(moduleDir, "build")
        val sep = File.separatorChar
        val packagePath = "${sep}com${sep}hashtagchow${sep}magehand$sep"

        val files = buildDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            // This module's own production classes only.
            .filter { it.path.contains(packagePath) }
            // The unit-test variant compiles into build/ as well, and test code is
            // allowed to name whatever it likes — this very file does.
            .filterNot { it.path.contains("UnitTest") || it.path.contains("androidTest") }
            // The Hilt/KSP generated component tree names every module it stitches together,
            // which is a graph of type names, not a call anyone can make.
            .filterNot { it.name.startsWith("Hilt_") || it.name.contains("_ComponentTreeDeps") }
            .toList()

        assertTrue(
            "found no compiled :app classes to scan under $buildDir — this test cannot " +
                "prove anything without them",
            files.size > 50,
        )
        return files
    }

    private val Method.signatureTypes: List<Class<*>>
        get() = listOf(returnType) + parameterTypes

    /** `name(paramSimpleNames)`, erased — the formula [allowedMutatorSignatures] catalogs. */
    private fun Method.erasedSignature(): String =
        "$name(${parameterTypes.joinToString(",") { it.simpleName }})"

    private companion object {
        val PROPERTY_GETTER = Regex("^(get|is)[A-Z].*")
    }
}
