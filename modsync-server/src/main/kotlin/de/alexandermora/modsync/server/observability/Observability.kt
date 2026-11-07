package de.alexandermora.modsync.server.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes inventory health gauges refreshed once a minute.
 *
 * Metrics are counts and ages only and contain no identifiers.
 *
 * @property db JDBC access for the count queries.
 * @param registry Meter registry that receives the gauges.
 */
@Component
class Observability(private val db: JdbcTemplate, registry: MeterRegistry) {
    private val agents = AtomicLong()
    private val devices = AtomicLong()
    private val stale = AtomicLong()
    private val age = AtomicLong()

    init {
        registry.gauge("modsync.agents.registered", agents)
        registry.gauge("modsync.devices.registered", devices)
        registry.gauge("modsync.inventory.stale", stale)
        registry.gauge("modsync.inventory.oldest.age.seconds", age)
    }

    /** Recomputes the gauge values from the database. */
    @Scheduled(fixedDelay = 60000)
    fun refresh() {
        agents.set(count("SELECT COUNT(*) FROM scanner_agents WHERE revoked=FALSE"))
        devices.set(count("SELECT COUNT(*) FROM client_devices WHERE revoked=FALSE"))
        stale.set(count("SELECT COUNT(*) FROM minecraft_servers WHERE inventory_at IS NULL OR inventory_at < CURRENT_TIMESTAMP - INTERVAL 15 MINUTE"))
        age.set(count("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,inventory_at,CURRENT_TIMESTAMP)),0) FROM minecraft_servers"))
    }

    /**
     * Runs a single-value count query.
     *
     * @param sql Query returning one number.
     * @return The value, or 0 when the query returns null.
     */
    private fun count(sql: String): Long = db.queryForObject(sql, Long::class.java) ?: 0
}
