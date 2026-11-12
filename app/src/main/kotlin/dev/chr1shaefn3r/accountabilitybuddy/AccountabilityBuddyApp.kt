package dev.chr1shaefn3r.accountabilitybuddy

import android.app.Application
import androidx.room.Room
import com.google.firebase.FirebaseApp
import dev.chr1shaefn3r.accountabilitybuddy.application.BuddyReporter
import dev.chr1shaefn3r.accountabilitybuddy.buddies.application.BuddiesPorts
import dev.chr1shaefn3r.accountabilitybuddy.buddies.application.BuddiesService
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.DeliverySchedule
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.History
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingRepository
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddressProvider
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushUnavailableException
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Relay
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background.SilenceWorker
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background.TrackingService
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background.WakeUpReceiver
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background.WorkManagerDeliverySchedule
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto.KeystoreVault
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto.TinkCryptography
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.notifications.Notifications
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.AppDatabase
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.Preferences
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.RoomHistory
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.RoomLimits
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.RoomOutbox
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.RoomPairings
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.RoomReceivedLog
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.Vault
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.push.FcmAddressProvider
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.push.HttpRelay
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.usage.PhoneUsage
import dev.chr1shaefn3r.accountabilitybuddy.limits.application.LimitsService
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The phone's clock. Reads the time zone afresh every time, so a zone change takes effect at once. */
private object PhoneClock : Clock() {
    override fun instant(): Instant = Instant.now()

    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)
}

/**
 * Creates the parts of the app and connects them. The only place where wiring happens.
 *
 * The adapters that need a real phone or the network can be replaced by a subclass,
 * which is how the user interface tests run the app.
 */
open class AccountabilityBuddyApp : Application() {
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    open val clock: Clock = PhoneClock

    val preferences by lazy { Preferences(this) }
    val notifications by lazy { Notifications(this) }
    val phone by lazy { PhoneUsage(this) }

    private val database by lazy { createDatabase() }
    private val roomLimits by lazy { RoomLimits(database.limits()) }
    private val roomPairings by lazy { RoomPairings(database.pairings(), createVault()) }

    protected open fun createVault(): Vault = KeystoreVault()

    protected open fun createRelay(): Relay =
        HttpRelay(
            HttpRelay.urlFor(
                FirebaseApp
                    .getInstance()
                    .options.projectId
                    .orEmpty(),
            ),
        )

    protected open fun createAddressProvider(): PushAddressProvider = FcmAddressProvider()

    /**
     * Sends right away while the app is running, and leaves a job behind that retries
     * when there is no network or the app is stopped first. Sending twice is harmless:
     * a letter leaves the outbox as soon as it is delivered.
     */
    protected open fun createDeliverySchedule(): DeliverySchedule {
        val retrying = WorkManagerDeliverySchedule(this)
        return DeliverySchedule {
            retrying.deliverSoon()
            background.launch { buddies.deliver() }
        }
    }

    protected open fun createDatabase(): AppDatabase =
        Room.databaseBuilder(this, AppDatabase::class.java, "accountability-buddy.db").build()

    /** Starts the periodic work that runs without the app being open. */
    protected open fun scheduleBackgroundWork() = SilenceWorker.schedule(this)

    val limitRepository: LimitRepository get() = roomLimits
    val pairings: PairingRepository get() = roomPairings
    val history: History by lazy { RoomHistory(database.messages()) }

    val buddies by lazy {
        BuddiesService(
            BuddiesPorts(
                pairings = roomPairings,
                invitations = roomPairings,
                outbox = RoomOutbox(database.messages()),
                deliverySchedule = createDeliverySchedule(),
                relay = createRelay(),
                received = RoomReceivedLog(database.messages()),
                history = history,
                notifier = notifications,
                profile = preferences,
                addressProvider = createAddressProvider(),
                crypto = TinkCryptography(),
                clock = clock,
            ),
        )
    }

    val limits by lazy {
        LimitsService(
            limits = roomLimits,
            hitLog = roomLimits,
            usageSource = phone,
            health = phone,
            memory = preferences,
            events = BuddyReporter(notifications, phone, buddies::tellBuddies),
            clock = clock,
        )
    }

    override fun onCreate() {
        super.onCreate()
        scheduleBackgroundWork()
        wakeUp()
        announceAddress()
    }

    /** Makes sure the midnight alarm is set and usage is being watched if there are limits. */
    fun wakeUp() {
        WakeUpReceiver.scheduleMidnight(this, clock)
        background.launch {
            if (limitRepository.all().isNotEmpty()) TrackingService.start(this@AccountabilityBuddyApp)
        }
    }

    /** Tells partners this phone's push address if it changed. Does nothing without a connection. */
    fun announceAddress() {
        if (!preferences.isOnboarded) return
        background.launch {
            try {
                buddies.refreshAddress()
            } catch (_: PushUnavailableException) {
                // Tried again on the next start.
            }
        }
    }
}
