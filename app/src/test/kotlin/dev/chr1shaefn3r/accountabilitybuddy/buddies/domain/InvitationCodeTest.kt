package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto.TinkCryptography
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvitationCodeTest {
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val invitation = Invitation.create(TinkCryptography(), now)
    private val code = invitation.code(PushAddress("fcm-token:of_alex"), "Alex \"the\" Großé")

    @Test
    fun `a code survives the trip through a QR code`() {
        val scanned = assertNotNull(InvitationCode.parse(code.encode()))

        assertEquals(invitation.pairingId, scanned.pairingId)
        assertContentEquals(invitation.publicKey, scanned.publicKey)
        assertContentEquals(invitation.secret, scanned.secret)
        assertEquals(PushAddress("fcm-token:of_alex"), scanned.senderAddress)
        assertEquals("Alex \"the\" Großé", scanned.senderName)
        assertEquals(invitation.expiresAt, scanned.expiresAt)
    }

    @Test
    fun `the private key never appears in the code`() {
        val scanned = InvitationCode.parse(code.encode())!!

        assertFalse(scanned.publicKey.contentEquals(invitation.privateKey))
    }

    @Test
    fun `an invitation is valid for ten minutes`() {
        assertTrue(invitation.isValidAt(now + Duration.ofMinutes(9)))
        assertFalse(invitation.isValidAt(now + Duration.ofMinutes(10)))
        assertFalse(code.isValidAt(now + Duration.ofMinutes(10)))
    }

    @Test
    fun `every invitation is different`() {
        val other = Invitation.create(TinkCryptography(), now)

        assertFalse(other.pairingId == invitation.pairingId)
        assertFalse(other.secret.contentEquals(invitation.secret))
    }

    @Test
    fun `other QR codes are not invitations`() {
        assertNull(InvitationCode.parse("https://example.com"))
        assertNull(InvitationCode.parse(""))
        assertNull(InvitationCode.parse("accountability-buddy:1:{broken"))
        assertNull(InvitationCode.parse("""accountability-buddy:1:{"i":"xyz","k":"a","s":"b","a":"c","n":"d","x":1}"""))
        assertNull(InvitationCode.parse("accountability-buddy:2:" + code.encode().substringAfter(":1:")))
    }
}
