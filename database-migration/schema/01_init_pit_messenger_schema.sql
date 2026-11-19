-- ==============================================================================
-- PIT MESSENGER – DATABASE SCHEMA (MYSQL 8.0+ / MARIADB 10.6+)
-- Storage Engine: InnoDB
-- Charset: utf8mb4
-- Collation: utf8mb4_unicode_ci
-- Preserves: Signal Cryptographic Invariants, Optimistic Locks & Non-Blocking Prekeys
-- ==============================================================================

CREATE DATABASE IF NOT EXISTS `pit_messenger`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `pit_messenger`;

-- ------------------------------------------------------------------------------
-- 1. ACCOUNTS TABLE
-- Replaces DynamoDB Accounts table. Stores primary account identity & credentials.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `accounts` (
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `pni`                       VARCHAR(36)     DEFAULT NULL,
  `e164`                      VARCHAR(32)     DEFAULT NULL,
  `username_hash`             VARBINARY(64)   DEFAULT NULL,
  `username_link_uuid`        VARCHAR(36)     DEFAULT NULL,
  `identity_key`              VARBINARY(64)   DEFAULT NULL,
  `version`                   BIGINT UNSIGNED NOT NULL DEFAULT 1,
  `account_data`              LONGBLOB        NOT NULL,
  `canonically_discoverable`  BOOLEAN         NOT NULL DEFAULT TRUE,
  `registered_at`             BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at`                BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `unregistered_at`           BIGINT UNSIGNED DEFAULT NULL,
  `last_seen_at`              BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`account_uuid`),
  UNIQUE KEY `idx_accounts_e164` (`e164`),
  UNIQUE KEY `idx_accounts_pni` (`pni`),
  UNIQUE KEY `idx_accounts_username_hash` (`username_hash`),
  UNIQUE KEY `idx_accounts_username_link_uuid` (`username_link_uuid`),
  INDEX `idx_accounts_last_seen` (`last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 2. DEVICES TABLE
-- Normalized secondary device table. Linked to parent account via foreign key.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `devices` (
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `device_id`                 INT UNSIGNED    NOT NULL,
  `name`                      VARCHAR(255)    DEFAULT NULL,
  `auth_token_hash`           VARCHAR(255)    NOT NULL DEFAULT '',
  `gcm_id`                    VARCHAR(512)    DEFAULT NULL,
  `apn_id`                    VARCHAR(512)    DEFAULT NULL,
  `voip_apn_id`               TEXT            DEFAULT NULL,
  `fetches_messages`          BOOLEAN         DEFAULT TRUE,
  `registration_id`           INT             DEFAULT NULL,
  `signed_prekey_id`          INT             DEFAULT NULL,
  `voip_id`                   VARCHAR(512)    DEFAULT NULL,
  `auth_token`                VARBINARY(64)   DEFAULT NULL,
  `salt`                      VARCHAR(255)    NOT NULL DEFAULT '',
  `last_seen_at`              BIGINT UNSIGNED NOT NULL,
  `created_at`                BIGINT UNSIGNED NOT NULL,
  `device_data`               LONGBLOB        DEFAULT NULL,
  PRIMARY KEY (`account_uuid`, `device_id`),
  CONSTRAINT `fk_devices_account`
    FOREIGN KEY (`account_uuid`) REFERENCES `accounts` (`account_uuid`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 3. EC ONE-TIME PREKEYS TABLE
-- Consumed atomically via SELECT ... FOR UPDATE SKIP LOCKED.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ec_one_time_prekeys` (
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `device_id`                 INT UNSIGNED    NOT NULL,
  `key_id`                    BIGINT UNSIGNED NOT NULL,
  `public_key`                VARBINARY(128)  NOT NULL,
  `created_at`                BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`account_uuid`, `device_id`, `key_id`),
  CONSTRAINT `fk_ec_prekeys_account`
    FOREIGN KEY (`account_uuid`) REFERENCES `accounts` (`account_uuid`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 4. SIGNED PREKEYS TABLE (EC & POST-QUANTUM LAST RESORT)
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `signed_prekeys` (
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `device_id`                 INT UNSIGNED    NOT NULL,
  `key_type`                  VARCHAR(32)     NOT NULL, -- 'EC', 'PQ_LAST_RESORT'
  `key_id`                    BIGINT UNSIGNED NOT NULL,
  `public_key`                VARBINARY(512)  NOT NULL,
  `signature`                 VARBINARY(512)  NOT NULL,
  `created_at`                BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`account_uuid`, `device_id`, `key_type`),
  CONSTRAINT `fk_signed_prekeys_account`
    FOREIGN KEY (`account_uuid`) REFERENCES `accounts` (`account_uuid`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 5. PAGED PQ PREKEYS (KYBER POST-QUANTUM BATCHES)
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `paged_pq_prekeys` (
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `device_id`                 INT UNSIGNED    NOT NULL,
  `batch_id`                  BIGINT UNSIGNED NOT NULL,
  `pq_keys_payload`           LONGBLOB        NOT NULL,
  `created_at`                BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`account_uuid`, `device_id`, `batch_id`),
  CONSTRAINT `fk_pq_prekeys_account`
    FOREIGN KEY (`account_uuid`) REFERENCES `accounts` (`account_uuid`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 6. PERSISTENT OFFLINE MESSAGES TABLE
-- Stores undelivered encrypted envelopes for offline recipients.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `persistent_messages` (
  `id`                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `guid`                      VARCHAR(36)     NOT NULL,
  `destination_uuid`          VARCHAR(36)     NOT NULL,
  `destination_device_id`     INT UNSIGNED    NOT NULL,
  `server_timestamp`          BIGINT UNSIGNED NOT NULL,
  `expires_at`                BIGINT UNSIGNED NOT NULL,
  `envelope_data`             LONGBLOB        NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_messages_guid` (`guid`),
  INDEX `idx_messages_destination` (`destination_uuid`, `destination_device_id`, `server_timestamp`),
  INDEX `idx_messages_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 7. PROFILES V1 & V2 TABLES
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `profiles_v1` (
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `version`                   VARCHAR(64)     NOT NULL,
  `name`                      VARCHAR(255)    DEFAULT NULL,
  `avatar_path`               VARCHAR(512)    DEFAULT NULL,
  `updated_at`                BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`account_uuid`, `version`),
  CONSTRAINT `fk_profiles_v1_account`
    FOREIGN KEY (`account_uuid`) REFERENCES `accounts` (`account_uuid`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `profiles_v2` (
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `version`                   VARCHAR(64)     NOT NULL,
  `ciphertext`                VARBINARY(1024) NOT NULL,
  `updated_at`                BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`account_uuid`, `version`),
  CONSTRAINT `fk_profiles_v2_account`
    FOREIGN KEY (`account_uuid`) REFERENCES `accounts` (`account_uuid`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 8. VERIFICATION SESSIONS (CUSTOM OTP ENGINE)
-- Supports custom dynamic OTP delivery & expiration management.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `verification_sessions` (
  `session_id`                VARCHAR(64)     NOT NULL,
  `e164`                      VARCHAR(32)     NOT NULL,
  `code_hash`                 VARCHAR(128)    NOT NULL,
  `salt`                      VARBINARY(32)   NOT NULL,
  `attempts`                  INT UNSIGNED    NOT NULL DEFAULT 0,
  `max_attempts`              INT UNSIGNED    NOT NULL DEFAULT 5,
  `verified`                  BOOLEAN         NOT NULL DEFAULT FALSE,
  `verification_token`        VARCHAR(128)    DEFAULT NULL,
  `created_at`                BIGINT UNSIGNED NOT NULL,
  `expires_at`                BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`session_id`),
  INDEX `idx_verification_e164` (`e164`, `expires_at`),
  INDEX `idx_verification_token` (`verification_token`),
  INDEX `idx_verification_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 9. PHONE NUMBER IDENTIFIERS (E.164 <-> PNI DIRECTORY)
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `phone_number_identifiers` (
  `phone_number`              VARCHAR(32)     NOT NULL,
  `phone_number_identifier`   VARCHAR(36)     NOT NULL,
  PRIMARY KEY (`phone_number`),
  INDEX `idx_pni_lookup` (`phone_number_identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------------------
-- 10. USED LINK DEVICE TOKENS
-- Prevents replay attacks during multi-device desktop/web pairing.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `used_link_device_tokens` (
  `token_hash`                VARBINARY(64)   NOT NULL,
  `account_uuid`              VARCHAR(36)     NOT NULL,
  `used_at`                   BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`token_hash`),
  INDEX `idx_used_tokens_account` (`account_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
