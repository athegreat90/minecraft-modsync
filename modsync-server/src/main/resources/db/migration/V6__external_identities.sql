CREATE TABLE external_identities (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  linked_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY external_identity_subject (provider, subject),
  UNIQUE KEY external_identity_user_provider (user_id, provider),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
