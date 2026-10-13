package com.deviante

import com.deviante.variability.ClientVariant
import com.deviante.variability.Plan
import com.deviante.variability.ProductLine
import io.ktor.server.config.ApplicationConfig

/**
 * Singleton (Reuso 03): a configuração do Core é lida uma única vez, de
 * application.yaml (que resolve as variáveis de ambiente), e todo módulo que
 * precisa dela reaproveita esta mesma instância. `object` do Kotlin garante uma
 * instância só, criada no primeiro acesso de forma segura entre threads.
 */
object AppConfig {
    lateinit var jdbcUrl: String; private set
    lateinit var dbUser: String; private set
    lateinit var dbPassword: String; private set
    lateinit var supabaseUrl: String; private set
    lateinit var supabaseAnonKey: String; private set
    lateinit var miningUrl: String; private set
    lateinit var productLine: ProductLine; private set

    @Volatile
    private var loaded = false

    /** Idempotente: a primeira chamada carrega; as seguintes não fazem nada. */
    @Synchronized
    fun init(config: ApplicationConfig) {
        if (loaded) return
        jdbcUrl = config.propertyOrNull("database.jdbcUrl")?.getString().orEmpty()
        dbUser = config.propertyOrNull("database.user")?.getString().orEmpty()
        dbPassword = config.propertyOrNull("database.password")?.getString().orEmpty()
        supabaseUrl = config.propertyOrNull("supabase.url")?.getString().orEmpty()
        supabaseAnonKey = config.propertyOrNull("supabase.anonKey")?.getString().orEmpty()
        miningUrl = config.propertyOrNull("mining.url")?.getString()
            ?: System.getenv("MINING_SERVICE_URL")
            ?: "http://localhost:8000"
        // Sem a seção productLine (ex.: testes), todo cliente recebe o plano completo.
        productLine = runCatching { ProductLine.fromConfig(config) }
            .getOrElse { ProductLine(ClientVariant("Cliente padrão", Plan.MANUTENCAO), emptyList()) }
        loaded = true
    }
}
