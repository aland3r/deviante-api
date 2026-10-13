package com.deviante.variability

import io.ktor.server.config.ApplicationConfig

/**
 * Variabilidade da linha de produto (Reuso 03, item 02).
 *
 * O Deviante é um único software que cada cliente recebe configurado de um
 * jeito. Os pontos de variação são as funcionalidades abaixo:
 *  - comuns: entram em todo produto (processos, atividades, análise de drift);
 *  - opcionais: entram conforme o plano contratado (módulo de manutenção);
 *  - exclusivas: feitas para um cliente específico (exportar a agenda em CSV).
 * Qual variante cada cliente recebe é configuração (application.yaml), não
 * código: incluir um cliente ou trocar o plano dele não exige recompilar.
 */
enum class Feature(val key: String) {
    PROCESSES("processes"),
    ACTIVITIES("activities"),
    DRIFT_ANALYSIS("drift-analysis"),
    EQUIPMENT("equipment"),
    SCHEDULES("schedules"),
    SCHEDULE_EXPORT("schedule-export");

    companion object {
        fun fromKey(key: String): Feature =
            entries.firstOrNull { it.key == key } ?: error("Funcionalidade desconhecida: $key")
    }
}

/** Funcionalidades comuns: entram em todo produto, qualquer que seja o plano. */
val COMMON_FEATURES = setOf(Feature.PROCESSES, Feature.ACTIVITIES, Feature.DRIFT_ANALYSIS)

/** Planos: conjuntos de funcionalidades. Todo plano contém as comuns. */
enum class Plan(val key: String, val label: String, optional: Set<Feature>) {
    ESSENCIAL("essencial", "Essencial", emptySet()),
    MANUTENCAO("manutencao", "Manutenção", setOf(Feature.EQUIPMENT, Feature.SCHEDULES));

    val features: Set<Feature> = COMMON_FEATURES + optional

    companion object {
        fun fromKey(key: String): Plan =
            entries.firstOrNull { it.key == key } ?: error("Plano desconhecido: $key")
    }
}

/** A variante do produto que um cliente recebe: o plano mais as funções exclusivas dele. */
data class ClientVariant(
    val client: String,
    val plan: Plan,
    val exclusive: Set<Feature> = emptySet(),
) {
    val features: Set<Feature> = plan.features + exclusive

    fun has(feature: Feature): Boolean = feature in features
}

class ProductLine(
    private val default: ClientVariant,
    /** Todas as variantes configuradas (uma por cliente), na ordem do application.yaml. */
    val variants: List<ClientVariant>,
) {
    /**
     * Variante de uma empresa pelo nome. A empresa a que cada usuário pertence
     * fica no banco (business_id); aqui fica só o que cada uma contratou.
     */
    fun variantForClient(client: String): ClientVariant? =
        variants.firstOrNull { it.client.equals(client.trim(), ignoreCase = true) }

    fun variantOrDefault(client: String?): ClientVariant = client?.let(::variantForClient) ?: default

    companion object {
        /** Lê a seção `productLine` do application.yaml. */
        fun fromConfig(config: ApplicationConfig): ProductLine {
            val section = config.config("productLine")
            val variants = section.configList("clients").map { client ->
                ClientVariant(
                    client = client.property("name").getString(),
                    plan = Plan.fromKey(client.property("plan").getString()),
                    exclusive = (client.propertyOrNull("exclusive")?.getList() ?: emptyList())
                        .map(Feature::fromKey).toSet(),
                )
            }
            val defaultClient = section.propertyOrNull("defaultClient")?.getString() ?: "Cliente padrão"
            // A variante padrão usa o mesmo plano e as mesmas exclusivas do cliente de mesmo nome.
            val default = variants.firstOrNull { it.client == defaultClient }
                ?: ClientVariant(defaultClient, Plan.fromKey(section.property("defaultPlan").getString()))
            return ProductLine(default, variants)
        }
    }
}
