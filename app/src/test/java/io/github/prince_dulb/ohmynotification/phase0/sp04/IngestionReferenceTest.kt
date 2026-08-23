package io.github.prince_dulb.ohmynotification.phase0.sp04

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionReferenceTest {
    @Test
    fun oneHundredCallbackBurstReconcilesWithoutSilentLoss() {
        val store = InMemoryAtomicIngestionStore()
        val dispatcher = BoundedSerialDispatcher(capacity = 128, store = store)
        val observations = buildList {
            repeat(25) { identityIndex ->
                val identity = identity(identityIndex)
                val evidence = IdentityEvidence(identityIndex, "tag-$identityIndex")
                add(post("${identityIndex}-post", identity, evidence, "v0"))
                add(post("${identityIndex}-replay", identity, evidence, "v0"))
                add(post("${identityIndex}-update", identity, evidence, "v1"))
                add(remove("${identityIndex}-remove", identity, evidence))
            }
        }

        observations.forEach { observation -> assertTrue(dispatcher.offer(observation)) }
        dispatcher.drainAll()

        assertEquals(100, dispatcher.terminals.size)
        assertEquals(100, dispatcher.terminals.map(DispatchTerminal::observationId).distinct().size)
        assertTrue(dispatcher.terminals.all { terminal ->
            terminal is DispatchTerminal.Persisted && terminal.result is CommitResult.Committed
        })
        assertEquals(100, dispatcher.peakPending)
        assertEquals(25, store.snapshot.items.size)
        assertTrue(store.snapshot.items.all { item ->
            item.lifecycleState == LifecycleState.REMOVED && item.contentRevision == 1
        })
        assertEquals(100, store.snapshot.events.size)
        assertEquals(100, store.snapshot.healthFacts.size)
        assertEquals((1L..100L).toList(), store.snapshot.events.map(StoredEvent::ingestSequence))
        assertEquals(0, store.snapshot.failures.size)
    }

    @Test
    fun undersizedQueueMakesEveryOverflowExplicit() {
        val store = InMemoryAtomicIngestionStore()
        val dispatcher = BoundedSerialDispatcher(capacity = 16, store = store)
        val observations = List(100) { index ->
            post("overflow-$index", identity(index), IdentityEvidence(index, null), "v0")
        }

        observations.forEach(dispatcher::offer)
        dispatcher.drainAll()

        assertEquals(100, dispatcher.terminals.size)
        assertEquals(16, dispatcher.terminals.count { it is DispatchTerminal.Persisted })
        assertEquals(84, dispatcher.terminals.count { it is DispatchTerminal.Backpressure })
        assertEquals(16, store.snapshot.items.size)
    }

    @Test
    fun exactReplayAndRecoveryWriteEventsWithoutDuplicatingOrRevisingItem() {
        val store = InMemoryAtomicIngestionStore()
        val identity = identity(1)
        val evidence = IdentityEvidence(1, "stable")
        val first = store.persist(post("first", identity, evidence, "same")) as CommitResult.Committed
        val replay = store.persist(post("replay", identity, evidence, "same")) as CommitResult.Committed
        val recovery = store.persist(
            post("recovery", identity, evidence, "same", CallbackKind.RECOVERY_SNAPSHOT),
        ) as CommitResult.Committed

        assertTrue(first.itemChanged)
        assertFalse(replay.itemChanged)
        assertFalse(recovery.itemChanged)
        assertEquals(EventKind.RECOVERY_SNAPSHOT, recovery.eventKind)
        assertEquals(1, store.snapshot.items.size)
        assertEquals(0, store.snapshot.items.single().contentRevision)
        assertEquals(3, store.snapshot.events.size)
    }

    @Test
    fun contentUpdateKeepsFirstPositionAndCreationSequence() {
        val store = InMemoryAtomicIngestionStore()
        val identity = identity(2)
        val evidence = IdentityEvidence(2, null)
        store.persist(post("create", identity, evidence, "before", observedAt = 10_000))
        val before = store.snapshot.items.single()
        store.persist(post("update", identity, evidence, "after", observedAt = 1_000))
        val after = store.snapshot.items.single()

        assertEquals(before.itemId, after.itemId)
        assertEquals(before.firstReceivedAtMillis, after.firstReceivedAtMillis)
        assertEquals(before.creationIngestSequence, after.creationIngestSequence)
        assertEquals(1_000, after.lastUpdatedAtMillis)
        assertEquals(1, after.contentRevision)
        assertEquals("after", after.content.body)
    }

    @Test
    fun persistentTargetChangeUpdatesItemWithoutIncreasingContentRevision() {
        val store = InMemoryAtomicIngestionStore()
        val identity = identity(12)
        val evidence = IdentityEvidence(12, null)
        store.persist(post("target-create", identity, evidence, "same", target = "target-a"))
        store.persist(post("target-update", identity, evidence, "same", target = "target-b"))

        val item = store.snapshot.items.single()
        assertEquals(0, item.contentRevision)
        assertEquals("target-b", item.persistentTarget)
        assertTrue(store.snapshot.events.last().itemChanged)
    }

    @Test
    fun sameTextNeverMergesDifferentSystemIdentityOrAndroidUser() {
        val store = InMemoryAtomicIngestionStore()
        val evidence = IdentityEvidence(9, "same")
        val first = identity(9)
        val otherKey = first.copy(systemKey = "system-other")
        val otherUser = first.copy(sourceUser = "user-work")

        store.persist(post("a", first, evidence, "same"))
        store.persist(post("b", otherKey, evidence, "same"))
        store.persist(post("c", otherUser, evidence, "same"))

        assertEquals(3, store.snapshot.items.size)
        assertEquals(3, store.snapshot.items.map(StoredItem::identity).distinct().size)
    }

    @Test
    fun removalRetainsArchiveAndRepostCreatesNextGeneration() {
        val store = InMemoryAtomicIngestionStore()
        val identity = identity(3)
        val evidence = IdentityEvidence(3, "reuse")
        store.persist(post("g0", identity, evidence, "old", observedAt = 100))
        store.persist(remove("remove-g0", identity, evidence, observedAt = 200))
        store.persist(post("g1", identity, evidence, "new", observedAt = 300))

        assertEquals(2, store.snapshot.items.size)
        val g0 = store.snapshot.items.single { it.generation == 0 }
        val g1 = store.snapshot.items.single { it.generation == 1 }
        assertEquals(LifecycleState.REMOVED, g0.lifecycleState)
        assertEquals(LifecycleState.ACTIVE, g1.lifecycleState)
        assertNotEquals(g0.itemId, g1.itemId)
        assertEquals(100, g0.firstReceivedAtMillis)
        assertEquals(300, g1.firstReceivedAtMillis)
    }

    @Test
    fun orphanRemovalAndIdentityConflictAreVisibleFailuresWithoutCorruptingItems() {
        val store = InMemoryAtomicIngestionStore()
        val identity = identity(4)
        val originalEvidence = IdentityEvidence(4, "original")
        val conflictEvidence = IdentityEvidence(5, "conflict")
        val orphan = store.persist(remove("orphan", identity, originalEvidence)) as CommitResult.Failed
        assertEquals(FailureCode.ORPHAN_REMOVAL, orphan.code)
        assertTrue(store.snapshot.items.isEmpty())

        store.persist(post("create", identity, originalEvidence, "v0"))
        val before = store.snapshot.items.single()
        val conflict = store.persist(post("conflict", identity, conflictEvidence, "v1")) as CommitResult.Failed

        assertEquals(FailureCode.IDENTITY_CONFLICT, conflict.code)
        assertEquals(before, store.snapshot.items.single())
        assertEquals(2, store.snapshot.failures.size)
    }

    @Test
    fun everyInjectedWriteFaultRollsBackSequenceItemEventAndHealthTogether() {
        FaultPoint.entries.forEach { faultPoint ->
            val store = InMemoryAtomicIngestionStore()
            val baselineIdentity = identity(10)
            store.persist(post("baseline", baselineIdentity, IdentityEvidence(10, null), "base"))
            val before = store.snapshot
            val result = store.persist(
                post("fault-$faultPoint", identity(11), IdentityEvidence(11, null), "new"),
                faultPoint = faultPoint,
            )

            assertEquals(CommitResult.Failed("fault-$faultPoint", FailureCode.PERSISTENCE_FAULT), result)
            assertEquals(before, store.snapshot)
        }
    }

    @Test
    fun ingestSequenceFollowsObservedOrderInsteadOfWallClock() {
        val store = InMemoryAtomicIngestionStore()
        val times = listOf(5_000L, 1_000L, 9_000L, -1L)
        times.forEachIndexed { index, time ->
            store.persist(
                post(
                    observationId = "clock-$index",
                    identity = identity(20 + index),
                    evidence = IdentityEvidence(20 + index, null),
                    body = "v$index",
                    observedAt = time,
                ),
            )
        }

        assertEquals((1L..4L).toList(), store.snapshot.events.map(StoredEvent::ingestSequence))
        assertEquals(times, store.snapshot.events.map(StoredEvent::observedAtMillis))
    }

    private fun identity(index: Int): BaseIdentity = BaseIdentity(
        sourceUser = "user-0",
        sourcePackage = "dev.omn.synthetic.source${index % 5}",
        systemKey = "system-$index",
    )

    private fun post(
        observationId: String,
        identity: BaseIdentity,
        evidence: IdentityEvidence,
        body: String,
        kind: CallbackKind = CallbackKind.POST_OR_UPDATE,
        observedAt: Long = observationCounter++,
        target: String? = "target-$body",
    ): Observation = Observation(
        observationId = observationId,
        callbackKind = kind,
        identity = identity,
        evidence = evidence,
        observedAtMillis = observedAt,
        observedElapsedMillis = observationCounter,
        content = NormalizedContent(
            title = "OMN_SYNTHETIC_TITLE",
            body = body,
        ),
        persistentTarget = target,
    )

    private fun remove(
        observationId: String,
        identity: BaseIdentity,
        evidence: IdentityEvidence,
        observedAt: Long = observationCounter++,
    ): Observation = Observation(
        observationId = observationId,
        callbackKind = CallbackKind.REMOVED,
        identity = identity,
        evidence = evidence,
        observedAtMillis = observedAt,
        observedElapsedMillis = observationCounter,
    )

    private var observationCounter = 1_000L
}
