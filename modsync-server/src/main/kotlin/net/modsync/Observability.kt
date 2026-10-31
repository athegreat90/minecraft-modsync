package net.modsync
import org.springframework.stereotype.Component
import org.springframework.scheduling.annotation.Scheduled
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong
@Component
class Observability(private val store: Store, registry: MeterRegistry) {
    private val agents=AtomicLong(); private val devices=AtomicLong(); private val stale=AtomicLong(); private val age=AtomicLong()
    init {
        registry.gauge("modsync.agents.registered",agents)
        registry.gauge("modsync.devices.registered",devices)
        registry.gauge("modsync.inventory.stale",stale)
        registry.gauge("modsync.inventory.oldest.age.seconds",age)
    }
    @Scheduled(fixedDelay=60000) fun refresh() {
        agents.set(store.db.queryForObject("SELECT COUNT(*) FROM scanner_agents WHERE revoked=FALSE",Long::class.java) ?: 0)
        devices.set(store.db.queryForObject("SELECT COUNT(*) FROM client_devices WHERE revoked=FALSE",Long::class.java) ?: 0)
        stale.set(store.db.queryForObject("SELECT COUNT(*) FROM minecraft_servers WHERE inventory_at IS NULL OR inventory_at < CURRENT_TIMESTAMP - INTERVAL 15 MINUTE",Long::class.java) ?: 0)
        age.set(store.db.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,inventory_at,CURRENT_TIMESTAMP)),0) FROM minecraft_servers",Long::class.java) ?: 0)
    }
}
