# Networking

Recommend a stable DNS name such as `https://modsync.example.net`. Support private DNS, LAN reverse proxies, public HTTPS or VPN DNS; none requires shared Docker networking between hosts. A and C need outbound TCP 443 to B; C also needs HTTPS access to the official Modrinth CDN and B to Modrinth APIs. MariaDB port 3306 stays private to Host B. No ModSync port is needed on A or C.

The supplied backend stack binds HTTP to Host B loopback port 8080 for a TLS reverse proxy on that host. Terminate trusted HTTPS there; if the proxy is elsewhere, configure backend TLS or protect that additional link. Do not expose plaintext backend HTTP to untrusted networks. Use proxy request limits, authentication throttling and access-log redaction. The application also enforces 2 MiB request bodies and authentication throttling.

LAN-only deployments can use an internal CA installed in the operating system/JVM trust store. Java distributions honor `javax.net.ssl.trustStore`; native support must be tested with the chosen GraalVM distribution. Never add an insecure verification switch. The Python administration helper supports `--ca-file` for a trusted CA.

WireGuard or Tailscale reduces public exposure and can provide stable private DNS; neither is mandatory. Firewall B to allow only intended client/agent networks where practical. Do not publish MariaDB and do not expose the agent.

For an address change, update DNS/reverse proxy routing and let clients reconnect. For a hostname change use `modsync config set server-url https://new-name.example.net`, then `modsync connection test`. Agents use setup with their existing state directory and explicit trusted UUID. Keep TLS verification enabled. Java DNS caches may delay reconnection; restart a one-shot process or wait for configured JVM DNS cache expiry rather than changing identities.

Backend downtime permits local scans, listings and doctor checks. Cached manifests display their retrieval time and age. Apply remains online-only. Backend reads return stored manifests while A is offline; no outage means an empty inventory.
