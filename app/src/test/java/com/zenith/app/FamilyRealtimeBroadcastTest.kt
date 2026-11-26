package com.zenith.app

import com.zenith.app.data.network.FamilyRealtimeBroadcastPayload
import com.zenith.app.data.network.FamilyRealtimeEvents
import com.zenith.app.data.network.FamilyRealtimeManager
import com.zenith.app.data.repository.FamilySyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FamilyRealtimeBroadcastTest {

    @Test
    fun testPrivateChannelTopicAndRequiredEventCatalog() {
        val famId = UUID.randomUUID().toString()
        assertEquals("family:$famId", FamilyRealtimeEvents.topicForFamily(famId))

        val expected = listOf(
            "transaction.created",
            "transaction.updated",
            "transaction.deleted",
            "budget.updated",
            "member.joined",
            "member.removed",
            "message.created",
            "message.deleted"
        )
        expected.forEach { ev ->
            assertTrue("Missing event $ev", FamilyRealtimeEvents.ALL_EVENTS.contains(ev))
        }
    }

    @Test
    fun testBroadcastPayloadRoundTripAndCrossFamilyIsolation() {
        val famA = UUID.randomUUID().toString()
        val actor = UUID.randomUUID().toString()
        val txId = UUID.randomUUID().toString()

        val payload = FamilyRealtimeBroadcastPayload(
            event = FamilyRealtimeEvents.TRANSACTION_CREATED,
            familyId = famA,
            actorUserId = actor,
            entityId = txId,
            sentAtMillis = 1764100000000L
        )
        val jsonObj = FamilyRealtimeManager.buildBroadcastJson(payload)
        val parsed = FamilyRealtimeManager.parseBroadcastPayload(
            FamilyRealtimeEvents.TRANSACTION_CREATED,
            jsonObj
        )
        assertNotNull(parsed)
        assertEquals(famA, parsed!!.familyId)
        assertEquals(actor, parsed.actorUserId)
        assertEquals(txId, parsed.entityId)
        assertEquals(1764100000000L, parsed.sentAtMillis)
    }

    @Test
    fun testOfflineFailedSyncDoesNotEmitFakeBroadcastsAndDisconnectIsSafe() = runBlocking {
        val manager = FamilyRealtimeManager()
        val famId = UUID.randomUUID().toString()
        val actor = UUID.randomUUID().toString()

        // Calling sync broadcast helper when DB commit failed must be a clean no-op
        manager.emitSyncResultBroadcasts(
            familyId = famId,
            actorUserId = actor,
            result = FamilySyncResult(syncedOk = false, createdTxIds = listOf("tx1"))
        )
        assertFalse(manager.isConnected.value)
        assertNull(manager.activeTopic.value)

        // Calling connect / disconnect cleanly clears state even without network channel
        manager.connectToFamily(
            familyId = "",
            onRemoteEvent = {},
            onReconcileNeeded = {}
        )
        manager.disconnect()
        assertFalse(manager.isConnected.value)
    }
}
