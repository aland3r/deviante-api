package com.deviante.repository

import com.deviante.db.ActivitiesTable
import com.deviante.db.OperationsTable
import com.deviante.db.ProcessActivitiesTable
import com.deviante.model.ActivityRecord
import com.deviante.model.OperationRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Bulk mapping (UC5 Resolve Mapping).
 *
 * The Manager names every activity first and confirms the whole set in one
 * action, so this resolves the batch inside a single transaction: a partial
 * mapping would leave the process graph half-built with no way to tell which
 * half the Manager actually approved.
 *
 * Two operations may resolve to the **same** activity — that convergence is
 * the point of UC3 (different outsourced logs spelling one physical step
 * differently), so activities are matched by name, case-insensitively, and
 * reused rather than duplicated.
 */
class MappingRepository {

    data class Resolution(
        val operationId: UUID,
        val activityName: String,
        val activityDescription: String,
    )

    data class Result(
        val operations: List<OperationRecord>,
        val activities: List<ActivityRecord>,
    )

    fun resolveAll(processId: UUID, resolutions: List<Resolution>): Result = transaction {
        val now = OffsetDateTime.now()
        val activitiesByKey = mutableMapOf<String, ActivityRecord>()

        for (resolution in resolutions) {
            val name = resolution.activityName.trim()
            val key = name.lowercase()
            if (activitiesByKey.containsKey(key)) continue

            activitiesByKey[key] = findActivityByName(name)
                ?: createActivity(name, resolution.activityDescription.trim(), now)
        }

        // The catalog is global; `process_activities` is what makes an
        // activity part of *this* process (many-to-many, owner's correction).
        for (activity in activitiesByKey.values) {
            linkIfAbsent(processId, activity.id, now)
        }

        val mapped = resolutions.mapNotNull { resolution ->
            val activity = activitiesByKey[resolution.activityName.trim().lowercase()]
                ?: return@mapNotNull null

            val updated = OperationsTable.update({ OperationsTable.id eq resolution.operationId }) {
                it[OperationsTable.activityId] = activity.id
                it[OperationsTable.mappingStatus] = "mapped"
                it[OperationsTable.updatedAt] = now
            }
            if (updated == 0) return@mapNotNull null

            OperationsTable
                .selectAll()
                .where { OperationsTable.id eq resolution.operationId }
                .limit(1)
                .firstOrNull()
                ?.toOperationRecord()
        }

        Result(operations = mapped, activities = activitiesByKey.values.toList())
    }

    private fun findActivityByName(name: String): ActivityRecord? =
        ActivitiesTable
            .selectAll()
            .where { ActivitiesTable.name.lowerCase() eq name.lowercase() }
            .limit(1)
            .firstOrNull()
            ?.toActivityRecord()

    private fun createActivity(name: String, description: String, now: OffsetDateTime): ActivityRecord {
        val id = UUID.randomUUID()
        ActivitiesTable.insert {
            it[ActivitiesTable.id] = id
            it[ActivitiesTable.name] = name.take(150)
            it[ActivitiesTable.description] = description
            it[ActivitiesTable.createdAt] = now
            it[ActivitiesTable.updatedAt] = now
        }
        return ActivityRecord(
            id = id,
            name = name,
            description = description,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun linkIfAbsent(processId: UUID, activityId: UUID, now: OffsetDateTime) {
        val exists = ProcessActivitiesTable
            .selectAll()
            .where {
                (ProcessActivitiesTable.processId eq processId) and
                    (ProcessActivitiesTable.activityId eq activityId)
            }
            .limit(1)
            .any()
        if (exists) return

        ProcessActivitiesTable.insert {
            it[ProcessActivitiesTable.processId] = processId
            it[ProcessActivitiesTable.activityId] = activityId
            it[ProcessActivitiesTable.createdAt] = now
        }
    }

    private fun ResultRow.toActivityRecord() = ActivityRecord(
        id = this[ActivitiesTable.id],
        name = this[ActivitiesTable.name],
        description = this[ActivitiesTable.description],
        createdAt = this[ActivitiesTable.createdAt],
        updatedAt = this[ActivitiesTable.updatedAt],
    )

    private fun ResultRow.toOperationRecord() = OperationRecord(
        id = this[OperationsTable.id],
        eventLogId = this[OperationsTable.eventLogId],
        rawLabel = this[OperationsTable.rawLabel],
        occurrenceCount = this[OperationsTable.occurrenceCount],
        activityId = this[OperationsTable.activityId],
        mappingStatus = this[OperationsTable.mappingStatus],
        createdAt = this[OperationsTable.createdAt],
        updatedAt = this[OperationsTable.updatedAt],
    )
}
