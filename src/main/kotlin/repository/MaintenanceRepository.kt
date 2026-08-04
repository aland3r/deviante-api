package com.deviante.repository

import com.deviante.db.*
import com.deviante.dto.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun OffsetDateTime.iso() = format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

data class ReadingSeries(
    val monitoringId: UUID,
    val values: List<Double>,
    val firstObservedAt: OffsetDateTime?,
    val lastObservedAt: OffsetDateTime?,
)

data class PredictiveSignalRow(
    val machineOperating: Double,
    val rawMaterialLoading: Double,
    val shortDowntime: Double,
)

data class StoredEquipmentAnalysis(
    val id: UUID,
    val equipmentId: UUID,
    val monitoringId: UUID,
    val parameterId: UUID,
    val inputSha256: String,
    val resultJson: String,
    val provenanceJson: String,
    val rulValue: Double?,
    val rulUnit: String,
    val failureProbability: Double?,
    val failureHorizonValue: Double?,
    val failureHorizonUnit: String?,
    val modelVersion: String?,
    val recommendation: String?,
    val createdAt: OffsetDateTime,
)

class MaintenanceRepository {
    fun listEquipment(): List<EquipmentResponse> = transaction {
        EquipmentTable.selectAll().orderBy(EquipmentTable.updatedAt, SortOrder.DESC).map(::equipment)
    }

    fun findEquipment(id: UUID): EquipmentResponse? = transaction {
        EquipmentTable.selectAll().where { EquipmentTable.id eq id }.firstOrNull()?.let(::equipment)
    }

    fun createEquipment(managerId: UUID, body: EquipmentRequest): EquipmentResponse = transaction {
        val id = UUID.randomUUID(); val now = OffsetDateTime.now()
        EquipmentTable.insert {
            it[EquipmentTable.id] = id; it[EquipmentTable.managerId] = managerId
            assignEquipment(it, body); it[EquipmentTable.createdAt] = now; it[EquipmentTable.updatedAt] = now
        }
        findEquipment(id)!!
    }

    fun updateEquipment(id: UUID, body: EquipmentRequest): EquipmentResponse? = transaction {
        if (EquipmentTable.update({ EquipmentTable.id eq id }) { assignEquipment(it, body); it[EquipmentTable.updatedAt] = OffsetDateTime.now() } == 0) null
        else findEquipment(id)
    }

    fun deleteEquipment(id: UUID): Boolean = transaction { EquipmentTable.deleteWhere { EquipmentTable.id eq id } > 0 }

    fun listProcessEquipment(processId: UUID): List<EquipmentResponse> = transaction {
        ProcessEquipmentTable.innerJoin(EquipmentTable).selectAll()
            .where { ProcessEquipmentTable.processId eq processId }.map(::equipment)
    }

    fun linkProcessEquipment(processId: UUID, equipmentId: UUID): Boolean = transaction {
        if (findEquipment(equipmentId) == null) return@transaction false
        ProcessEquipmentTable.insertIgnore {
            it[ProcessEquipmentTable.processId] = processId; it[ProcessEquipmentTable.equipmentId] = equipmentId
            it[ProcessEquipmentTable.createdAt] = OffsetDateTime.now()
        }
        true
    }

    fun unlinkProcessEquipment(processId: UUID, equipmentId: UUID): Boolean = transaction {
        ProcessEquipmentTable.deleteWhere { (ProcessEquipmentTable.processId eq processId) and (ProcessEquipmentTable.equipmentId eq equipmentId) } > 0
    }

    fun listMonitorings(): List<MonitoringResponse> = transaction {
        MonitoringsTable.selectAll().orderBy(MonitoringsTable.updatedAt, SortOrder.DESC).map(::monitoring)
    }

    fun findMonitoring(id: UUID): MonitoringResponse? = transaction {
        MonitoringsTable.selectAll().where { MonitoringsTable.id eq id }.firstOrNull()?.let(::monitoring)
    }

    fun createMonitoring(managerId: UUID, body: MonitoringRequest): MonitoringResponse = transaction {
        val id = UUID.randomUUID(); val now = OffsetDateTime.now()
        MonitoringsTable.insert {
            it[MonitoringsTable.id] = id; it[MonitoringsTable.managerId] = managerId
            assignMonitoring(it, body); it[MonitoringsTable.createdAt] = now; it[MonitoringsTable.updatedAt] = now
        }
        findMonitoring(id)!!
    }

    fun updateMonitoring(id: UUID, body: MonitoringRequest): MonitoringResponse? = transaction {
        if (MonitoringsTable.update({ MonitoringsTable.id eq id }) { assignMonitoring(it, body); it[MonitoringsTable.updatedAt] = OffsetDateTime.now() } == 0) null
        else findMonitoring(id)
    }

    fun deleteMonitoring(id: UUID): Boolean = transaction { MonitoringsTable.deleteWhere { MonitoringsTable.id eq id } > 0 }

    fun listMonitoringEquipment(monitoringId: UUID): List<EquipmentResponse> = transaction {
        MonitoringEquipmentTable.innerJoin(EquipmentTable).selectAll()
            .where { MonitoringEquipmentTable.monitoringId eq monitoringId }.map(::equipment)
    }

    fun linkMonitoringEquipment(monitoringId: UUID, equipmentId: UUID): Boolean = transaction {
        if (findMonitoring(monitoringId) == null || findEquipment(equipmentId) == null) return@transaction false
        MonitoringEquipmentTable.insertIgnore {
            it[MonitoringEquipmentTable.monitoringId] = monitoringId; it[MonitoringEquipmentTable.equipmentId] = equipmentId
            it[MonitoringEquipmentTable.createdAt] = OffsetDateTime.now()
        }
        true
    }

    fun unlinkMonitoringEquipment(monitoringId: UUID, equipmentId: UUID): Boolean = transaction {
        MonitoringEquipmentTable.deleteWhere { (MonitoringEquipmentTable.monitoringId eq monitoringId) and (MonitoringEquipmentTable.equipmentId eq equipmentId) } > 0
    }

    fun monitoringContainsEquipment(monitoringId: UUID, equipmentId: UUID): Boolean = transaction {
        MonitoringEquipmentTable.selectAll().where {
            (MonitoringEquipmentTable.monitoringId eq monitoringId) and (MonitoringEquipmentTable.equipmentId eq equipmentId)
        }.any()
    }

    fun listParameters(monitoringId: UUID): List<MonitoringParameterResponse> = transaction {
        MonitoringParametersTable.selectAll().where { MonitoringParametersTable.monitoringId eq monitoringId }
            .orderBy(MonitoringParametersTable.name).map(::parameter)
    }

    fun listParametersForEquipment(equipmentId: UUID): List<MonitoringParameterResponse> = transaction {
        MonitoringParametersTable.selectAll().where { MonitoringParametersTable.equipmentId eq equipmentId }
            .orderBy(MonitoringParametersTable.name).map(::parameter)
    }

    fun firstMonitoringForEquipment(equipmentId: UUID): UUID? = transaction {
        MonitoringEquipmentTable.selectAll()
            .where { MonitoringEquipmentTable.equipmentId eq equipmentId }
            .firstOrNull()?.get(MonitoringEquipmentTable.monitoringId)
    }

    fun deleteParameter(equipmentId: UUID, parameterId: UUID): Boolean = transaction {
        MonitoringParametersTable.deleteWhere {
            (MonitoringParametersTable.id eq parameterId) and
                (MonitoringParametersTable.equipmentId eq equipmentId)
        } > 0
    }

    fun predictiveSignals(equipmentId: UUID): List<PredictiveSignalRow> = transaction {
        fun canonical(name: String) = name.trim().lowercase()
            .replace('-', '_').replace(' ', '_')
        val aliases = mapOf(
            "machine_operating" to "machine_operating",
            "maquina_trabalhando" to "machine_operating",
            "raw_material_loading" to "raw_material_loading",
            "carregamento_materia_prima" to "raw_material_loading",
            "short_downtime" to "short_downtime",
            "pequena_parada" to "short_downtime",
        )
        val parameters = MonitoringParametersTable.selectAll()
            .where { MonitoringParametersTable.equipmentId eq equipmentId }
            .toList()
        val series = mutableMapOf<String, List<Double>>()
        for (parameter in parameters) {
            val signal = aliases[canonical(parameter[MonitoringParametersTable.name])] ?: continue
            series[signal] = MonitoringReadingsTable.selectAll()
                .where { MonitoringReadingsTable.parameterId eq parameter[MonitoringParametersTable.id] }
                .orderBy(MonitoringReadingsTable.observedAt)
                .map { it[MonitoringReadingsTable.value] }
        }
        val machine = series["machine_operating"] ?: return@transaction emptyList()
        val material = series["raw_material_loading"] ?: return@transaction emptyList()
        val downtime = series["short_downtime"] ?: return@transaction emptyList()
        val size = minOf(machine.size, material.size, downtime.size)
        if (size == 0) return@transaction emptyList()
        val machineTail = machine.takeLast(size)
        val materialTail = material.takeLast(size)
        val downtimeTail = downtime.takeLast(size)
        List(size) { index -> PredictiveSignalRow(machineTail[index], materialTail[index], downtimeTail[index]) }
    }

    fun findParameter(id: UUID): MonitoringParameterResponse? = transaction {
        MonitoringParametersTable.selectAll().where { MonitoringParametersTable.id eq id }.firstOrNull()?.let(::parameter)
    }

    fun findParameterForEquipmentByName(equipmentId: UUID, name: String): MonitoringParameterResponse? = transaction {
        MonitoringParametersTable.selectAll().where {
            (MonitoringParametersTable.equipmentId eq equipmentId) and
                (MonitoringParametersTable.name.lowerCase() eq name.trim().lowercase())
        }.firstOrNull()?.let(::parameter)
    }

    fun createParameter(monitoringId: UUID, body: MonitoringParameterRequest): MonitoringParameterResponse? = transaction {
        if (findMonitoring(monitoringId) == null) return@transaction null
        val equipmentId = runCatching { UUID.fromString(body.equipmentId) }.getOrNull() ?: return@transaction null
        if (!monitoringContainsEquipment(monitoringId, equipmentId)) return@transaction null
        val id = UUID.randomUUID(); val now = OffsetDateTime.now()
        MonitoringParametersTable.insert {
            it[MonitoringParametersTable.id] = id; it[MonitoringParametersTable.monitoringId] = monitoringId
            it[MonitoringParametersTable.equipmentId] = equipmentId
            it[MonitoringParametersTable.name] = body.name.trim(); it[MonitoringParametersTable.unit] = body.unit.trim(); it[MonitoringParametersTable.description] = body.description.trim()
            it[MonitoringParametersTable.createdAt] = now; it[MonitoringParametersTable.updatedAt] = now
        }
        findParameter(id)
    }

    fun appendReadings(parameterId: UUID, readings: List<Pair<ReadingInput, OffsetDateTime>>): List<MonitoringReadingResponse> = transaction {
        val now = OffsetDateTime.now()
        readings.forEach { (input, observedAt) ->
            MonitoringReadingsTable.insertIgnore {
                it[MonitoringReadingsTable.id] = UUID.randomUUID(); it[MonitoringReadingsTable.parameterId] = parameterId
                it[MonitoringReadingsTable.observedAt] = observedAt; it[MonitoringReadingsTable.value] = input.value
                it[MonitoringReadingsTable.quality] = input.quality.trim().ifBlank { "good" }; it[MonitoringReadingsTable.createdAt] = now
            }
        }
        listReadings(parameterId)
    }

    fun listReadings(parameterId: UUID): List<MonitoringReadingResponse> = transaction {
        MonitoringReadingsTable.selectAll().where { MonitoringReadingsTable.parameterId eq parameterId }
            .orderBy(MonitoringReadingsTable.observedAt).map(::reading)
    }

    fun readingSeries(parameterId: UUID): ReadingSeries? = transaction {
        val parameter = MonitoringParametersTable.selectAll().where { MonitoringParametersTable.id eq parameterId }.firstOrNull()
            ?: return@transaction null
        val rows = MonitoringReadingsTable.selectAll().where { MonitoringReadingsTable.parameterId eq parameterId }
            .orderBy(MonitoringReadingsTable.observedAt).toList()
        ReadingSeries(
            monitoringId = parameter[MonitoringParametersTable.monitoringId],
            values = rows.map { it[MonitoringReadingsTable.value] },
            firstObservedAt = rows.firstOrNull()?.get(MonitoringReadingsTable.observedAt),
            lastObservedAt = rows.lastOrNull()?.get(MonitoringReadingsTable.observedAt),
        )
    }

    fun saveAnalysis(
        equipmentId: UUID, monitoringId: UUID, parameterId: UUID, method: String, delta: Double,
        treatment: String, observationCount: Int, inputSha256: String, resultJson: String,
        provenanceJson: String, rulValue: Double?, rulUnit: String, failureProbability: Double?,
        failureHorizonValue: Double?, failureHorizonUnit: String?, modelVersion: String?, recommendation: String?,
    ): StoredEquipmentAnalysis = transaction {
        val id = UUID.randomUUID(); val now = OffsetDateTime.now()
        EquipmentAnalysisRunsTable.insert {
            it[EquipmentAnalysisRunsTable.id] = id; it[EquipmentAnalysisRunsTable.equipmentId] = equipmentId
            it[EquipmentAnalysisRunsTable.monitoringId] = monitoringId; it[EquipmentAnalysisRunsTable.parameterId] = parameterId
            it[EquipmentAnalysisRunsTable.method] = method; it[EquipmentAnalysisRunsTable.delta] = delta
            it[EquipmentAnalysisRunsTable.treatment] = treatment; it[EquipmentAnalysisRunsTable.observationCount] = observationCount
            it[EquipmentAnalysisRunsTable.inputSha256] = inputSha256; it[EquipmentAnalysisRunsTable.resultJson] = resultJson
            it[EquipmentAnalysisRunsTable.provenanceJson] = provenanceJson; it[EquipmentAnalysisRunsTable.rulValue] = rulValue
            it[EquipmentAnalysisRunsTable.rulUnit] = rulUnit
            it[EquipmentAnalysisRunsTable.failureProbability] = failureProbability
            it[EquipmentAnalysisRunsTable.failureHorizonValue] = failureHorizonValue
            it[EquipmentAnalysisRunsTable.failureHorizonUnit] = failureHorizonUnit
            it[EquipmentAnalysisRunsTable.modelVersion] = modelVersion
            it[EquipmentAnalysisRunsTable.recommendationText] = recommendation?.trim()?.takeIf(String::isNotBlank)
            it[EquipmentAnalysisRunsTable.createdAt] = now
        }
        StoredEquipmentAnalysis(id, equipmentId, monitoringId, parameterId, inputSha256, resultJson, provenanceJson, rulValue, rulUnit, failureProbability, failureHorizonValue, failureHorizonUnit, modelVersion, recommendation, now)
    }

    fun findAnalysis(id: UUID): StoredEquipmentAnalysis? = transaction {
        EquipmentAnalysisRunsTable.selectAll().where { EquipmentAnalysisRunsTable.id eq id }.firstOrNull()?.let(::analysis)
    }

    fun listAnalyses(equipmentId: UUID): List<StoredEquipmentAnalysis> = transaction {
        EquipmentAnalysisRunsTable.selectAll().where { EquipmentAnalysisRunsTable.equipmentId eq equipmentId }
            .orderBy(EquipmentAnalysisRunsTable.createdAt, SortOrder.DESC).map(::analysis)
    }

    fun createRecommendation(managerId: UUID, equipmentId: UUID, analysisRunId: UUID?, body: RecommendationRequest): RecommendationResponse? = transaction {
        if (findEquipment(equipmentId) == null || (analysisRunId != null && findAnalysis(analysisRunId) == null)) return@transaction null
        val id = UUID.randomUUID(); val now = OffsetDateTime.now()
        MaintenanceRecommendationsTable.insert {
            it[MaintenanceRecommendationsTable.id] = id; it[MaintenanceRecommendationsTable.equipmentId] = equipmentId
            it[MaintenanceRecommendationsTable.analysisRunId] = analysisRunId; it[MaintenanceRecommendationsTable.managerId] = managerId
            it[MaintenanceRecommendationsTable.action] = body.action.trim(); it[MaintenanceRecommendationsTable.rationale] = body.rationale.trim(); it[MaintenanceRecommendationsTable.priority] = body.priority
            it[MaintenanceRecommendationsTable.status] = body.status; it[MaintenanceRecommendationsTable.recommendedStart] = body.recommendedStart?.let(OffsetDateTime::parse)
            it[MaintenanceRecommendationsTable.recommendedEnd] = body.recommendedEnd?.let(OffsetDateTime::parse); it[MaintenanceRecommendationsTable.createdAt] = now; it[MaintenanceRecommendationsTable.updatedAt] = now
        }
        findRecommendation(id)
    }

    fun findRecommendation(id: UUID): RecommendationResponse? = transaction {
        MaintenanceRecommendationsTable.selectAll().where { MaintenanceRecommendationsTable.id eq id }.firstOrNull()?.let(::recommendation)
    }

    fun listSchedules(equipmentId: UUID? = null): List<MaintenanceScheduleResponse> = transaction {
        val query = MaintenanceSchedulesTable.selectAll()
        if (equipmentId != null) query.where { MaintenanceSchedulesTable.equipmentId eq equipmentId }
        query.orderBy(MaintenanceSchedulesTable.scheduledStart).map(::schedule)
    }

    fun findSchedule(id: UUID): MaintenanceScheduleResponse? = transaction {
        MaintenanceSchedulesTable.selectAll().where { MaintenanceSchedulesTable.id eq id }.firstOrNull()?.let(::schedule)
    }

    fun createSchedule(managerId: UUID, body: MaintenanceScheduleRequest): MaintenanceScheduleResponse? = transaction {
        val equipmentId = UUID.fromString(body.equipmentId)
        if (findEquipment(equipmentId) == null) return@transaction null
        val id = UUID.randomUUID(); val now = OffsetDateTime.now()
        MaintenanceSchedulesTable.insert {
            it[MaintenanceSchedulesTable.id] = id; it[MaintenanceSchedulesTable.equipmentId] = equipmentId
            it[MaintenanceSchedulesTable.processId] = body.processId?.let(UUID::fromString); it[MaintenanceSchedulesTable.recommendationId] = body.recommendationId?.let(UUID::fromString)
            it[MaintenanceSchedulesTable.managerId] = managerId; it[MaintenanceSchedulesTable.title] = body.title.trim(); it[MaintenanceSchedulesTable.notes] = body.notes.trim()
            it[MaintenanceSchedulesTable.scheduledStart] = OffsetDateTime.parse(body.scheduledStart); it[MaintenanceSchedulesTable.scheduledEnd] = body.scheduledEnd?.let(OffsetDateTime::parse)
            it[MaintenanceSchedulesTable.status] = body.status; it[MaintenanceSchedulesTable.createdAt] = now; it[MaintenanceSchedulesTable.updatedAt] = now
        }
        findSchedule(id)
    }

    fun updateSchedule(id: UUID, body: MaintenanceScheduleRequest): MaintenanceScheduleResponse? = transaction {
        val changed = MaintenanceSchedulesTable.update({ MaintenanceSchedulesTable.id eq id }) {
            it[MaintenanceSchedulesTable.equipmentId] = UUID.fromString(body.equipmentId); it[MaintenanceSchedulesTable.processId] = body.processId?.let(UUID::fromString)
            it[MaintenanceSchedulesTable.recommendationId] = body.recommendationId?.let(UUID::fromString); it[MaintenanceSchedulesTable.title] = body.title.trim(); it[MaintenanceSchedulesTable.notes] = body.notes.trim()
            it[MaintenanceSchedulesTable.scheduledStart] = OffsetDateTime.parse(body.scheduledStart); it[MaintenanceSchedulesTable.scheduledEnd] = body.scheduledEnd?.let(OffsetDateTime::parse)
            it[MaintenanceSchedulesTable.status] = body.status; it[MaintenanceSchedulesTable.updatedAt] = OffsetDateTime.now()
        }
        if (changed == 0) null else findSchedule(id)
    }

    fun deleteSchedule(id: UUID): Boolean = transaction { MaintenanceSchedulesTable.deleteWhere { MaintenanceSchedulesTable.id eq id } > 0 }

    private fun assignEquipment(row: UpdateBuilder<*>, body: EquipmentRequest) {
        row[EquipmentTable.name] = body.name.trim(); row[EquipmentTable.description] = body.description.trim()
        row[EquipmentTable.tag] = body.tag?.trim()?.takeIf(String::isNotBlank)
        row[EquipmentTable.kind] = body.kind?.trim()?.takeIf(String::isNotBlank)
        row[EquipmentTable.location] = body.location?.trim()?.takeIf(String::isNotBlank)
        row[EquipmentTable.manufacturer] = body.manufacturer?.trim()?.takeIf(String::isNotBlank)
        row[EquipmentTable.model] = body.model?.trim()?.takeIf(String::isNotBlank)
        row[EquipmentTable.serialNumber] = body.serialNumber?.trim()?.takeIf(String::isNotBlank)
        row[EquipmentTable.status] = body.status; row[EquipmentTable.assetUrl] = body.assetUrl?.trim()?.takeIf(String::isNotBlank)
        row[EquipmentTable.assetFormat] = body.assetFormat?.trim()?.takeIf(String::isNotBlank)
    }

    private fun assignMonitoring(row: UpdateBuilder<*>, body: MonitoringRequest) {
        row[MonitoringsTable.name] = body.name.trim(); row[MonitoringsTable.description] = body.description.trim()
        row[MonitoringsTable.sourceType] = body.sourceType; row[MonitoringsTable.sourceName] = body.sourceName?.trim()?.takeIf(String::isNotBlank)
        row[MonitoringsTable.status] = body.status
    }

    private fun equipment(r: ResultRow) = EquipmentResponse(r[EquipmentTable.id].toString(), r[EquipmentTable.name], r[EquipmentTable.tag], r[EquipmentTable.kind], r[EquipmentTable.location], r[EquipmentTable.description], r[EquipmentTable.manufacturer], r[EquipmentTable.model], r[EquipmentTable.serialNumber], r[EquipmentTable.status], r[EquipmentTable.assetUrl], r[EquipmentTable.assetFormat], r[EquipmentTable.createdAt].iso(), r[EquipmentTable.updatedAt].iso())
    private fun monitoring(r: ResultRow) = MonitoringResponse(r[MonitoringsTable.id].toString(), r[MonitoringsTable.name], r[MonitoringsTable.description], r[MonitoringsTable.sourceType], r[MonitoringsTable.sourceName], r[MonitoringsTable.status], r[MonitoringsTable.createdAt].iso(), r[MonitoringsTable.updatedAt].iso())
    private fun parameter(r: ResultRow) = MonitoringParameterResponse(r[MonitoringParametersTable.id].toString(), r[MonitoringParametersTable.monitoringId].toString(), r[MonitoringParametersTable.equipmentId].toString(), r[MonitoringParametersTable.name], r[MonitoringParametersTable.unit], r[MonitoringParametersTable.description], r[MonitoringParametersTable.createdAt].iso(), r[MonitoringParametersTable.updatedAt].iso())
    private fun reading(r: ResultRow) = MonitoringReadingResponse(r[MonitoringReadingsTable.id].toString(), r[MonitoringReadingsTable.parameterId].toString(), r[MonitoringReadingsTable.observedAt].iso(), r[MonitoringReadingsTable.value], r[MonitoringReadingsTable.quality])
    private fun analysis(r: ResultRow) = StoredEquipmentAnalysis(r[EquipmentAnalysisRunsTable.id], r[EquipmentAnalysisRunsTable.equipmentId], r[EquipmentAnalysisRunsTable.monitoringId], r[EquipmentAnalysisRunsTable.parameterId], r[EquipmentAnalysisRunsTable.inputSha256], r[EquipmentAnalysisRunsTable.resultJson], r[EquipmentAnalysisRunsTable.provenanceJson], r[EquipmentAnalysisRunsTable.rulValue], r[EquipmentAnalysisRunsTable.rulUnit], r[EquipmentAnalysisRunsTable.failureProbability], r[EquipmentAnalysisRunsTable.failureHorizonValue], r[EquipmentAnalysisRunsTable.failureHorizonUnit], r[EquipmentAnalysisRunsTable.modelVersion], r[EquipmentAnalysisRunsTable.recommendationText], r[EquipmentAnalysisRunsTable.createdAt])
    private fun recommendation(r: ResultRow) = RecommendationResponse(r[MaintenanceRecommendationsTable.id].toString(), r[MaintenanceRecommendationsTable.equipmentId].toString(), r[MaintenanceRecommendationsTable.analysisRunId]?.toString(), r[MaintenanceRecommendationsTable.action], r[MaintenanceRecommendationsTable.rationale], r[MaintenanceRecommendationsTable.priority], r[MaintenanceRecommendationsTable.status], r[MaintenanceRecommendationsTable.recommendedStart]?.iso(), r[MaintenanceRecommendationsTable.recommendedEnd]?.iso(), r[MaintenanceRecommendationsTable.createdAt].iso(), r[MaintenanceRecommendationsTable.updatedAt].iso())
    private fun schedule(r: ResultRow) = MaintenanceScheduleResponse(r[MaintenanceSchedulesTable.id].toString(), r[MaintenanceSchedulesTable.equipmentId].toString(), r[MaintenanceSchedulesTable.processId]?.toString(), r[MaintenanceSchedulesTable.recommendationId]?.toString(), r[MaintenanceSchedulesTable.title], r[MaintenanceSchedulesTable.notes], r[MaintenanceSchedulesTable.scheduledStart].iso(), r[MaintenanceSchedulesTable.scheduledEnd]?.iso(), r[MaintenanceSchedulesTable.status], r[MaintenanceSchedulesTable.createdAt].iso(), r[MaintenanceSchedulesTable.updatedAt].iso())
}
