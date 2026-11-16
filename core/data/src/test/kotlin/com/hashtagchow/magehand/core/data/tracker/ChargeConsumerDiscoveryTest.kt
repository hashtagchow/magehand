package com.hashtagchow.magehand.core.data.tracker

import com.hashtagchow.magehand.core.data.contract.ContractFixtures
import com.hashtagchow.magehand.core.model.ChargeConsumer
import com.hashtagchow.magehand.core.model.ChargeConsumerKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-59 stage 1 — which actions stand behind a charge (docs/design/22-charge-consumers.md R1/R2,
 * pinned per R8: **one test per arm of the `charge-consumers` vector**).
 *
 * The input is the contract fixture itself ([ContractFixtures.chargeConsumerSheetBody]), not a
 * private copy of it, so these tests and the vector WebHand ports against cannot describe two
 * different sheets. Each arm owns one resource row, and the assertion is that row's
 * `consumers` — so an arm that regressed shows up on its own row and nowhere else.
 */
class ChargeConsumerDiscoveryTest {

    private val board: TrackerBoard = TrackerEngine.build(
        CreatureSheet.fromSnapshotJson(ContractFixtures.chargeConsumerSheetBody(), ContractFixtures.creatureId),
    )

    private fun row(id: String): TrackedResource =
        board.resources.single { it.propertyId == id }

    private fun consumersOf(id: String): List<ChargeConsumer> = row(id).consumers

    @Test
    fun `a buff that is a direct descendant of the action makes it a consumer, through a folder`() {
        assertEquals(
            listOf(
                ChargeConsumer(
                    actionId = ContractFixtures.consumerDirectActionId,
                    name = "Direct Form",
                    kind = ChargeConsumerKind.ACTION,
                    applies = "An applied direct buff",
                ),
            ),
            consumersOf(ContractFixtures.consumerDirectResourceId),
        )
    }

    @Test
    fun `a buff held by a trigger carrying all three fields makes the tag-matched action a consumer`() {
        // The spell-typed trigger on the same tag is in the input and is NOT counted: had the
        // `actionPropertyType` comparison been skipped there would be two buffs and `applies`
        // would be null.
        assertEquals(
            listOf(
                ChargeConsumer(
                    actionId = ContractFixtures.consumerTriggerActionId,
                    name = "Trigger Form",
                    kind = ChargeConsumerKind.ACTION,
                    applies = "An applied form buff",
                ),
            ),
            consumersOf(ContractFixtures.consumerTriggerResourceId),
        )
    }

    @Test
    fun `a trigger lacking actionPropertyType never fires, so its buff makes nobody a consumer`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOf(ContractFixtures.consumerStrippedResourceId))
    }

    @Test
    fun `a trigger whose targetTags are not a subset of the action's tags does not count`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOf(ContractFixtures.consumerSubsetResourceId))
    }

    @Test
    fun `an inactive action is not a consumer`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOf(ContractFixtures.consumerInactiveResourceId))
    }

    @Test
    fun `a removed action is not a consumer`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOf(ContractFixtures.consumerRemovedResourceId))
    }

    @Test
    fun `a spell consumes a charge as a SPELL consumer`() {
        assertEquals(
            listOf(
                ChargeConsumer(
                    actionId = ContractFixtures.consumerSpellId,
                    name = "Companion Spell",
                    kind = ChargeConsumerKind.SPELL,
                    applies = "A companion buff",
                ),
            ),
            consumersOf(ContractFixtures.consumerSpellResourceId),
        )
    }

    @Test
    fun `two qualifying buffs keep the consumer and print no applies`() {
        val consumers = consumersOf(ContractFixtures.consumerTwofoldResourceId)
        assertEquals(listOf(ContractFixtures.consumerTwofoldActionId), consumers.map { it.actionId })
        assertEquals(null, consumers.single().applies)
    }

    @Test
    fun `a consumer whose only buff targets someone else is absent`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOf(ContractFixtures.consumerTargetedResourceId))
    }

    @Test
    fun `a cost of quantity zero is not a consumer`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOf(ContractFixtures.consumerZeroResourceId))
    }

    @Test
    fun `several consumers of one charge are ordered by their own order, not the input's`() {
        assertEquals(
            listOf(ContractFixtures.consumerSharedFirstActionId, ContractFixtures.consumerSharedSecondActionId),
            consumersOf(ContractFixtures.consumerSharedResourceId).map { it.actionId },
        )
    }

    // --- Addendum 2: one test per new arm --------------------------------------------------

    private fun consumersOfKey(key: String): List<ChargeConsumer> =
        consumersOf(ContractFixtures.fakeId("consumer-resource-$key"))

    private fun actionIdOf(key: String) = ContractFixtures.fakeId("consumer-action-$key")

    @Test
    fun `a trigger missing event is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("noevent"))
    }

    @Test
    fun `a trigger missing timing is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("notiming"))
    }

    @Test
    fun `a trigger whose timing is not one of the engine's options is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("oddtiming"))
    }

    @Test
    fun `an inactive trigger is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("inactivetrigger"))
    }

    @Test
    fun `a trigger with empty targetTags is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("emptytags"))
    }

    @Test
    fun `a trigger carrying a NOT extraTags arm is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("nottags"))
    }

    @Test
    fun `a trigger whose condition is false at rest is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("falsecondition"))
    }

    @Test
    fun `a trigger whose condition is true at rest is counted`() {
        assertEquals(listOf(actionIdOf("truecondition")), consumersOfKey("truecondition").map { it.actionId })
    }

    @Test
    fun `a trigger timed afterChildren is counted`() {
        assertEquals(listOf(actionIdOf("afterchildren")), consumersOfKey("afterchildren").map { it.actionId })
    }

    @Test
    fun `a buff behind a branch is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("branch"))
    }

    @Test
    fun `a buff under a child trigger of the action is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("childtrigger"))
    }

    @Test
    fun `a buff under a nested action is not counted`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("nested"))
    }

    @Test
    fun `a consumer naming the resource twice is one consumer with one buff, through two folders`() {
        assertEquals(
            listOf(
                ChargeConsumer(
                    actionId = actionIdOf("twice"),
                    name = "Twice Form",
                    kind = ChargeConsumerKind.ACTION,
                    applies = "A buff named once",
                ),
            ),
            consumersOfKey("twice"),
        )
    }

    @Test
    fun `two rows sharing a variableName get no consumers`() {
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("shared-name-a"))
        assertEquals(emptyList<ChargeConsumer>(), consumersOfKey("shared-name-b"))
    }

    @Test
    fun `a spell cast without spell slots is a SPELL consumer`() {
        val consumer = consumersOfKey("slotless").single()
        assertEquals(ContractFixtures.fakeId("consumer-spell-slotless"), consumer.actionId)
        assertEquals(ChargeConsumerKind.SPELL, consumer.kind)
    }

    @Test
    fun `the engine reads castWithoutSpellSlots, so the spell's Use needs no slot`() {
        val spells = ActionEngine.build(
            CreatureSheet.fromSnapshotJson(ContractFixtures.chargeConsumerSheetBody(), ContractFixtures.creatureId),
        ).spells
        val slotless = spells.single { it.propertyId == ContractFixtures.fakeId("consumer-spell-slotless") }
        val ordinary = spells.single { it.propertyId == ContractFixtures.consumerSpellId }
        assertTrue(slotless.castWithoutSpellSlots)
        assertEquals(false, checkNotNull(slotless.copy(prepared = true).useTarget).needsSlot)
        assertEquals(false, ordinary.castWithoutSpellSlots)
        assertEquals(true, checkNotNull(ordinary.copy(prepared = true).useTarget).needsSlot)
    }

    @Test
    fun `an unresolved quantity reads one`() {
        assertEquals(listOf(actionIdOf("unresolved")), consumersOfKey("unresolved").map { it.actionId })
    }

    @Test
    fun `only resource rows carry consumers`() {
        // R1: not slots, hit dice, HP, limited-use rows or items. The fixture carries a slot and a
        // hit die that a buff-applying action spends by `variableName`, and a limited-use row.
        val slot = board.slots.single { it.propertyId == ContractFixtures.fakeId("consumer-slot") }
        val hitDie = board.hitDice.single { it.propertyId == ContractFixtures.fakeId("consumer-hit-die") }
        val limited = board.limitedUses.single { it.propertyId == ContractFixtures.fakeId("consumer-limited-use") }
        listOf(slot, hitDie, limited).forEach {
            assertEquals("${it.kind} row must carry no consumers", emptyList<ChargeConsumer>(), it.consumers)
        }
        assertTrue((board.allCountableRows - board.resources.toSet()).all { it.consumers.isEmpty() })
        // And a sheet with no consumers at all keeps every resource row exactly as it was.
        val plain = TrackerEngine.build(
            CreatureSheet.fromSnapshotJson(ContractFixtures.trackerSheetBody(), ContractFixtures.creatureId),
        )
        assertTrue(plain.resources.isNotEmpty())
        assertTrue(plain.resources.all { it.consumers.isEmpty() })
    }

    @Test
    fun `a blank action name or a blank buff name is treated as absent`() {
        fun sheet(actionName: String, buffName: String) = CreatureSheet.fromSnapshotJson(
            ContractFixtures.snapshotBody(
                creature = ContractFixtures.creature(),
                properties = listOf(
                    ContractFixtures.consumerResource("r1", "Charges", "charges", order = 1),
                    ContractFixtures.consumerAction("a1", "action", actionName, consumes = "charges", order = 2),
                    ContractFixtures.buff(
                        id = "b1", name = buffName, order = 3, parentId = "a1",
                        parentCollection = "creatureProperties", inactive = true,
                    ),
                ),
            ),
            ContractFixtures.creatureId,
        )
        assertEquals(
            emptyList<ChargeConsumer>(),
            TrackerEngine.build(sheet(" ", "A buff")).resources.single().consumers,
        )
        assertEquals(null, TrackerEngine.build(sheet("Form", " ")).resources.single().consumers.single().applies)
    }

    /**
     * FR-59 stage 2 is delivered sheet-side as one action per known form: many consumers of one
     * charge, sharing a name prefix — and two may even share a whole name. They are listed in the
     * sheet's `order` (not the input's, not alphabetical), and nothing is merged by name.
     */
    @Test
    fun `many consumers sharing a name prefix are kept apart and listed in sheet order`() {
        val forms = listOf(
            Triple("f3", "Form: Second", 30),
            Triple("f1", "Form: Zeta", 10),
            Triple("f4", "Form: Second", 40),
            Triple("f2", "Form: Alpha", 20),
        )
        val sheet = CreatureSheet.fromSnapshotJson(
            ContractFixtures.snapshotBody(
                creature = ContractFixtures.creature(),
                properties = listOf(ContractFixtures.consumerResource("r1", "Form Charges", "formCharges", order = 1)) +
                    forms.flatMap { (id, name, order) ->
                        listOf(
                            ContractFixtures.consumerAction(id, "action", name, consumes = "formCharges", order = order),
                            ContractFixtures.buff(
                                id = "b-$id", name = "An applied form buff $id", order = order + 1, parentId = id,
                                parentCollection = "creatureProperties", inactive = true,
                            ),
                        )
                    },
            ),
            ContractFixtures.creatureId,
        )
        val consumers = TrackerEngine.build(sheet).resources.single().consumers
        assertEquals(listOf("f1", "f2", "f3", "f4"), consumers.map { it.actionId })
        assertEquals(listOf("Form: Zeta", "Form: Alpha", "Form: Second", "Form: Second"), consumers.map { it.name })
    }
}
