package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import android.content.Context
import androidx.core.content.edit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Session
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SessionId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SessionRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * Keeps the sessions of the day in the app's private preferences. They are few and gone
 * by midnight, so they do not need a table in the database.
 */
class StoredSessions(
    context: Context,
) : SessionRepository {
    private val store = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Row(
        val id: String,
        val lengthSeconds: Long,
        val startedAt: Long,
        val usedUpAt: Long? = null,
        val stoppedAt: Long? = null,
        val usedWhenStoppedSeconds: Long? = null,
        val dismissed: Boolean = false,
    )

    override suspend fun all(): List<Session> = read().map { it.toSession() }

    override suspend fun save(session: Session) =
        write(read().filterNot { it.id == session.id.value } + session.toRow())

    override suspend fun forgetStartedBefore(instant: Instant) =
        write(read().filterNot { it.startedAt < instant.toEpochMilli() })

    private fun read(): List<Row> =
        try {
            json.decodeFromString(ListSerializer(Row.serializer()), store.getString(SESSIONS, null) ?: "[]")
        } catch (_: SerializationException) {
            emptyList()
        }

    private fun write(rows: List<Row>) =
        store.edit { putString(SESSIONS, json.encodeToString(ListSerializer(Row.serializer()), rows)) }

    private fun Row.toSession() =
        Session(
            id = SessionId(id),
            length = Duration.ofSeconds(lengthSeconds),
            startedAt = Instant.ofEpochMilli(startedAt),
            usedUpAt = usedUpAt?.let(Instant::ofEpochMilli),
            stoppedAt = stoppedAt?.let(Instant::ofEpochMilli),
            usedWhenStopped = usedWhenStoppedSeconds?.let(Duration::ofSeconds),
            dismissed = dismissed,
        )

    private fun Session.toRow() =
        Row(
            id = id.value,
            lengthSeconds = length.seconds,
            startedAt = startedAt.toEpochMilli(),
            usedUpAt = usedUpAt?.toEpochMilli(),
            stoppedAt = stoppedAt?.toEpochMilli(),
            usedWhenStoppedSeconds = usedWhenStopped?.seconds,
            dismissed = dismissed,
        )

    private companion object {
        const val SESSIONS = "sessions"
    }
}
