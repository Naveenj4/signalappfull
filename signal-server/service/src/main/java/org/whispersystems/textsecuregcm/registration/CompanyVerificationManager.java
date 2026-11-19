/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.registration;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.Util;

/**
 * Manages user registration verification codes and company allowlists.
 * Generates secure random 6-digit OTPs and verifies them in real time.
 */
public class CompanyVerificationManager {

  private static final Logger logger = LoggerFactory.getLogger(CompanyVerificationManager.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  public record AllowedRegistration(
      String identifier,
      String displayName,
      String email,
      String verificationCode,
      Long codeExpiresAt,
      int attemptsRemaining,
      boolean enabled,
      long addedAt,
      String notes) {}

  private final Jdbi jdbi;
  private final boolean allowlistOnly;
  private final boolean fixedCodeEnabled;
  private final String fixedVerificationCode;

  public CompanyVerificationManager(
      final Jdbi jdbi,
      final boolean allowlistOnly,
      final boolean fixedCodeEnabled,
      final String fixedVerificationCode) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
    this.allowlistOnly = allowlistOnly;
    this.fixedCodeEnabled = fixedCodeEnabled;
    this.fixedVerificationCode = fixedVerificationCode != null ? fixedVerificationCode : "123456";
  }

  public boolean isAllowlistOnly() {
    return allowlistOnly;
  }

  public boolean isFixedCodeEnabled() {
    return fixedCodeEnabled;
  }

  public String getFixedVerificationCode() {
    return fixedVerificationCode;
  }

  /**
   * Generates a cryptographically secure 6-digit OTP with the given validity period.
   */
  public String generateVerificationCode(final String phoneNumber, final int validitySeconds) {
    final String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    final long expiresAt = Instant.now().plusSeconds(validitySeconds).getEpochSecond();
    final long now = Instant.now().getEpochSecond();

    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT INTO allowed_registrations (identifier, verification_code, code_expires_at, added_at, notes) " +
            "VALUES (:id, :code, :exp, :added, 'otp-request') " +
            "ON DUPLICATE KEY UPDATE " +
            "verification_code = VALUES(verification_code), " +
            "code_expires_at = VALUES(code_expires_at)")
            .bind("id", phoneNumber)
            .bind("code", code)
            .bind("exp", expiresAt)
            .bind("added", now)
            .execute()
    );
    return code;
  }

  /**
   * Returns the currently active, unexpired verification code for a phone number if any.
   */
  public Optional<String> getActiveVerificationCode(final String phoneNumber) {
    final List<String> forms = Util.getAlternateForms(phoneNumber);
    final long now = Instant.now().getEpochSecond();

    return jdbi.withHandle(handle ->
        handle.createQuery(
            "SELECT verification_code FROM allowed_registrations " +
            "WHERE identifier IN (<forms>) AND verification_code IS NOT NULL AND (code_expires_at IS NULL OR code_expires_at >= :now) " +
            "ORDER BY added_at DESC LIMIT 1")
            .bindList("forms", forms)
            .bind("now", now)
            .mapTo(String.class)
            .findFirst()
    );
  }

  /**
   * Clears the verification code for a phone number.
   */
  public void clearVerificationCode(final String phoneNumber) {
    final List<String> forms = Util.getAlternateForms(phoneNumber);
    jdbi.useHandle(handle ->
        handle.createUpdate("UPDATE allowed_registrations SET verification_code = NULL, code_expires_at = NULL WHERE identifier IN (<forms>)")
            .bindList("forms", forms)
            .execute()
    );
  }

  /**
   * Checks whether the given phone number is allowed to register on this server.
   */
  public boolean isAllowed(final String phoneNumber) {
    if (!allowlistOnly) {
      return true;
    }

    final List<String> forms = Util.getAlternateForms(phoneNumber);
    return jdbi.withHandle(handle ->
        handle.createQuery("SELECT COUNT(*) FROM allowed_registrations WHERE identifier IN (<forms>) AND enabled = TRUE")
            .bindList("forms", forms)
            .mapTo(Integer.class)
            .one() > 0
    );
  }

  /**
   * Verifies the submitted verification code for the given phone number.
   * On success, burns the one-time code so it cannot be reused.
   * Enforces max 5 attempts per code.
   */
  public boolean verifyCode(final String phoneNumber, final String submittedCode) {
    if (submittedCode == null || submittedCode.isBlank()) {
      return false;
    }

    if (!isAllowed(phoneNumber)) {
      logger.warn("Registration rejected: {} is not on the company allowlist or is disabled", phoneNumber);
      return false;
    }

    // 1. Check fixed code if explicitly enabled in configuration
    if (fixedCodeEnabled && fixedVerificationCode.equals(submittedCode.trim())) {
      logger.info("Verification succeeded via fallback fixed code for {}", phoneNumber);
      return true;
    }

    // 2. Check dynamic or admin-issued one-time code
    final List<String> forms = Util.getAlternateForms(phoneNumber);
    final long now = Instant.now().getEpochSecond();

    return jdbi.inTransaction(handle -> {
      final List<AllowedRegistration> matches = handle.createQuery(
          "SELECT identifier, display_name, email, verification_code, code_expires_at, attempts_remaining, enabled, added_at, notes " +
          "FROM allowed_registrations WHERE identifier IN (<forms>) AND enabled = TRUE FOR UPDATE")
          .bindList("forms", forms)
          .map((rs, ctx) -> new AllowedRegistration(
              rs.getString("identifier"),
              rs.getString("display_name"),
              rs.getString("email"),
              rs.getString("verification_code"),
              rs.getObject("code_expires_at") != null ? rs.getLong("code_expires_at") : null,
              rs.getInt("attempts_remaining"),
              rs.getBoolean("enabled"),
              rs.getLong("added_at"),
              rs.getString("notes")
          ))
          .list();

      for (final AllowedRegistration reg : matches) {
        if (reg.verificationCode() == null) {
          continue;
        }

        if (reg.attemptsRemaining() <= 0) {
          logger.warn("Verification rejected: max attempts exceeded for {}", reg.identifier());
          return false;
        }

        if (reg.codeExpiresAt() != null && reg.codeExpiresAt() < now) {
          logger.warn("Verification code for {} has expired", reg.identifier());
          return false;
        }

        if (reg.verificationCode().equals(submittedCode.trim())) {
          logger.info("Verification succeeded via one-time code for {}", reg.identifier());
          // Burn the code after successful verification so it cannot be reused!
          handle.createUpdate(
              "UPDATE allowed_registrations SET verification_code = NULL, code_expires_at = NULL, attempts_remaining = 5 WHERE identifier = :id")
              .bind("id", reg.identifier())
              .execute();
          return true;
        } else {
          // Decrement attempts remaining
          final int remaining = Math.max(0, reg.attemptsRemaining() - 1);
          handle.createUpdate("UPDATE allowed_registrations SET attempts_remaining = :rem WHERE identifier = :id")
              .bind("rem", remaining)
              .bind("id", reg.identifier())
              .execute();
          logger.warn("Incorrect verification code for {}. Attempts remaining: {}", reg.identifier(), remaining);
        }
      }
      return false;
    });
  }

  /**
   * Adds or updates an allowlisted company staff number.
   */
  public void addAllowed(
      final String identifier,
      final String displayName,
      final String email,
      final String code,
      final Long expiresAtEpochSeconds,
      final String notes) {

    final long now = Instant.now().getEpochSecond();
    jdbi.useHandle(handle ->
        handle.createUpdate(
            "INSERT INTO allowed_registrations (identifier, display_name, email, verification_code, code_expires_at, attempts_remaining, enabled, added_at, notes) " +
            "VALUES (:id, :name, :email, :code, :exp, 5, TRUE, :added, :notes) " +
            "ON DUPLICATE KEY UPDATE " +
            "display_name = COALESCE(VALUES(display_name), display_name), " +
            "email = COALESCE(VALUES(email), email), " +
            "verification_code = VALUES(verification_code), " +
            "code_expires_at = VALUES(code_expires_at), " +
            "attempts_remaining = 5, " +
            "enabled = TRUE, " +
            "notes = VALUES(notes)")
            .bind("id", identifier)
            .bind("name", displayName)
            .bind("email", email)
            .bind("code", code)
            .bind("exp", expiresAtEpochSeconds)
            .bind("added", now)
            .bind("notes", notes)
            .execute()
    );
  }

  /**
   * Disables a user in the allowlist.
   */
  public boolean disableUser(final String identifier) {
    return setUserEnabled(identifier, false);
  }

  /**
   * Enables a user in the allowlist.
   */
  public boolean enableUser(final String identifier) {
    return setUserEnabled(identifier, true);
  }

  private boolean setUserEnabled(final String identifier, final boolean enabled) {
    final int rows = jdbi.withHandle(handle ->
        handle.createUpdate("UPDATE allowed_registrations SET enabled = :enabled WHERE identifier = :id")
            .bind("enabled", enabled)
            .bind("id", identifier)
            .execute()
    );
    return rows > 0;
  }

  /**
   * Removes a phone number from the allowlist.
   */
  public boolean removeAllowed(final String identifier) {
    final int rows = jdbi.withHandle(handle ->
        handle.createUpdate("DELETE FROM allowed_registrations WHERE identifier = :id")
            .bind("id", identifier)
            .execute()
    );
    return rows > 0;
  }

  /**
   * Lists all allowlisted staff numbers.
   */
  public List<AllowedRegistration> listAllowed() {
    return jdbi.withHandle(handle ->
        handle.createQuery("SELECT identifier, display_name, email, verification_code, code_expires_at, attempts_remaining, enabled, added_at, notes FROM allowed_registrations ORDER BY added_at DESC")
            .map((rs, ctx) -> new AllowedRegistration(
                rs.getString("identifier"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("verification_code"),
                rs.getObject("code_expires_at") != null ? rs.getLong("code_expires_at") : null,
                rs.getInt("attempts_remaining"),
                rs.getBoolean("enabled"),
                rs.getLong("added_at"),
                rs.getString("notes")
            ))
            .list()
    );
  }

  /**
   * Generates or sets a verification code for an existing allowlisted user.
   */
  public void setVerificationCode(final String identifier, final String code, final Long expiresAtEpochSeconds) {
    jdbi.useHandle(handle ->
        handle.createUpdate("UPDATE allowed_registrations SET verification_code = :code, code_expires_at = :exp, attempts_remaining = 5 WHERE identifier = :id")
            .bind("id", identifier)
            .bind("code", code)
            .bind("exp", expiresAtEpochSeconds)
            .execute()
    );
  }
}
