# Grafana dashboard specification

Optional Prometheus scrape: protected `/actuator/prometheus` on Host B using a revocable admin credential from a protected secret store. Do not put credentials in dashboard JSON or labels.

Panels: registered agents (`modsync_agents_registered`), registered devices (`modsync_devices_registered`, not a live connection count), stale inventories (`modsync_inventory_stale`), oldest inventory age (`modsync_inventory_oldest_age_seconds`), Spring HTTP latency/errors, Hikari active/pending connections, JVM RSS/CPU and database health. Display published revisions/agent heartbeat timestamps from authenticated APIs where not yet exported as per-resource gauges.

Alert when backend readiness fails, inventories become stale or database pool waits rise. Current metrics are a baseline; dedicated Modrinth/download/synchronization outcome counters and measured remote-agent resource metrics remain outstanding. Never add full local paths or bearer tokens as labels.
