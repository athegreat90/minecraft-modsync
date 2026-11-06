CREATE TABLE client_instance_inventory (
  instance_id CHAR(36) PRIMARY KEY,
  server_id CHAR(36),
  minecraft_version VARCHAR(80) NOT NULL,
  loader VARCHAR(80) NOT NULL,
  loader_version VARCHAR(80),
  sync_policy VARCHAR(30) NOT NULL,
  activity VARCHAR(30) NOT NULL,
  applied_revision BIGINT,
  sequence BIGINT NOT NULL,
  mod_count INT NOT NULL,
  verified_at TIMESTAMP(6) NOT NULL,
  INDEX client_inventory_server(server_id),
  FOREIGN KEY(instance_id) REFERENCES client_instances(id),
  FOREIGN KEY(server_id) REFERENCES minecraft_servers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE client_instance_mods (
  instance_id CHAR(36) NOT NULL,
  sha512 CHAR(128) NOT NULL,
  file_name VARCHAR(240) NOT NULL,
  project_id VARCHAR(128),
  version_id VARCHAR(128),
  managed BOOLEAN NOT NULL,
  PRIMARY KEY(instance_id, sha512),
  INDEX client_mods_hash(sha512),
  FOREIGN KEY(instance_id) REFERENCES client_instances(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
