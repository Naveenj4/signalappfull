-- ==============================================================================
-- Company Registration Allowlist Management Script
-- Database: signal
-- Table: allowed_registrations
-- ==============================================================================

USE `signal`;

-- 1. View all currently allowlisted staff numbers
SELECT 
    identifier,
    verification_code,
    FROM_UNIXTIME(code_expires_at) AS code_expires,
    FROM_UNIXTIME(added_at) AS added,
    notes
FROM allowed_registrations
ORDER BY added_at DESC;

-- 2. Allowlist a staff member with fixed test code (123456)
-- Example: Allow Alice Smith (+15551234567)
INSERT INTO allowed_registrations (identifier, verification_code, code_expires_at, added_at, notes)
VALUES ('+15551234567', '123456', UNIX_TIMESTAMP() + 86400 * 30, UNIX_TIMESTAMP(), 'Alice Smith - Engineering')
ON DUPLICATE KEY UPDATE 
    verification_code = VALUES(verification_code),
    code_expires_at = VALUES(code_expires_at),
    notes = VALUES(notes);

-- 3. Allowlist a staff member with a custom one-time code (e.g. 789456 valid for 24 hours)
-- Example: Allow Bob Jones (+15559876543)
INSERT INTO allowed_registrations (identifier, verification_code, code_expires_at, added_at, notes)
VALUES ('+15559876543', '789456', UNIX_TIMESTAMP() + 86400, UNIX_TIMESTAMP(), 'Bob Jones - Operations')
ON DUPLICATE KEY UPDATE 
    verification_code = VALUES(verification_code),
    code_expires_at = VALUES(code_expires_at),
    notes = VALUES(notes);

-- 4. Remove a staff member from the allowlist (revokes registration permission)
-- Example:
-- DELETE FROM allowed_registrations WHERE identifier = '+15551234567';
