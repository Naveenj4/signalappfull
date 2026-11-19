/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.push;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.DeliveryPriority;
import com.eatthepath.pushy.apns.PushType;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.google.common.annotations.VisibleForTesting;
import io.dropwizard.lifecycle.Managed;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.ApnConfiguration;

public class APNSender implements Managed, PushNotificationSender {

  private static final Logger logger = LoggerFactory.getLogger(APNSender.class);

  private final ExecutorService executor;
  private final Clock clock;

  private final String bundleId;
  private final ApnsClient apnsClient;

  @VisibleForTesting
  static final String APN_NSE_NOTIFICATION_PAYLOAD = new SimpleApnsPayloadBuilder()
      .setMutableContent(true)
      .setLocalizedAlertMessage("APN_Message")
      .build();

  @VisibleForTesting
  static final String APN_BACKGROUND_PAYLOAD = new SimpleApnsPayloadBuilder()
      .setContentAvailable(true)
      .build();

  // See https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns
  @VisibleForTesting
  static final Duration DEFAULT_TTL = Duration.ofDays(30);

  private static final String APNS_CA_FILENAME = "apns-certificates.pem";

  private static final Timer SEND_NOTIFICATION_TIMER = Metrics.timer(name(APNSender.class, "sendNotification"));

  public APNSender(final ExecutorService executor, final Clock clock, final ApnConfiguration configuration)
      throws IOException, NoSuchAlgorithmException, InvalidKeyException
  {
    this.executor = executor;
    this.clock = clock;
    this.bundleId = configuration.bundleId();
    ApnsClient client = null;
    final String signingKey = configuration.signingKey() != null ? configuration.signingKey().value() : null;
    if (signingKey != null && !signingKey.isBlank() && !signingKey.contains("dummy")) {
      try {
        client = new ApnsClientBuilder().setSigningKey(
                ApnsSigningKey.loadFromInputStream(new ByteArrayInputStream(signingKey.getBytes()),
                    configuration.teamId().value(), configuration.keyId().value()))
            .setTrustedServerCertificateChain(getClass().getResourceAsStream(APNS_CA_FILENAME))
            .setApnsServer(configuration.sandbox() ? ApnsClientBuilder.DEVELOPMENT_APNS_HOST : ApnsClientBuilder.PRODUCTION_APNS_HOST)
            .build();
        logger.info("APNs client initialized successfully.");
      } catch (Exception e) {
        logger.warn("Failed to initialize APNs client: {}. APNs push notifications will be disabled.", e.getMessage());
      }
    } else {
      logger.info("APNs signing key is dummy or not configured. APNs push notifications disabled (clients will use WebSocket).");
    }
    this.apnsClient = client;
  }

  @VisibleForTesting
  public APNSender(final ExecutorService executor, final ApnsClient apnsClient, final String bundleId, final Clock clock) {
    this.executor = executor;
    this.clock = clock;
    this.apnsClient = apnsClient;
    this.bundleId = bundleId;
  }

  @Override
  public CompletableFuture<SendPushNotificationResult> sendNotification(final PushNotification notification) {
    if (apnsClient == null) {
      return CompletableFuture.completedFuture(
          new SendPushNotificationResult(false, Optional.of("APNS_DISABLED"), false, Optional.empty()));
    }
    final String payload = switch (notification.notificationType()) {
      case NOTIFICATION -> notification.urgent() ? APN_NSE_NOTIFICATION_PAYLOAD : APN_BACKGROUND_PAYLOAD;

      case ATTEMPT_LOGIN_NOTIFICATION_HIGH_PRIORITY -> new SimpleApnsPayloadBuilder()
          .setMutableContent(true)
          .setLocalizedAlertMessage("APN_Message")
          .addCustomProperty("attemptLoginContext", notification.data())
          .build();

      case CHALLENGE -> new SimpleApnsPayloadBuilder()
          .setContentAvailable(true)
          .addCustomProperty("challenge", notification.data())
          .build();

      case RATE_LIMIT_CHALLENGE -> new SimpleApnsPayloadBuilder()
          .setContentAvailable(true)
          .addCustomProperty("rateLimitChallenge", notification.data())
          .build();

      case VERIFICATION_CODE_REQUESTED -> {
        if (!(notification.data() instanceof VerificationCodeRequestData(long timestamp))) {
          throw new IllegalArgumentException("Notification did not have VerificationCodeRequestData");
        }

        yield new SimpleApnsPayloadBuilder()
          .setMutableContent(true)
          .setLocalizedAlertMessage("APN_Message")
          .addCustomProperty("verificationCodeRequested", Map.of("timestamp", timestamp))
          .build();
      }
    };

    final PushType pushType = switch (notification.notificationType()) {
      case NOTIFICATION -> notification.urgent() ? PushType.ALERT : PushType.BACKGROUND;
      case ATTEMPT_LOGIN_NOTIFICATION_HIGH_PRIORITY, VERIFICATION_CODE_REQUESTED -> PushType.ALERT;
      case CHALLENGE, RATE_LIMIT_CHALLENGE -> PushType.BACKGROUND;
    };

    final DeliveryPriority deliveryPriority;

    if (pushType == PushType.BACKGROUND) {
      deliveryPriority = DeliveryPriority.CONSERVE_POWER;
    } else {
      deliveryPriority = notification.urgent() ? DeliveryPriority.IMMEDIATE : DeliveryPriority.CONSERVE_POWER;
    }

    final String collapseId =
        (notification.notificationType() == PushNotification.NotificationType.NOTIFICATION && notification.urgent())
            ? "incoming-message" : null;

    final Instant start = Instant.now();

    return apnsClient.sendNotification(new SimpleApnsPushNotification(notification.deviceToken(),
        bundleId,
        payload,
        clock.instant().plus(notification.ttl() != null ? notification.ttl() : DEFAULT_TTL),
        deliveryPriority,
        pushType,
        collapseId))
        .whenComplete((response, throwable) -> {
          // Note that we deliberately run this small bit of non-blocking measurement on the "send notification" thread
          // to avoid any measurement noise that could arise from dispatching to another executor and waiting in its
          // queue
          SEND_NOTIFICATION_TIMER.record(Duration.between(start, Instant.now()));
        })
        .thenApplyAsync(response -> {
          final boolean accepted;
          final Optional<String> rejectionReason;
          final boolean unregistered;

          if (response.isAccepted()) {
            accepted = true;
            rejectionReason = Optional.empty();
            unregistered = false;
          } else {
            accepted = false;
            rejectionReason = response.getRejectionReason();
            unregistered = response.getRejectionReason().map(reason -> "Unregistered".equals(reason) || "BadDeviceToken".equals(reason) || "ExpiredToken".equals(reason))
                .orElse(false);
          }

          return new SendPushNotificationResult(accepted, rejectionReason, unregistered, response.getTokenInvalidationTimestamp());
        }, executor);
  }

  @Override
  public void start() {
  }

  @Override
  public void stop() {
    if (this.apnsClient != null) {
      this.apnsClient.close().join();
    }
  }
}
