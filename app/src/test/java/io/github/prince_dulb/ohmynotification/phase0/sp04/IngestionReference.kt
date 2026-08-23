package io.github.prince_dulb.ohmynotification.phase0.sp04

internal data class BaseIdentity(
    val sourceUser: String,
    val sourcePackage: String,
    val systemKey: String,
)

internal data class IdentityEvidence(
    val notificationId: Int,
    val tag: String?,
)

internal enum class CallbackKind {
    POST_OR_UPDATE,
    RECOVERY_SNAPSHOT,
    REMOVED,
}

internal data class NormalizedContent(
    val title: String?,
    val body: String?,
)

internal data class Observation(
    val observationId: String,
    val callbackKind: CallbackKind,
    val identity: BaseIdentity,
    val evidence: IdentityEvidence,
    val observedAtMillis: Long,
    val observedElapsedMillis: Long,
    val content: NormalizedContent? = null,
    val persistentTarget: String? = null,
) {
    init {
        require(observationId.isNotBlank())
        require(callbackKind == CallbackKind.REMOVED || content != null)
        require(callbackKind != CallbackKind.REMOVED || content == null)
    }
}

internal enum class LifecycleState {
    ACTIVE,
    REMOVED,
}

internal data class StoredItem(
    val itemId: Long,
    val identity: BaseIdentity,
    val evidence: IdentityEvidence,
    val generation: Int,
    val firstReceivedAtMillis: Long,
    val lastUpdatedAtMillis: Long,
    val creationIngestSequence: Long,
    val contentRevision: Int,
    val content: NormalizedContent,
    val persistentTarget: String?,
    val lifecycleState: LifecycleState,
)

internal enum class EventKind {
    POSTED,
    UPDATED,
    RECOVERY_SNAPSHOT,
    REMOVED,
}

internal data class StoredEvent(
    val eventId: Long,
    val observationId: String,
    val itemId: Long,
    val ingestSequence: Long,
    val eventKind: EventKind,
    val observedAtMillis: Long,
    val itemChanged: Boolean,
)

internal data class HealthFact(
    val observationId: String,
    val ingestSequence: Long,
    val observedElapsedMillis: Long,
)

internal enum class FailureCode {
    INGESTION_BACKPRESSURE,
    IDENTITY_CONFLICT,
    ORPHAN_REMOVAL,
    PERSISTENCE_FAULT,
}

internal data class FailureFact(
    val failureId: Long,
    val observationId: String,
    val code: FailureCode,
    val observedAtMillis: Long,
)

internal data class StoreSnapshot(
    val items: List<StoredItem> = emptyList(),
    val events: List<StoredEvent> = emptyList(),
    val healthFacts: List<HealthFact> = emptyList(),
    val failures: List<FailureFact> = emptyList(),
    val nextItemId: Long = 1,
    val nextEventId: Long = 1,
    val nextFailureId: Long = 1,
    val nextIngestSequence: Long = 1,
)

internal enum class FaultPoint {
    AFTER_SEQUENCE_ALLOCATION,
    AFTER_ITEM_WRITE,
    AFTER_EVENT_WRITE,
    AFTER_HEALTH_WRITE,
}

internal sealed interface CommitResult {
    val observationId: String

    data class Committed(
        override val observationId: String,
        val itemId: Long,
        val ingestSequence: Long,
        val itemChanged: Boolean,
        val eventKind: EventKind,
    ) : CommitResult

    data class Failed(
        override val observationId: String,
        val code: FailureCode,
    ) : CommitResult
}

internal class InMemoryAtomicIngestionStore {
    var snapshot: StoreSnapshot = StoreSnapshot()
        private set

    fun persist(
        observation: Observation,
        faultPoint: FaultPoint? = null,
    ): CommitResult {
        val before = snapshot
        val active = before.items.singleOrNull { item ->
            item.identity == observation.identity && item.lifecycleState == LifecycleState.ACTIVE
        }
        if (active != null && active.evidence != observation.evidence) {
            return persistFailure(observation, FailureCode.IDENTITY_CONFLICT)
        }
        if (observation.callbackKind == CallbackKind.REMOVED && active == null) {
            return persistFailure(observation, FailureCode.ORPHAN_REMOVAL)
        }

        var working = before
        return try {
            val ingestSequence = working.nextIngestSequence
            working = working.copy(nextIngestSequence = ingestSequence + 1)
            failAt(faultPoint, FaultPoint.AFTER_SEQUENCE_ALLOCATION)

            val mutation = mutateItem(working, observation, active, ingestSequence)
            working = mutation.snapshot
            failAt(faultPoint, FaultPoint.AFTER_ITEM_WRITE)

            val event = StoredEvent(
                eventId = working.nextEventId,
                observationId = observation.observationId,
                itemId = mutation.item.itemId,
                ingestSequence = ingestSequence,
                eventKind = mutation.eventKind,
                observedAtMillis = observation.observedAtMillis,
                itemChanged = mutation.itemChanged,
            )
            working = working.copy(
                events = working.events + event,
                nextEventId = working.nextEventId + 1,
            )
            failAt(faultPoint, FaultPoint.AFTER_EVENT_WRITE)

            working = working.copy(
                healthFacts = working.healthFacts + HealthFact(
                    observationId = observation.observationId,
                    ingestSequence = ingestSequence,
                    observedElapsedMillis = observation.observedElapsedMillis,
                ),
            )
            failAt(faultPoint, FaultPoint.AFTER_HEALTH_WRITE)

            snapshot = working
            CommitResult.Committed(
                observationId = observation.observationId,
                itemId = mutation.item.itemId,
                ingestSequence = ingestSequence,
                itemChanged = mutation.itemChanged,
                eventKind = mutation.eventKind,
            )
        } catch (_: InjectedPersistenceFault) {
            check(snapshot == before)
            CommitResult.Failed(observation.observationId, FailureCode.PERSISTENCE_FAULT)
        }
    }

    private fun mutateItem(
        working: StoreSnapshot,
        observation: Observation,
        active: StoredItem?,
        ingestSequence: Long,
    ): ItemMutation {
        if (active == null) {
            check(observation.callbackKind != CallbackKind.REMOVED)
            val generation = working.items
                .asSequence()
                .filter { item -> item.identity == observation.identity }
                .maxOfOrNull(StoredItem::generation)
                ?.plus(1)
                ?: 0
            val item = StoredItem(
                itemId = working.nextItemId,
                identity = observation.identity,
                evidence = observation.evidence,
                generation = generation,
                firstReceivedAtMillis = observation.observedAtMillis,
                lastUpdatedAtMillis = observation.observedAtMillis,
                creationIngestSequence = ingestSequence,
                contentRevision = 0,
                content = requireNotNull(observation.content),
                persistentTarget = observation.persistentTarget,
                lifecycleState = LifecycleState.ACTIVE,
            )
            return ItemMutation(
                snapshot = working.copy(
                    items = working.items + item,
                    nextItemId = working.nextItemId + 1,
                ),
                item = item,
                itemChanged = true,
                eventKind = if (observation.callbackKind == CallbackKind.RECOVERY_SNAPSHOT) {
                    EventKind.RECOVERY_SNAPSHOT
                } else {
                    EventKind.POSTED
                },
            )
        }

        if (observation.callbackKind == CallbackKind.REMOVED) {
            val closed = active.copy(lifecycleState = LifecycleState.REMOVED)
            return ItemMutation(
                snapshot = working.replaceItem(closed),
                item = closed,
                itemChanged = true,
                eventKind = EventKind.REMOVED,
            )
        }

        val content = requireNotNull(observation.content)
        val contentChanged = content != active.content
        val targetChanged = observation.persistentTarget != active.persistentTarget
        val changed = contentChanged || targetChanged
        val updated = if (changed) {
            active.copy(
                lastUpdatedAtMillis = observation.observedAtMillis,
                contentRevision = active.contentRevision + if (contentChanged) 1 else 0,
                content = content,
                persistentTarget = observation.persistentTarget,
            )
        } else {
            active
        }
        return ItemMutation(
            snapshot = if (changed) working.replaceItem(updated) else working,
            item = updated,
            itemChanged = changed,
            eventKind = if (observation.callbackKind == CallbackKind.RECOVERY_SNAPSHOT) {
                EventKind.RECOVERY_SNAPSHOT
            } else {
                EventKind.UPDATED
            },
        )
    }

    private fun persistFailure(observation: Observation, code: FailureCode): CommitResult.Failed {
        val failure = FailureFact(
            failureId = snapshot.nextFailureId,
            observationId = observation.observationId,
            code = code,
            observedAtMillis = observation.observedAtMillis,
        )
        snapshot = snapshot.copy(
            failures = snapshot.failures + failure,
            nextFailureId = snapshot.nextFailureId + 1,
        )
        return CommitResult.Failed(observation.observationId, code)
    }

    private fun StoreSnapshot.replaceItem(replacement: StoredItem): StoreSnapshot = copy(
        items = items.map { item -> if (item.itemId == replacement.itemId) replacement else item },
    )

    private fun failAt(actual: FaultPoint?, expected: FaultPoint) {
        if (actual == expected) throw InjectedPersistenceFault()
    }

    private data class ItemMutation(
        val snapshot: StoreSnapshot,
        val item: StoredItem,
        val itemChanged: Boolean,
        val eventKind: EventKind,
    )

    private class InjectedPersistenceFault : RuntimeException()
}

internal sealed interface DispatchTerminal {
    val observationId: String

    data class Persisted(val result: CommitResult) : DispatchTerminal {
        override val observationId: String = result.observationId
    }

    data class Backpressure(override val observationId: String) : DispatchTerminal
}

internal class BoundedSerialDispatcher(
    private val capacity: Int,
    private val store: InMemoryAtomicIngestionStore,
) {
    private val pending = ArrayDeque<Observation>()
    private val knownObservationIds = hashSetOf<String>()
    private val mutableTerminals = arrayListOf<DispatchTerminal>()

    init {
        require(capacity > 0)
    }

    val terminals: List<DispatchTerminal> get() = mutableTerminals.toList()
    var peakPending: Int = 0
        private set

    fun offer(observation: Observation): Boolean {
        require(knownObservationIds.add(observation.observationId)) {
            "Duplicate observationId"
        }
        if (pending.size == capacity) {
            mutableTerminals += DispatchTerminal.Backpressure(observation.observationId)
            return false
        }
        pending.addLast(observation)
        peakPending = maxOf(peakPending, pending.size)
        return true
    }

    fun drainOne(): Boolean {
        val observation = pending.removeFirstOrNull() ?: return false
        mutableTerminals += DispatchTerminal.Persisted(store.persist(observation))
        return true
    }

    fun drainAll() {
        while (drainOne()) Unit
    }
}
