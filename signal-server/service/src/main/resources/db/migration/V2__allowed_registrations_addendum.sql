-- V2__allowed_registrations_addendum.sql
-- Adds display_name, email, enabled, and attempts_remaining to allowed_registrations

ALTER TABLE allowed_registrations
    ADD COLUMN display_name VARCHAR(128) AFTER identifier,
    ADD COLUMN email VARCHAR(255) AFTER display_name,
    ADD COLUMN attempts_remaining INT DEFAULT 5 AFTER code_expires_at,
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER attempts_remaining;
