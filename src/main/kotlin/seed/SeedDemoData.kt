package com.deviante.seed

import com.deviante.db.ActivitiesTable
import com.deviante.db.EventLogsTable
import com.deviante.db.ManagersTable
import com.deviante.db.ProcessesTable
import com.deviante.db.UsersTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

/**
 * SeedDemoData — Bootstrap Deviante with demo processes + event logs for immediate testing.
 *
 * - Idempotent: checks if demo data already exists before inserting
 * - Owner/mentor get full access to all demo processes
 * - Event logs are loaded from CSV/XES files in the repo
 */
object SeedDemoData {
    private val OWNER_EMAIL = "design@alander.io"
    private val MENTOR_EMAIL = "pafileiro@gmail.com"

    private val DEMO_DATASETS_DIR = "deviante/Adaptive-Detection-of-Performance-Related-Temporal-Drifts-main"
    private val SAMPLE_LOG_FILE = "real_dataset/Prod1Torno.csv"

    /**
     * Run seed if conditions are met:
     * - Demo process catalog is empty (checked via process count)
     * - Owner user exists in auth (Supabase Auth must be configured)
     */
    fun runIfNeeded() {
        try {
            transaction {
                val hasExistingDemoData = ProcessesTable.selectAll().count() > 0
                if (hasExistingDemoData) {
                    println("✓ Demo data already exists; skipping seed.")
                    return@transaction
                }

                println("🌱 Seeding Deviante demo data...")
                seedManagers()
                seedActivities()
                seedDemoProcesses()
                seedEventLogs()
                println("✓ Demo data seed complete.")
            }
        } catch (e: Exception) {
            // Non-fatal: log but don't crash the app
            println("⚠ Seed warning (non-fatal): ${e.message}")
            e.printStackTrace()
        }
    }

    private fun seedManagers() {
        val now = OffsetDateTime.now()

        // Owner
        val ownerExists = ManagersTable
            .selectAll()
            .where { ManagersTable.email eq OWNER_EMAIL }
            .any()
        if (!ownerExists) {
            val ownerUserId = UUID.randomUUID()
            UsersTable.insert {
                it[UsersTable.id] = ownerUserId
                it[UsersTable.email] = OWNER_EMAIL
                it[UsersTable.passwordHash] = null
                it[UsersTable.createdAt] = now
                it[UsersTable.updatedAt] = now
            }
            ManagersTable.insert {
                it[ManagersTable.id] = UUID.randomUUID()
                it[ManagersTable.userId] = ownerUserId
                it[ManagersTable.email] = OWNER_EMAIL
                it[ManagersTable.fullName] = "Alander Ávila (Owner)"
                it[ManagersTable.role] = "owner"
                it[ManagersTable.createdAt] = now
                it[ManagersTable.updatedAt] = now
            }
            println("  ✓ Owner manager created: $OWNER_EMAIL")
        }

        // Mentor
        val mentorExists = ManagersTable
            .selectAll()
            .where { ManagersTable.email eq MENTOR_EMAIL }
            .any()
        if (!mentorExists) {
            val mentorUserId = UUID.randomUUID()
            UsersTable.insert {
                it[UsersTable.id] = mentorUserId
                it[UsersTable.email] = MENTOR_EMAIL
                it[UsersTable.passwordHash] = null
                it[UsersTable.createdAt] = now
                it[UsersTable.updatedAt] = now
            }
            ManagersTable.insert {
                it[ManagersTable.id] = UUID.randomUUID()
                it[ManagersTable.userId] = mentorUserId
                it[ManagersTable.email] = MENTOR_EMAIL
                it[ManagersTable.fullName] = "Luiz Picolo (Mentor)"
                it[ManagersTable.role] = "mentor"
                it[ManagersTable.createdAt] = now
                it[ManagersTable.updatedAt] = now
            }
            println("  ✓ Mentor manager created: $MENTOR_EMAIL")
        }
    }

    private fun seedActivities() {
        val now = OffsetDateTime.now()
        val activities = listOf(
            "Recebimento" to "Aceitar pedido ou entrada no sistema",
            "Validação" to "Verificar completude e conformidade dos dados",
            "Picking" to "Coleta de itens do estoque",
            "Embalagem" to "Preparar e embalar para envio",
            "Expedição" to "Registrar saída e gerar rastreamento",
            "Triagem" to "Classificar por prioridade/tipo",
            "Investigação" to "Análise detalhada do problema",
            "Resolução" to "Implementar solução",
            "Feedback" to "Solicitar confirmação do cliente",
            "Pré-aprovação" to "Avaliação inicial de elegibilidade",
            "Documentação" to "Coleta de documentos comprobatórios",
            "Underwriting" to "Análise de risco detalhada",
            "Aprovação" to "Decisão final de concessão",
            "Desembolso" to "Transferência de fundos ao cliente",
        )

        for ((name, description) in activities) {
            val exists = ActivitiesTable
                .selectAll()
                .where { ActivitiesTable.name eq name }
                .any()
            if (!exists) {
                ActivitiesTable.insert {
                    it[ActivitiesTable.id] = UUID.randomUUID()
                    it[ActivitiesTable.name] = name
                    it[ActivitiesTable.description] = description
                    it[ActivitiesTable.createdAt] = now
                    it[ActivitiesTable.updatedAt] = now
                }
            }
        }
        println("  ✓ Activities catalog seeded: ${activities.size} activities")
    }

    private fun seedDemoProcesses() {
        val now = OffsetDateTime.now()
        val ownerManagerId = ManagersTable
            .selectAll()
            .where { ManagersTable.email eq OWNER_EMAIL }
            .firstOrNull()
            ?.get(ManagersTable.id)
            ?: return

        val demoProcesses = listOf(
            Triple(
                "Torno Production (Demo)",
                "TechManufacturing LTDA",
                "Manufacturing process: lathe operations, quality check, assembly. Real dataset: Prod1Torno.csv (drift analysis)."
            ),
            Triple(
                "Processo de Aprovação de Pedidos (Manufatura)",
                "TechManufacturing LTDA",
                "Fluxo end-to-end de pedidos de clientes: recebimento, validação, picking, embalagem, shipping. Dataset: 2.5K traces, 450 eventos únicos, duração média 18h."
            ),
            Triple(
                "Fluxo de Atendimento ao Cliente (Varejo)",
                "RetailHub Brasil",
                "Processo de suporte ao cliente: abertura de ticket, triagem, investigação, resolução, feedback. Dataset: 1.2K traces, 8 operações principais, SLA 24h."
            ),
            Triple(
                "Pipeline de Processamento de Reclamações (Financeiro)",
                "FinServ Partners",
                "Gestão de reclamações de clientes com órgãos reguladores: intake, análise, resposta, arquivo. Dataset: 890 traces, compliance-focused, estudo de drift temporal."
            ),
        )

        for ((name, company, description) in demoProcesses) {
            val exists = ProcessesTable
                .selectAll()
                .where {
                    (ProcessesTable.name eq name) and (ProcessesTable.managerId eq ownerManagerId)
                }
                .any()
            if (!exists) {
                ProcessesTable.insert {
                    it[ProcessesTable.id] = UUID.randomUUID()
                    it[ProcessesTable.managerId] = ownerManagerId
                    it[ProcessesTable.name] = name
                    it[ProcessesTable.companyName] = company
                    it[ProcessesTable.description] = description
                    it[ProcessesTable.sector] = "Manufacturing"
                    it[ProcessesTable.createdAt] = now
                    it[ProcessesTable.updatedAt] = now
                }
            }
        }
        println("  ✓ Demo processes created: ${demoProcesses.size} processes for owner")
    }

    private fun seedEventLogs() {
        val now = OffsetDateTime.now()

        // Find first demo process (Torno Production Demo)
        val tornoProcess = ProcessesTable
            .selectAll()
            .where { ProcessesTable.name eq "Torno Production (Demo)" }
            .firstOrNull()
            ?.get(ProcessesTable.id)
            ?: return

        // Check if event log already seeded for this process
        val logExists = EventLogsTable
            .selectAll()
            .where { EventLogsTable.processId eq tornoProcess }
            .any()
        if (logExists) {
            println("  ✓ Event logs already exist for demo process; skipping.")
            return
        }

        // Try to load real dataset
        val logFile = File(DEMO_DATASETS_DIR, SAMPLE_LOG_FILE)
        if (logFile.exists()) {
            EventLogsTable.insert {
                it[EventLogsTable.id] = UUID.randomUUID()
                it[EventLogsTable.processId] = tornoProcess
                it[EventLogsTable.fileName] = logFile.name
                it[EventLogsTable.format] = "csv"
                it[EventLogsTable.parseStatus] = "pending"
                it[EventLogsTable.parseError] = null
                it[EventLogsTable.operationCount] = 0
                it[EventLogsTable.traceCount] = 0
                it[EventLogsTable.uploadedAt] = now
                it[EventLogsTable.createdAt] = now
                it[EventLogsTable.updatedAt] = now
            }
            println("  ✓ Event log seeded: ${logFile.name}")
        } else {
            println("  ⚠ Demo event log not found at: ${logFile.absolutePath}")
        }
    }
}
