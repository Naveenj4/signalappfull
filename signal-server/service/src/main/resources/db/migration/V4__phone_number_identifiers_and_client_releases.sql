-- ============================================================
-- Flyway Migration V4: Fix phone_number_identifiers and client_releases schemas
-- ============================================================
DROP TABLE IF EXISTS phone_number_identifiers;
CREATE TABLE phone_number_identifiers (
  phone_number VARCHAR(32) NOT NULL PRIMARY KEY,
  pni VARBINARY(16) NOT NULL,
  KEY idx_pni (pni)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS client_releases;
CREATE TABLE client_releases (
  platform VARCHAR(32) NOT NULL,
  version VARCHAR(32) NOT NULL,
  release_timestamp BIGINT NOT NULL,
  expiration BIGINT NOT NULL,
  PRIMARY KEY (platform, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO client_releases (platform, version, release_timestamp, expiration)
VALUES ('ANDROID', '7.0.0', UNIX_TIMESTAMP() - 86400, UNIX_TIMESTAMP() + 315360000);
