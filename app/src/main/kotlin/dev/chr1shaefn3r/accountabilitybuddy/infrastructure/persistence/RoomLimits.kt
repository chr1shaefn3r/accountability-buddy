package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.HitLog
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Stores limits and the hits of the last days in the local database. */
class RoomLimits(
    private val dao: LimitDao,
) : LimitRepository,
    HitLog {
    override fun observeAll(): Flow<List<Limit>> = dao.observeAll().map { rows -> rows.map { it.toLimit() } }

    override suspend fun all(): List<Limit> = dao.all().map { it.toLimit() }

    override suspend fun find(id: LimitId): Limit? = dao.find(id.value)?.toLimit()

    override suspend fun save(limit: Limit) =
        dao.save(LimitRow(limit.id.value, limit.name, limit.budget.seconds, limit.apps.joined()))

    override suspend fun delete(id: LimitId) = dao.delete(id.value)

    override suspend fun hitsOn(day: Day): List<LimitHit> =
        dao.hitsOn(day.date.toString()).map {
            LimitHit(
                day = Day(LocalDate.parse(it.day)),
                limitId = LimitId(it.limitId),
                limitName = it.limitName,
                budget = Duration.ofSeconds(it.budgetSeconds),
                apps = it.apps.split(),
                at = Instant.ofEpochMilli(it.hitAt),
            )
        }

    override suspend fun record(hit: LimitHit) =
        dao.record(
            HitRow(
                day = hit.day.date.toString(),
                limitId = hit.limitId.value,
                limitName = hit.limitName,
                budgetSeconds = hit.budget.seconds,
                apps = hit.apps.joined(),
                hitAt = hit.at.toEpochMilli(),
            ),
        )

    override suspend fun forgetBefore(day: Day) = dao.forgetHitsBefore(day.date.toString())

    private fun LimitRow.toLimit() = Limit(LimitId(id), name, Duration.ofSeconds(budgetSeconds), apps.split())

    private fun Set<AppId>.joined(): String = joinToString(SEPARATOR) { it.packageName }

    private fun String.split(): Set<AppId> = split(SEPARATOR).filter { it.isNotEmpty() }.map(::AppId).toSet()

    private companion object {
        const val SEPARATOR = "\n"
    }
}
