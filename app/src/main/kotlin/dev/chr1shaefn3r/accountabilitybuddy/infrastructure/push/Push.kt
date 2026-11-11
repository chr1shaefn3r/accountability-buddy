package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.push

import com.google.firebase.FirebaseException
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddressProvider
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushUnavailableException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ExecutionException

/**
 * Registers this phone with Firebase Cloud Messaging. Its address is the Firebase
 * installation ID, which stays the same until the app is reinstalled or its data cleared.
 */
class FcmAddressProvider : PushAddressProvider {
    override suspend fun current(): PushAddress =
        try {
            FirebaseMessaging.getInstance().register().await()
            PushAddress(FirebaseInstallations.getInstance().id.await())
        } catch (e: IOException) {
            throw PushUnavailableException(e)
        } catch (e: ExecutionException) {
            throw PushUnavailableException(e)
        } catch (e: IllegalStateException) {
            throw PushUnavailableException(e)
        } catch (e: FirebaseException) {
            throw PushUnavailableException(e)
        }
}

/** Receives sealed messages from Firebase Cloud Messaging. */
class PushReceiver : FirebaseMessagingService() {
    private val app get() = application as AccountabilityBuddyApp

    override fun onMessageReceived(message: RemoteMessage) {
        val wire =
            try {
                Base64.getDecoder().decode(message.data[PAYLOAD_KEY] ?: return)
            } catch (_: IllegalArgumentException) {
                return
            }
        runBlocking { app.buddies.receive(wire) }
        app.wakeUp()
    }

    override fun onRegistered(installationId: String) {
        app.announceAddress()
    }

    private companion object {
        const val PAYLOAD_KEY = "p"
    }
}
