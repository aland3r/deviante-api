package com.deviante.repository

import com.deviante.db.EventLogsTable
import com.deviante.db.ManagersTable
import com.deviante.db.ObjectVisibilityTable
import com.deviante.db.TraceNotesTable
import com.deviante.db.TracesTable
import com.deviante.dto.TraceNoteResponse
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = LoggerFactory.getLogger("com.deviante.repository.RoleRepository")

/**
 * Ocultação de processos e máquinas por papel (deviante.object_visibility).
 *
 * As consultas "seguras" alimentam listagens e o acesso a processo: se a
 * tabela ainda não existe (ou falha), nada é ocultado e a rota segue.
 */
class VisibilityRepository {
    /** IDs do tipo ocultos para o papel; vazio em caso de falha. */
    fun hiddenIdsSafe(objectType: String, role: String): Set<UUID> = try {
        transaction {
            maxAttempts = 1
            ObjectVisibilityTable.selectAll()
                .where { (ObjectVisibilityTable.objectType eq objectType) and (ObjectVisibilityTable.hiddenRole eq role) }
                .map { it[ObjectVisibilityTable.objectId] }
                .toSet()
        }
    } catch (e: Exception) {
        log.warn("object_visibility indisponível; nada oculto: {}", e.message)
        emptySet()
    }

    fun isHiddenSafe(objectType: String, objectId: UUID, role: String): Boolean = try {
        transaction {
            maxAttempts = 1
            ObjectVisibilityTable.selectAll()
                .where {
                    (ObjectVisibilityTable.objectType eq objectType) and
                        (ObjectVisibilityTable.objectId eq objectId) and
                        (ObjectVisibilityTable.hiddenRole eq role)
                }
                .limit(1)
                .any()
        }
    } catch (e: Exception) {
        log.warn("object_visibility indisponível; nada oculto: {}", e.message)
        false
    }

    fun hiddenRoles(objectType: String, objectId: UUID): List<String> = transaction {
        maxAttempts = 1
        ObjectVisibilityTable.selectAll()
            .where { (ObjectVisibilityTable.objectType eq objectType) and (ObjectVisibilityTable.objectId eq objectId) }
            .map { it[ObjectVisibilityTable.hiddenRole] }
            .distinct()
            .sorted()
    }

    /** Substitui o conjunto de papéis dos quais o objeto fica oculto. */
    fun replace(businessId: UUID, objectType: String, objectId: UUID, roles: Set<String>, createdBy: UUID): List<String> = transaction {
        maxAttempts = 1
        ObjectVisibilityTable.deleteWhere {
            (ObjectVisibilityTable.objectType eq objectType) and (ObjectVisibilityTable.objectId eq objectId)
        }
        val now = OffsetDateTime.now()
        roles.forEach { role ->
            ObjectVisibilityTable.insert {
                it[ObjectVisibilityTable.id] = UUID.randomUUID()
                it[ObjectVisibilityTable.businessId] = businessId
                it[ObjectVisibilityTable.objectType] = objectType
                it[ObjectVisibilityTable.objectId] = objectId
                it[ObjectVisibilityTable.hiddenRole] = role
                it[ObjectVisibilityTable.createdBy] = createdBy
                it[ObjectVisibilityTable.createdAt] = now
            }
        }
        roles.sorted()
    }
}

data class StoredTraceNote(val id: UUID, val processId: UUID, val managerId: UUID)

/** Anotações sobre traces de um processo (deviante.trace_notes). */
class TraceNotesRepository {
    /** Case ID do trace, quando ele pertence a um log do processo; null caso contrário. */
    fun caseIdForProcessTrace(processId: UUID, traceId: UUID): String? = transaction {
        TracesTable.join(EventLogsTable, JoinType.INNER, TracesTable.eventLogId, EventLogsTable.id)
            .selectAll()
            .where { (TracesTable.id eq traceId) and (EventLogsTable.processId eq processId) }
            .limit(1)
            .firstOrNull()
            ?.get(TracesTable.caseId)
    }

    fun list(processId: UUID): List<TraceNoteResponse> = transaction {
        maxAttempts = 1
        TraceNotesTable
            .join(TracesTable, JoinType.LEFT, TraceNotesTable.traceId, TracesTable.id)
            .join(ManagersTable, JoinType.LEFT, TraceNotesTable.managerId, ManagersTable.id)
            .selectAll()
            .where { TraceNotesTable.processId eq processId }
            .orderBy(TraceNotesTable.createdAt, SortOrder.ASC)
            .map { row ->
                val name = row.getOrNull(ManagersTable.fullName)?.takeIf(String::isNotBlank)
                    ?: row.getOrNull(ManagersTable.email)
                TraceNoteResponse(
                    id = row[TraceNotesTable.id].toString(),
                    traceId = row[TraceNotesTable.traceId].toString(),
                    caseId = row.getOrNull(TracesTable.caseId),
                    activityName = row[TraceNotesTable.activityName],
                    note = row[TraceNotesTable.note],
                    authorName = name,
                    createdAt = row[TraceNotesTable.createdAt].format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )
            }
    }

    fun create(
        processId: UUID,
        traceId: UUID,
        activityName: String,
        note: String,
        managerId: UUID,
        businessId: UUID,
    ): UUID = transaction {
        maxAttempts = 1
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        TraceNotesTable.insert {
            it[TraceNotesTable.id] = id
            it[TraceNotesTable.processId] = processId
            it[TraceNotesTable.traceId] = traceId
            it[TraceNotesTable.activityName] = activityName
            it[TraceNotesTable.note] = note
            it[TraceNotesTable.managerId] = managerId
            it[TraceNotesTable.businessId] = businessId
            it[TraceNotesTable.createdAt] = now
            it[TraceNotesTable.updatedAt] = now
        }
        id
    }

    fun find(processId: UUID, id: UUID): StoredTraceNote? = transaction {
        maxAttempts = 1
        TraceNotesTable.selectAll()
            .where { (TraceNotesTable.id eq id) and (TraceNotesTable.processId eq processId) }
            .limit(1)
            .firstOrNull()
            ?.let { StoredTraceNote(it[TraceNotesTable.id], it[TraceNotesTable.processId], it[TraceNotesTable.managerId]) }
    }

    fun delete(id: UUID): Boolean = transaction {
        maxAttempts = 1
        TraceNotesTable.deleteWhere { TraceNotesTable.id eq id } > 0
    }
}
