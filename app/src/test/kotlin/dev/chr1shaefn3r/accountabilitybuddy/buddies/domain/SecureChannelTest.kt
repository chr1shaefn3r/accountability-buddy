package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto.TinkCryptography
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecureChannelTest {
    private val crypto = TinkCryptography()
    private val channel = SecureChannel(crypto)
    private val pairingId = PairingId.of(crypto.randomBytes(PairingId.SIZE))
    private val invitation = InvitationSecret(pairingId, crypto.randomBytes(Invitation.SECRET_SIZE))
    private val sender = crypto.newKeyPair()
    private val buddy = crypto.newKeyPair()
    private val senderKeys = SessionKeys.derive(crypto, sender.privateKey, buddy.publicKey, invitation, iAmSender = true)
    private val buddyKeys = SessionKeys.derive(crypto, buddy.privateKey, sender.publicKey, invitation, iAmSender = false)
    private val hit = BuddyMessage.LimitHit("Instagram", 60, 1_800_000_000)

    @Test
    fun `what one side seals the other side can open`() {
        val wire = channel.seal(pairingId, senderKeys, 7, hit)

        val envelope = assertNotNull(channel.read(wire))
        val opened = assertNotNull(channel.open(envelope, buddyKeys))

        assertEquals(pairingId, envelope.pairingId)
        assertEquals(7, opened.counter)
        assertEquals(hit, opened.message)
    }

    @Test
    fun `both directions work and use different keys`() {
        val wire = channel.seal(pairingId, buddyKeys, 0, BuddyMessage.PairingEnded)

        assertEquals(BuddyMessage.PairingEnded, channel.open(channel.read(wire)!!, senderKeys)?.message)
        assertNull(channel.open(channel.read(wire)!!, buddyKeys), "a phone cannot open its own messages")
    }

    @Test
    fun `every envelope has the same size whatever it contains`() {
        val short = channel.seal(pairingId, senderKeys, 0, BuddyMessage.PairingEnded)
        val long =
            channel.seal(
                pairingId,
                senderKeys,
                1,
                BuddyMessage.DayClosed("2026-11-12", List(10) { BuddyMessage.DayClosed.Entry("Limit $it", 60, 90) }),
            )
        val request = channel.seal(pairingId, buddyKeys, 0, BuddyMessage.PairingRequested("Sam", "token"), buddy.publicKey)

        assertEquals(short.size, long.size)
        assertEquals(short.size, request.size)
    }

    @Test
    fun `the content is not readable on the wire`() {
        val wire = channel.seal(pairingId, senderKeys, 0, hit)

        assertEquals(-1, wire.decodeToString(throwOnInvalidSequence = false).indexOf("Instagram"))
    }

    @Test
    fun `a pairing request carries the buddy's public key in the open`() {
        val wire = channel.seal(pairingId, buddyKeys, 0, BuddyMessage.PairingRequested("Sam", "token"), buddy.publicKey)

        assertContentEquals(buddy.publicKey, channel.read(wire)?.publicKey)
    }

    @Test
    fun `without the invitation secret the keys do not match`() {
        val guessed = InvitationSecret(pairingId, crypto.randomBytes(Invitation.SECRET_SIZE))
        val attackerKeys = SessionKeys.derive(crypto, buddy.privateKey, sender.publicKey, guessed, iAmSender = false)
        val wire = channel.seal(pairingId, attackerKeys, 0, BuddyMessage.PairingRequested("Mallory", "token"))

        assertNull(channel.open(channel.read(wire)!!, senderKeys))
    }

    @Test
    fun `a tampered envelope is rejected`() {
        val wire = channel.seal(pairingId, senderKeys, 0, hit)

        for (position in listOf(1, 20, 60, wire.size - 1)) {
            val tampered = wire.copyOf().also { it[position] = (it[position] + 1).toByte() }
            val envelope = channel.read(tampered)
            assertNull(envelope?.let { channel.open(it, buddyKeys) }, "changed byte $position")
        }
    }

    @Test
    fun `garbage is not an envelope`() {
        assertNull(channel.read(ByteArray(0)))
        assertNull(channel.read("hello".encodeToByteArray()))
        assertNull(channel.read(ByteArray(3000) { 9 }))
    }

    @Test
    fun `a message that does not fit is refused instead of truncated`() {
        val tooLong = BuddyMessage.NameChanged("x".repeat(SecureChannel.FRAME_SIZE))

        assertFailsWith<IllegalArgumentException> { channel.seal(pairingId, senderKeys, 0, tooLong) }
    }

    @Test
    fun `session keys survive being stored`() {
        val restored = SessionKeys.fromBytes(buddyKeys.toBytes())
        val wire = channel.seal(pairingId, senderKeys, 0, hit)

        assertEquals(hit, channel.open(channel.read(wire)!!, restored)?.message)
    }
}
