-- ============================================================
-- Signal-Server MySQL 8.0+ / MariaDB 10.6+ Schema (V1)
-- Engine: InnoDB, Charset: utf8mb4, Collation: utf8mb4_unicode_ci
-- Compatible with both MySQL 8.0+ and MariaDB 10.6+
-- ============================================================

-- 1. Accounts & Authentication
CREATE TABLE IF NOT EXISTS accounts (
    account_uuid          VARCHAR(36) NOT NULL,
    identity_key          VARBINARY(64),
    pni                   VARCHAR(36) UNIQUE,
    e164                  VARCHAR(32) UNIQUE,
    username_hash         VARBINARY(64) UNIQUE,
    username_link_uuid    VARCHAR(36) UNIQUE,
    version               INT NOT NULL DEFAULT 1,
    canonically_discoverable BOOLEAN NOT NULL DEFAULT TRUE,
    unregistered_at       BIGINT,
    created_at            BIGINT NOT NULL,
    last_seen_at          BIGINT NOT NULL,
    account_data          LONGBLOB,
    PRIMARY KEY (account_uuid),
    INDEX idx_accounts_pni (pni),
    INDEX idx_accounts_e164 (e164),
    INDEX idx_accounts_username_link (username_link_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Devices
CREATE TABLE IF NOT EXISTS devices (
    account_uuid          VARCHAR(36) NOT NULL,
    device_id             SMALLINT NOT NULL,
    name                  VARCHAR(255),
    auth_token_hash       VARCHAR(255) NOT NULL,
    salt                  VARCHAR(255) NOT NULL,
    gcm_id                TEXT,
    apn_id                TEXT,
    voip_apn_id           TEXT,
    fetches_messages      BOOLEAN DEFAULT TRUE,
    registration_id       INT,
    signed_prekey_id      INT,
    created_at            BIGINT NOT NULL,
    last_seen_at          BIGINT NOT NULL,
    device_data           LONGBLOB,
    PRIMARY KEY (account_uuid, device_id),
    CONSTRAINT fk_devices_account FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Device Linking Tokens
CREATE TABLE IF NOT EXISTS used_link_device_tokens (
    token_hash            VARBINARY(64) NOT NULL,
    created_at            BIGINT NOT NULL,
    PRIMARY KEY (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Deleted Accounts Tracking
CREATE TABLE IF NOT EXISTS deleted_accounts (
    account_pni           VARCHAR(64) NOT NULL,
    account_uuid          VARCHAR(36) NOT NULL,
    deleted_at            BIGINT NOT NULL,
    PRIMARY KEY (account_pni),
    INDEX idx_deleted_accounts_uuid (account_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. One-Time Curve25519 Prekeys
CREATE TABLE IF NOT EXISTS ec_one_time_prekeys (
    account_uuid          VARCHAR(36) NOT NULL,
    device_id             SMALLINT NOT NULL,
    key_id                INT NOT NULL,
    public_key            VARBINARY(64) NOT NULL,
    created_at            BIGINT NOT NULL,
    PRIMARY KEY (account_uuid, device_id, key_id),
    CONSTRAINT fk_ec_prekeys_account FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Paged Post-Quantum / Kyber Prekeys
CREATE TABLE IF NOT EXISTS paged_pq_prekeys (
    account_uuid          VARCHAR(36) NOT NULL,
    device_id             SMALLINT NOT NULL,
    batch_id              INT NOT NULL,
    keys_data             LONGBLOB NOT NULL,
    created_at            BIGINT NOT NULL,
    PRIMARY KEY (account_uuid, device_id, batch_id),
    CONSTRAINT fk_pq_prekeys_account FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. Repeated-Use Signed Prekeys (EC & PQ Last Resort)
CREATE TABLE IF NOT EXISTS signed_prekeys (
    account_uuid          VARCHAR(36) NOT NULL,
    device_id             SMALLINT NOT NULL,
    key_type              VARCHAR(16) NOT NULL, -- 'EC' or 'PQ_LAST_RESORT'
    key_id                INT NOT NULL,
    public_key            VARBINARY(64) NOT NULL,
    signature             VARBINARY(128),
    created_at            BIGINT NOT NULL,
    PRIMARY KEY (account_uuid, device_id, key_type),
    CONSTRAINT fk_signed_prekeys_account FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. User Profiles V1
CREATE TABLE IF NOT EXISTS profiles_v1 (
    account_uuid          VARCHAR(36) NOT NULL,
    version               VARCHAR(64) NOT NULL,
    name                  VARCHAR(255),
    about                 VARCHAR(255),
    avatar_path           VARCHAR(255),
    commitment            VARBINARY(64),
    profile_data          LONGBLOB,
    PRIMARY KEY (account_uuid, version),
    CONSTRAINT fk_profiles_v1_account FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. User Profiles V2
CREATE TABLE IF NOT EXISTS profiles_v2 (
    account_uuid          VARCHAR(36) NOT NULL,
    version               VARBINARY(64) NOT NULL,
    profile_data          LONGBLOB NOT NULL,
    PRIMARY KEY (account_uuid, version),
    CONSTRAINT fk_profiles_v2_account FOREIGN KEY (account_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. Profile Avatars
CREATE TABLE IF NOT EXISTS profile_avatars (
    identity              VARBINARY(64) NOT NULL,
    avatar_data           LONGBLOB NOT NULL,
    PRIMARY KEY (identity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. Persistent Offline Message Queue
CREATE TABLE IF NOT EXISTS persistent_messages (
    id                    BIGINT AUTO_INCREMENT NOT NULL,
    destination_uuid      VARCHAR(36) NOT NULL,
    destination_device_id SMALLINT NOT NULL,
    guid                  VARCHAR(36) NOT NULL,
    server_timestamp      BIGINT NOT NULL,
    client_timestamp      BIGINT NOT NULL,
    is_urgent             BOOLEAN DEFAULT FALSE,
    envelope_data         LONGBLOB NOT NULL,
    expires_at            BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_messages_guid (guid),
    INDEX idx_messages_queue (destination_uuid, destination_device_id, server_timestamp),
    INDEX idx_messages_expiry (expires_at),
    CONSTRAINT fk_messages_account FOREIGN KEY (destination_uuid) REFERENCES accounts(account_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. Verification Sessions
CREATE TABLE IF NOT EXISTS verification_sessions (
    session_id            VARCHAR(64) NOT NULL,
    e164                  VARCHAR(32) NOT NULL,
    verified              BOOLEAN DEFAULT FALSE,
    session_data          BLOB,
    expires_at            BIGINT NOT NULL,
    PRIMARY KEY (session_id),
    INDEX idx_verification_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. Phone Number Recovery Passwords
CREATE TABLE IF NOT EXISTS phone_number_recovery_passwords (
    pni                   VARCHAR(64) NOT NULL,
    password_hash         VARBINARY(64) NOT NULL,
    expires_at            BIGINT NOT NULL,
    PRIMARY KEY (pni)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. Remote Config
CREATE TABLE IF NOT EXISTS remote_configs (
    name                  VARCHAR(128) NOT NULL,
    config_value          TEXT NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. Client Releases
CREATE TABLE IF NOT EXISTS client_releases (
    platform              VARCHAR(32) NOT NULL,
    version               VARCHAR(32) NOT NULL,
    PRIMARY KEY (platform, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16. Push Challenges
CREATE TABLE IF NOT EXISTS push_challenges (
    token                 VARCHAR(64) NOT NULL,
    created_at            BIGINT NOT NULL,
    PRIMARY KEY (token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. Report Messages
CREATE TABLE IF NOT EXISTS report_messages (
    token                 VARCHAR(64) NOT NULL,
    report_data           LONGBLOB NOT NULL,
    expires_at            BIGINT NOT NULL,
    PRIMARY KEY (token),
    INDEX idx_report_messages_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18. Change Number Waiting Periods
CREATE TABLE IF NOT EXISTS change_number_waiting_periods (
    account_uuid          VARCHAR(36) NOT NULL,
    expires_at            BIGINT NOT NULL,
    PRIMARY KEY (account_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 19. Phone Number Identifiers Mapping
CREATE TABLE IF NOT EXISTS phone_number_identifiers (
    phone_number             VARCHAR(32) NOT NULL,
    phone_number_identifier  VARCHAR(36) NOT NULL,
    created_at               BIGINT NOT NULL,
    PRIMARY KEY (phone_number),
    INDEX idx_pni_lookup (phone_number_identifier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 20. Allowed Company Registrations & Pre-Generated Codes
CREATE TABLE IF NOT EXISTS allowed_registrations (
    identifier            VARCHAR(64) NOT NULL, -- phone number (E.164) or employee ID
    verification_code     VARCHAR(32),
    code_expires_at       BIGINT,
    added_at              BIGINT NOT NULL,
    notes                 VARCHAR(255),
    PRIMARY KEY (identifier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
