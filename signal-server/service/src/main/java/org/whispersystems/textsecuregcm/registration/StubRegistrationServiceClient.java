/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.registration;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.controllers.RateLimitExceededException;
import org.whispersystems.textsecuregcm.controllers.VerificationSessionRateLimitExceededException;
import org.whispersystems.textsecuregcm.entities.RegistrationServiceSession;

/**
 * Stub registration client that replaces external Signal cloud registration microservices.
 * Supports fixed verification codes (e.g. 123456) and company allowlisted admin codes.
 */
public class StubRegistrationServiceClient extends RegistrationServiceClient {

  private static final Logger logger = LoggerFactory.getLogger(StubRegistrationServiceClient.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final CompanyVerificationManager companyVerificationManager;
  private final SmsSender smsSender;
  private final ConcurrentHashMap<ByteBuffer, RegistrationServiceSession> sessions = new ConcurrentHashMap<>();

  public StubRegistrationServiceClient(final CompanyVerificationManager companyVerificationManager) {
    this(companyVerificationManager, new SmsSender(null, null, null, null));
  }

  public StubRegistrationServiceClient(
      final CompanyVerificationManager companyVerificationManager,
      final SmsSender smsSender) {
    super();
    this.companyVerificationManager = Objects.requireNonNull(companyVerificationManager, "companyVerificationManager must not be null");
    this.smsSender = smsSender != null ? smsSender : new SmsSender(null, null, null, null);
  }

  public CompanyVerificationManager getCompanyVerificationManager() {
    return companyVerificationManager;
  }

  @Override
  public RegistrationServiceSession createRegistrationSession(
      final Phonenumber.PhoneNumber phoneNumber,
      final String sourceHost,
      final boolean accountExistsWithPhoneNumber,
      @Nullable final String clientMcc,
      @Nullable final String clientMnc,
      final Duration timeout) throws RateLimitExceededException {

    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);

    if (!companyVerificationManager.isAllowed(e164)) {
      logger.warn("Rejecting registration session for non-allowlisted number: {}", e164);
      throw new RateLimitExceededException(Duration.ofMinutes(15));
    }

    final byte[] sessionId = new byte[16];
    SECURE_RANDOM.nextBytes(sessionId);

    final long expiration = Instant.now().plusSeconds(3600).getEpochSecond();
    final RegistrationServiceSession session = new RegistrationServiceSession(
        sessionId,
        e164,
        false,
        0L,
        null,
        0L,
        expiration);

    sessions.put(ByteBuffer.wrap(sessionId), session);
    logger.info("Created local registration verification session for {}", e164);
    return session;
  }

  @Override
  public RegistrationServiceSession sendVerificationCode(
      final byte[] sessionId,
      final MessageTransport messageTransport,
      final ClientType clientType,
      @Nullable final String acceptLanguage,
      @Nullable final String senderOverride,
      final Duration timeout)
      throws VerificationSessionRateLimitExceededException, RegistrationServiceException {

    final RegistrationServiceSession existing = sessions.get(ByteBuffer.wrap(sessionId));
    if (existing == null) {
      throw new RegistrationServiceException(null);
    }

    final String verificationCode;
    if (companyVerificationManager.isFixedCodeEnabled()) {
      verificationCode = companyVerificationManager.getFixedVerificationCode();
      logger.info("Using configured fallback verification code for {}", existing.number());
    } else {
      // Production mode: generate a cryptographically secure random 6-digit OTP valid for 10 minutes
      verificationCode = companyVerificationManager.generateVerificationCode(existing.number(), 600);
    }

    // Dispatch OTP via Twilio SMS, custom webhook, or server console
    smsSender.sendVerificationCode(existing.number(), verificationCode);

    return existing;
  }

  @Override
  public RegistrationServiceSession checkVerificationCode(
      final byte[] sessionId,
      final String verificationCode,
      final Duration timeout) throws VerificationSessionRateLimitExceededException, RegistrationServiceException {

    final ByteBuffer key = ByteBuffer.wrap(sessionId);
    final RegistrationServiceSession existing = sessions.get(key);
    if (existing == null) {
      throw new RegistrationServiceException(null);
    }

    final boolean valid = companyVerificationManager.verifyCode(existing.number(), verificationCode);
    if (!valid) {
      logger.warn("Invalid verification code submitted for {}", existing.number());
      throw new RegistrationServiceException(existing);
    }

    final RegistrationServiceSession verifiedSession = new RegistrationServiceSession(
        existing.id(),
        existing.number(),
        true,
        null,
        null,
        null,
        existing.expiration()
    );

    sessions.put(key, verifiedSession);
    logger.info("Successfully verified registration session for {}", existing.number());
    return verifiedSession;
  }

  @Override
  public Optional<RegistrationServiceSession> getSession(final byte[] sessionId, final Duration timeout) {
    if (sessionId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(sessions.get(ByteBuffer.wrap(sessionId)));
  }
}
