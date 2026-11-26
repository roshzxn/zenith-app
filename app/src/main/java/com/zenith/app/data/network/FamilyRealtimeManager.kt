package com.zenith.app.data.network

import android.util.Log
import com.zenith.app.data.repository.FamilySyncResult
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object FamilyRealtimeEvents {
    const val TRANSACTION_CREATED = "transaction.created"
    const val TRANSACTION_UPDATED = "transaction.updated"
    const val TRANSACTION_DELETED = "transaction.deleted"
    const val BUDGET_UPDATED = "budget.updated"
    const val MEMBER_JOINED = "member.joined"
    const val MEMBER_REMOVED = "member.removed"
    const val MESSAGE_CREATED = "message.created"
    const val MESSAGE_DELETED = "message.deleted"

    val ALL_EVENTS = listOf(
        TRANSACTION_CREATED,
        TRANSACTION_UPDATED,
        TRANSACTION_DELETED,
        BUDGET_UPDATED,
        MEMBER_JOINED,
        MEMBER_REMOVED,
        MESSAGE_CREATED,
        MESSAGE_DELETED
    )

    fun topicForFamily(familyId: String): String = "family:$familyId"
}

data class FamilyRealtimeBroadcastPayload(
    val event: String,
    val familyId: String,
    val actorUserId: String,
    val entityId: String? = null,
    val sentAtMillis: Long = System.currentTimeMillis()
)

/**
 * Coordinates Supabase Realtime private Broadcast channels (`family:<uuid>`).
 * PostgreSQL remains the authoritative source of truth: broadcasts only fire AFTER
 * successful DB commits and incoming events / reconnects trigger DB reconciliation.
 */
class FamilyRealtimeManager {

    private val tag = "FamilyRealtimeManager"
    // Serialize channel listener setup & awaitClose cleanup on 1 thread to prevent supabase-kt CallbackManagerImpl race
    @OptIn(ExperimentalCoroutinesApi::class)
    private val realtimeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val realtimeErrorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(tag, "Realtime worker suppressed exception safely: ${throwable.message}")
    }
    private val managerScope = CoroutineScope(SupervisorJob() + realtimeDispatcher + realtimeErrorHandler)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _activeTopic = MutableStateFlow<String?>(null)
    val activeTopic: StateFlow<String?> = _activeTopic.asStateFlow()

    @Volatile
    private var activeChannel: RealtimeChannel? = null

    @Volatile
    private var activeFamilyId: String? = null

    private var connectionJob: Job? = null
    private val channelMutex = Mutex()

    fun connectToFamily(
        familyId: String,
        onRemoteEvent: suspend (FamilyRealtimeBroadcastPayload) -> Unit,
        onReconcileNeeded: suspend (String) -> Unit
    ) {
        val hasSession = try {
            SupabaseClientConfig.isConfigured && SupabaseClientConfig.supabase.auth.currentSessionOrNull() != null
        } catch (_: Throwable) {
            false
        }
        if (!hasSession || familyId.isBlank()) {
            _isConnected.value = false
            return
        }
        val targetTopic = FamilyRealtimeEvents.topicForFamily(familyId)
        if (activeFamilyId == familyId && _activeTopic.value == targetTopic && connectionJob?.isActive == true) {
            return
        }

        val previousJob = connectionJob
        val previousChannel = activeChannel
        connectionJob = null
        activeChannel = null
        activeFamilyId = familyId
        _activeTopic.value = targetTopic
        _isConnected.value = false

        connectionJob = managerScope.launch {
            previousJob?.cancel()
            channelMutex.withLock {
                if (previousChannel != null && SupabaseClientConfig.isConfigured) {
                    withContext(NonCancellable) {
                        runCatching {
                            previousChannel.unsubscribe()
                            SupabaseClientConfig.supabase.realtime.removeChannel(previousChannel)
                        }
                    }
                }
            }

            var retryAttempt = 0
            val maxRetries = 3
            while (activeFamilyId == familyId) {
                var channel: RealtimeChannel? = null
                try {
                    channel = SupabaseClientConfig.supabase.channel(targetTopic) {
                        isPrivate = true
                        broadcast {
                            acknowledgeBroadcasts = true
                            receiveOwnBroadcasts = false
                        }
                    }
                    activeChannel = channel

                    // Register listeners before subscribing
                    for (eventName in FamilyRealtimeEvents.ALL_EVENTS) {
                        launch {
                            channel.broadcastFlow<JsonObject>(event = eventName).collect { raw ->
                                val parsed = parseBroadcastPayload(eventName, raw)
                                if (parsed != null && parsed.familyId == familyId) {
                                    onRemoteEvent(parsed)
                                }
                            }
                        }
                    }

                    // Monitor channel subscription & reconnect recovery
                    launch {
                        var wasEverSubscribed = false
                        var lastStatus: RealtimeChannel.Status? = null
                        channel.status.collect { status ->
                            val connectedNow = status == RealtimeChannel.Status.SUBSCRIBED
                            _isConnected.value = connectedNow
                            if (connectedNow) {
                                val reconnected = wasEverSubscribed && lastStatus != RealtimeChannel.Status.SUBSCRIBED
                                wasEverSubscribed = true
                                if (reconnected) {
                                    onReconcileNeeded(familyId)
                                }
                            }
                            lastStatus = status
                        }
                    }

                    channel.subscribe(blockUntilSubscribed = true)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(tag, "Realtime subscribe deferred/offline for $targetTopic (attempt ${retryAttempt + 1}): ${e.message}")
                    _isConnected.value = false
                    channel?.let { failedCh ->
                        withContext(NonCancellable) {
                            runCatching {
                                failedCh.unsubscribe()
                                SupabaseClientConfig.supabase.realtime.removeChannel(failedCh)
                            }
                        }
                    }
                    if (activeChannel === channel) activeChannel = null
                    retryAttempt++
                    if (retryAttempt > maxRetries || activeFamilyId != familyId) {
                        break
                    }
                    delay(2000L * retryAttempt)
                }
            }
        }
    }

    suspend fun emitCommittedEvent(payload: FamilyRealtimeBroadcastPayload): Boolean {
        if (!SupabaseClientConfig.isConfigured || payload.familyId.isBlank()) return false
        val ch = activeChannel ?: return false
        if (activeFamilyId != payload.familyId) return false
        return try {
            ch.broadcast(
                event = payload.event,
                message = buildBroadcastJson(payload)
            )
            true
        } catch (e: Exception) {
            Log.w(tag, "Broadcast send skipped (${payload.event}): ${e.message}")
            false
        }
    }

    suspend fun emitSyncResultBroadcasts(
        familyId: String,
        actorUserId: String,
        result: FamilySyncResult
    ) {
        if (!result.syncedOk) return
        if (result.createdTxIds.isNotEmpty()) {
            emitCommittedEvent(
                FamilyRealtimeBroadcastPayload(
                    event = FamilyRealtimeEvents.TRANSACTION_CREATED,
                    familyId = familyId,
                    actorUserId = actorUserId,
                    entityId = result.createdTxIds.last()
                )
            )
        }
        if (result.updatedTxIds.isNotEmpty()) {
            emitCommittedEvent(
                FamilyRealtimeBroadcastPayload(
                    event = FamilyRealtimeEvents.TRANSACTION_UPDATED,
                    familyId = familyId,
                    actorUserId = actorUserId,
                    entityId = result.updatedTxIds.last()
                )
            )
        }
        if (result.deletedTxIds.isNotEmpty()) {
            emitCommittedEvent(
                FamilyRealtimeBroadcastPayload(
                    event = FamilyRealtimeEvents.TRANSACTION_DELETED,
                    familyId = familyId,
                    actorUserId = actorUserId,
                    entityId = result.deletedTxIds.last()
                )
            )
        }
        if (result.changedBudget) {
            emitCommittedEvent(
                FamilyRealtimeBroadcastPayload(
                    event = FamilyRealtimeEvents.BUDGET_UPDATED,
                    familyId = familyId,
                    actorUserId = actorUserId
                )
            )
        }
        if (result.createdMessageIds.isNotEmpty()) {
            emitCommittedEvent(
                FamilyRealtimeBroadcastPayload(
                    event = FamilyRealtimeEvents.MESSAGE_CREATED,
                    familyId = familyId,
                    actorUserId = actorUserId,
                    entityId = result.createdMessageIds.last()
                )
            )
        }
        if (result.deletedMessageIds.isNotEmpty()) {
            emitCommittedEvent(
                FamilyRealtimeBroadcastPayload(
                    event = FamilyRealtimeEvents.MESSAGE_DELETED,
                    familyId = familyId,
                    actorUserId = actorUserId,
                    entityId = result.deletedMessageIds.last()
                )
            )
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        val prevChannel = activeChannel
        activeChannel = null
        activeFamilyId = null
        _activeTopic.value = null
        _isConnected.value = false
        if (prevChannel != null && SupabaseClientConfig.isConfigured) {
            managerScope.launch {
                channelMutex.withLock {
                    withContext(NonCancellable) {
                        runCatching {
                            prevChannel.unsubscribe()
                            SupabaseClientConfig.supabase.realtime.removeChannel(prevChannel)
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun buildBroadcastJson(payload: FamilyRealtimeBroadcastPayload): JsonObject = buildJsonObject {
            put("event", payload.event)
            put("family_id", payload.familyId)
            put("actor_user_id", payload.actorUserId)
            if (!payload.entityId.isNullOrBlank()) {
                put("entity_id", payload.entityId)
            }
            put("sent_at", payload.sentAtMillis)
        }

        fun parseBroadcastPayload(
            event: String,
            obj: JsonObject
        ): FamilyRealtimeBroadcastPayload? {
            val famId = (obj["family_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val actor = (obj["actor_user_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val entityId = obj["entity_id"]?.takeIf { it !is JsonNull }?.let {
                (it as? JsonPrimitive)?.contentOrNull
            }
            val sentAt = (obj["sent_at"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
            return FamilyRealtimeBroadcastPayload(
                event = event,
                familyId = famId,
                actorUserId = actor,
                entityId = entityId,
                sentAtMillis = sentAt
            )
        }
    }
}
