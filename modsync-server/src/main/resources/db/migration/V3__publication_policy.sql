ALTER TABLE minecraft_servers ADD COLUMN publication_policy VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
CREATE TABLE publication_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  server_id CHAR(36) NOT NULL,
  scan_id CHAR(36) NOT NULL,
  policy VARCHAR(20) NOT NULL,
  outcome VARCHAR(30) NOT NULL,
  revision BIGINT,
  detail VARCHAR(1000) NOT NULL,
  actor VARCHAR(100) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  INDEX publication_server_time(server_id, created_at),
  FOREIGN KEY(server_id) REFERENCES minecraft_servers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
