package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object EquipmentTable : Table(name = "equipment") {
    override val tableName = "deviante.equipment"
    val id = uuid("id")
    val managerId = uuid("manager_id").references(ManagersTable.id).nullable()
    val name = varchar("name", 150)
    val tag = varchar("tag", 100).nullable()
    val kind = varchar("kind", 100).nullable()
    val location = varchar("location", 200).nullable()
    val description = text("description")
    val manufacturer = varchar("manufacturer", 150).nullable()
    val model = varchar("model", 150).nullable()
    val serialNumber = varchar("serial_number", 150).nullable()
    val status = varchar("status", 30)
    val assetUrl = text("asset_url").nullable()
    val assetFormat = varchar("asset_format", 20).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object MonitoringsTable : Table(name = "monitorings") {
    override val tableName = "deviante.monitorings"
    val id = uuid("id")
    val managerId = uuid("manager_id").references(ManagersTable.id)
    val name = varchar("name", 150)
    val description = text("description")
    val sourceType = varchar("source_type", 30)
    val sourceName = varchar("source_name", 255).nullable()
    val status = varchar("status", 30)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ProcessEquipmentTable : Table(name = "process_equipment") {
    override val tableName = "deviante.process_equipment"
    val processId = uuid("process_id").references(ProcessesTable.id)
    val equipmentId = uuid("equipment_id").references(EquipmentTable.id)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(processId, equipmentId)
}

object MonitoringEquipmentTable : Table(name = "monitoring_equipment") {
    override val tableName = "deviante.monitoring_equipment"
    val monitoringId = uuid("monitoring_id").references(MonitoringsTable.id)
    val equipmentId = uuid("equipment_id").references(EquipmentTable.id)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(monitoringId, equipmentId)
}

object MonitoringParametersTable : Table(name = "monitoring_parameters") {
    override val tableName = "deviante.monitoring_parameters"
    val id = uuid("id")
    val monitoringId = uuid("monitoring_id").references(MonitoringsTable.id)
    val equipmentId = uuid("equipment_id").references(EquipmentTable.id)
    val name = varchar("name", 150)
    val unit = varchar("unit", 50)
    val description = text("description")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object MonitoringReadingsTable : Table(name = "monitoring_readings") {
    override val tableName = "deviante.monitoring_readings"
    val id = uuid("id")
    val parameterId = uuid("parameter_id").references(MonitoringParametersTable.id)
    val observedAt = timestampWithTimeZone("observed_at")
    val value = double("value")
    val quality = varchar("quality", 30)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object EquipmentAnalysisRunsTable : Table(name = "equipment_analysis_runs") {
    override val tableName = "deviante.equipment_analysis_runs"
    val id = uuid("id")
    val equipmentId = uuid("equipment_id").references(EquipmentTable.id)
    val monitoringId = uuid("monitoring_id").references(MonitoringsTable.id)
    val parameterId = uuid("parameter_id").references(MonitoringParametersTable.id)
    val method = varchar("method", 50)
    val delta = double("delta")
    val treatment = varchar("treatment", 20)
    val observationCount = integer("observation_count")
    val inputSha256 = char("input_sha256", 64)
    val resultJson = text("result_json")
    val provenanceJson = text("provenance_json")
    val rulValue = double("rul_value").nullable()
    val rulUnit = varchar("rul_unit", 20)
    val failureProbability = double("failure_probability").nullable()
    val failureHorizonValue = double("failure_horizon_value").nullable()
    val failureHorizonUnit = varchar("failure_horizon_unit", 20).nullable()
    val modelVersion = varchar("model_version", 100).nullable()
    val recommendationText = text("recommendation_text").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object MaintenanceRecommendationsTable : Table(name = "maintenance_recommendations") {
    override val tableName = "deviante.maintenance_recommendations"
    val id = uuid("id")
    val equipmentId = uuid("equipment_id").references(EquipmentTable.id)
    val analysisRunId = uuid("analysis_run_id").references(EquipmentAnalysisRunsTable.id).nullable()
    val managerId = uuid("manager_id").references(ManagersTable.id)
    val action = text("action")
    val rationale = text("rationale")
    val priority = varchar("priority", 20)
    val status = varchar("status", 30)
    val recommendedStart = timestampWithTimeZone("recommended_start").nullable()
    val recommendedEnd = timestampWithTimeZone("recommended_end").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object MaintenanceSchedulesTable : Table(name = "maintenance_schedules") {
    override val tableName = "deviante.maintenance_schedules"
    val id = uuid("id")
    val equipmentId = uuid("equipment_id").references(EquipmentTable.id)
    val processId = uuid("process_id").references(ProcessesTable.id).nullable()
    val recommendationId = uuid("recommendation_id").references(MaintenanceRecommendationsTable.id).nullable()
    val managerId = uuid("manager_id").references(ManagersTable.id)
    val title = varchar("title", 200)
    val notes = text("notes")
    val scheduledStart = timestampWithTimeZone("scheduled_start")
    val scheduledEnd = timestampWithTimeZone("scheduled_end").nullable()
    val status = varchar("status", 30)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}
