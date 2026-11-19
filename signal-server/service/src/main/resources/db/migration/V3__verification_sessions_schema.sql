-- ============================================================
-- Flyway Migration V3: Fix verification_sessions schema
-- ============================================================
DROP TABLE IF EXISTS verification_sessions;
CREATE TABLE verification_sessions (
  session_key VARCHAR(128) NOT NULL PRIMARY KEY,
  serialized_data MEDIUMTEXT NOT NULL,
  expiration BIGINT NOT NULL,
  KEY idx_expiration (expiration)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
