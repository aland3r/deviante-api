package com.deviante.seed

import com.deviante.db.ManagersTable
import com.deviante.db.ProcessesTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Gives the owner something to open on a fresh database, so the first login
 * is not an empty dashboard.
 *
 * **Seeds processes only — never identities.** An earlier version created
 * `deviante.users` + `deviante.managers` rows for the owner and mentor with
 * freshly generated UUIDs. Those UUIDs cannot match the ones Supabase Auth
 * issues, so the first real OAuth login hit the unique index on
 * `managers.email` and failed: the seed locked the owner out of the product
 * it was meant to demo. Identity is created by
 * `ManagerRepository.findOrCreateForSupabaseUser`, from the real token, and
 * this object must not race it.
 *
 * The consequence is that a truly empty database seeds nothing on boot —
 * there is no manager to own a process yet. It seeds on the next start after
 * the owner has logged in once, which is the first moment the rows can be
 * correct.
 */
object SeedDemoData {
    private const val OWNER_EMAIL = "design@alander.io"

    fun runIfNeeded() {
        try {
            transaction {
                if (ProcessesTable.selectAll().count() > 0) {
                    println("✓ Demo data already exists; skipping seed.")
                    return@transaction
                }

                val ownerManagerId = ManagersTable
                    .selectAll()
                    .where { ManagersTable.email eq OWNER_EMAIL }
                    .firstOrNull()
                    ?.get(ManagersTable.id)

                if (ownerManagerId == null) {
                    println("✓ No owner manager yet ($OWNER_EMAIL has not logged in); skipping seed.")
                    return@transaction
                }

                println("🌱 Seeding Deviante demo processes...")
                seedDemoProcesses(ownerManagerId)
                println("✓ Demo data seed complete.")
            }
        } catch (e: Exception) {
            // Non-fatal: a failed seed must not stop the API from booting.
            println("⚠ Seed warning (non-fatal): ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Empty shells, deliberately: a process becomes useful by having a log
     * uploaded into it (UC4). Seeding an `event_logs` row here would be a
     * lie — nothing parsed it, so it would sit at `parse_status = 'pending'`
     * forever with zero operations and zero traces, and the graph would have
     * nothing to draw from it.
     *
     * Activities are not seeded either. The catalog is global (UC3) and the
     * mapping step creates entries from the labels a real log actually
     * contains; pre-filling it with unrelated names risks
     * `MappingRepository.resolveAll` silently binding an uploaded event to a
     * demo activity that happens to share its name.
     */
    private fun seedDemoProcesses(ownerManagerId: UUID) {
        val now = OffsetDateTime.now()

        val demoProcesses = listOf(
            Triple(
                "Torno Production (Demo)",
                "TechManufacturing LTDA",
                "Torneamento em chão de fábrica. Carregue `real_dataset/Prod1Torno.csv` "
                    + "(dataset real do grupo de pesquisa) para ver o processo.",
            ),
            Triple(
                "Linha Estável (Demo)",
                "TechManufacturing LTDA",
                "Série de controle sem desvio injetado. Carregue `dataset_manufacturing/ST_01.xes` "
                    + "para a linha de base.",
            ),
            Triple(
                "Linha com Desvio (Demo)",
                "TechManufacturing LTDA",
                "Mesma linha, com um desvio injetado no trace 10. Carregue "
                    + "`dataset_manufacturing/DR_01.xes` e compare com a linha de base.",
            ),
        )

        for ((name, company, description) in demoProcesses) {
            val exists = ProcessesTable
                .selectAll()
                .where { (ProcessesTable.name eq name) and (ProcessesTable.managerId eq ownerManagerId) }
                .any()
            if (exists) continue

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

        println("  ✓ Demo processes created: ${demoProcesses.size} for owner")
    }
}
