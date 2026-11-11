package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.push

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Relay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Hands sealed messages to the relay function over HTTPS. */
class HttpRelay(private val url: URL) : Relay {
    override suspend fun send(to: PushAddress, wire: ByteArray): Relay.Outcome =
        withContext(Dispatchers.IO) {
            val body =
                buildJsonObject {
                    put("to", to.id)
                    put("payload", Base64.getEncoder().encodeToString(wire))
                }.toString().encodeToByteArray()
            try {
                outcomeOf(post(body))
            } catch (_: IOException) {
                Relay.Outcome.TRY_LATER
            }
        }

    private fun post(body: ByteArray): Int {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 15_000
        private const val GONE = 410
        private val SUCCESS = 200..299

        fun outcomeOf(status: Int): Relay.Outcome =
            when (status) {
                in SUCCESS -> Relay.Outcome.DELIVERED
                GONE -> Relay.Outcome.ADDRESS_GONE
                else -> Relay.Outcome.TRY_LATER
            }

        /** The relay function lives in the same Firebase project as the push messages. */
        fun urlFor(firebaseProjectId: String): URL = URL("https://europe-west1-$firebaseProjectId.cloudfunctions.net/relay")
    }
}
