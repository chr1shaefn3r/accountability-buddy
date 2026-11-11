package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTest {
    private val paired = Instant.parse("2026-11-12T10:00:00Z")
    private val sender =
        Pairing(
            id = PairingId("00112233445566778899aabbccddeeff"),
            partnerRole = PartnerRole.SENDER,
            partnerName = "Alex",
            partnerAddress = PushAddress("token"),
            state = PairingState.ACTIVE,
            lastSignOfLife = paired,
        )

    @Test
    fun `a sender is silent after seven days without a sign of life`() {
        assertFalse(sender.isSilentAt(paired + Duration.ofDays(7).minusSeconds(1)))
        assertTrue(sender.isSilentAt(paired + Duration.ofDays(7)))
    }

    @Test
    fun `a sign of life ends the silence`() {
        val later = paired + Duration.ofDays(8)

        assertFalse(sender.copy(silenceAlerted = true).signOfLife(later).isSilentAt(later))
    }

    @Test
    fun `silence is alerted only once`() {
        assertFalse(sender.copy(silenceAlerted = true).isSilentAt(paired + Duration.ofDays(30)))
    }

    @Test
    fun `only active senders can be silent`() {
        val muchLater = paired + Duration.ofDays(30)

        assertFalse(sender.copy(partnerRole = PartnerRole.BUDDY).isSilentAt(muchLater))
        assertFalse(sender.copy(state = PairingState.REQUESTED).isSilentAt(muchLater))
        assertFalse(sender.copy(state = PairingState.ENDED).isSilentAt(muchLater))
    }

    @Test
    fun `a nickname replaces the partner's own name on this phone`() {
        assertEquals("Alex", sender.displayName)
        assertEquals("Alex from work", sender.copy(nickname = "Alex from work").displayName)
    }

    @Test
    fun `a pairing id is sixteen bytes in hex`() {
        val id = PairingId.of(ByteArray(16) { (it * 17).toByte() })

        assertEquals("00112233445566778899aabbccddeeff", id.hex)
        assertEquals(id, PairingId.of(id.bytes()))
        assertFailsWith<IllegalArgumentException> { PairingId("abc") }
        assertFailsWith<IllegalArgumentException> { PairingId("Z".repeat(32)) }
    }
}
