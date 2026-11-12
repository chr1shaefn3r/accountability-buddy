package dev.chr1shaefn3r.accountabilitybuddy

import androidx.room.Room
import dev.chr1shaefn3r.accountabilitybuddy.buddies.application.Network
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.DeliverySchedule
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationStore
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddressProvider
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Relay
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.AppDatabase
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.RoomPairings
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.Vault
import kotlinx.coroutines.launch

/**
 * The real app for user interface tests. Only what needs a real phone or the internet is
 * replaced: the Keystore, the push service, the relay and the job scheduler.
 */
class TestApp : AccountabilityBuddyApp() {
    val network = Network()
    val address = PushAddress("installation-id-of-this-phone")
    private val vault =
        object : Vault {
            override fun protect(secret: ByteArray): ByteArray = secret.reversedArray()

            override fun reveal(protectedSecret: ByteArray): ByteArray = protectedSecret.reversedArray()
        }
    private val testDatabase by lazy { Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).build() }

    /** The invitation the app currently shows as a QR code. */
    val invitations: InvitationStore by lazy { RoomPairings(testDatabase.pairings(), vault) }

    override fun createVault(): Vault = vault

    override fun createRelay(): Relay = Relay { to, wire -> network.send(to, wire) }

    override fun createAddressProvider(): PushAddressProvider = PushAddressProvider { address }

    override fun createDeliverySchedule(): DeliverySchedule =
        DeliverySchedule { background.launch { buddies.deliver() } }

    override fun createDatabase(): AppDatabase = testDatabase

    override fun scheduleBackgroundWork() = Unit

    override fun onCreate() {
        super.onCreate()
        network.connect(address) { buddies.receive(it) }
    }
}
