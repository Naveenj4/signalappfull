/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.registration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches verification OTPs via Twilio, custom HTTP Webhook, or secure console alert.
 */
public class SmsSender {

  private static final Logger logger = LoggerFactory.getLogger(SmsSender.class);
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private final String twilioAccountSid;
  private final String twilioAuthToken;
  private final String twilioFromNumber;
  private final String smsWebhookUrl;
  private final String twoFactorApiKey;
  private final String twoFactorBaseUrl;
  private final String twoFactorTemplate;

  public SmsSender(
      final String twilioAccountSid,
      final String twilioAuthToken,
      final String twilioFromNumber,
      final String smsWebhookUrl) {

    this.twoFactorApiKey = System.getenv("TWOFACTOR_API_KEY");
    final String configuredBaseUrl = System.getenv("TWOFACTOR_API_BASE_URL");
    this.twoFactorBaseUrl = (configuredBaseUrl != null && !configuredBaseUrl.isBlank())
        ? (configuredBaseUrl.endsWith("/") ? configuredBaseUrl : configuredBaseUrl + "/")
        : "https://2factor.in/API/V1/";
    this.twoFactorTemplate = System.getenv("TWOFACTOR_OTP_TEMPLATE");

    this.twilioAccountSid = (twilioAccountSid != null && !twilioAccountSid.isBlank())
        ? twilioAccountSid.trim()
        : System.getenv("TWILIO_ACCOUNT_SID");

    this.twilioAuthToken = (twilioAuthToken != null && !twilioAuthToken.isBlank())
        ? twilioAuthToken.trim()
        : System.getenv("TWILIO_AUTH_TOKEN");

    this.twilioFromNumber = (twilioFromNumber != null && !twilioFromNumber.isBlank())
        ? twilioFromNumber.trim()
        : System.getenv("TWILIO_FROM_NUMBER");

    this.smsWebhookUrl = (smsWebhookUrl != null && !smsWebhookUrl.isBlank())
        ? smsWebhookUrl.trim()
        : System.getenv("SMS_WEBHOOK_URL");
  }

  public void sendVerificationCode(final String destinationPhoneNumber, final String verificationCode) {
    final String messageBody = "Your PIT Messenger verification code is: " + verificationCode + ". Do not share this code with anyone.";

    // 1. Try 2Factor.in (Primary SMS Gateway for PIT Messenger)
    if (twoFactorApiKey != null && !twoFactorApiKey.isBlank()) {
      sendVia2Factor(destinationPhoneNumber, verificationCode);
      return;
    }

    // 2. Try Twilio if credentials are configured
    if (twilioAccountSid != null && !twilioAccountSid.isBlank()
        && twilioAuthToken != null && !twilioAuthToken.isBlank()
        && twilioFromNumber != null && !twilioFromNumber.isBlank()) {

      sendViaTwilio(destinationPhoneNumber, messageBody);
      return;
    }

    // 3. Try generic SMS Webhook if configured
    if (smsWebhookUrl != null && !smsWebhookUrl.isBlank()) {
      sendViaWebhook(destinationPhoneNumber, verificationCode, messageBody);
      return;
    }

    // 4. Fallback: Log prominently to server console/logs in real-time
    logger.warn("\n======================================================================\n" +
                "[PRODUCTION VERIFICATION CODE REQUEST]\n" +
                "To:   {}\n" +
                "Code: {}\n" +
                "Message: \"{}\"\n" +
                "Expires: 10 minutes\n" +
                "Notice: Configure TWOFACTOR_API_KEY to dispatch live cellular SMS.\n" +
                "======================================================================",
        destinationPhoneNumber, verificationCode, messageBody);
  }

  private void sendVia2Factor(final String to, final String code) {
    try {
      // 2Factor expects pure digits for Indian destinations (strip leading '+' or formatting)
      final String cleanPhone = to.replaceAll("[^0-9]", "");
      final String endpoint;
      if (twoFactorTemplate != null && !twoFactorTemplate.isBlank()) {
        endpoint = twoFactorBaseUrl + twoFactorApiKey.trim() + "/SMS/" + cleanPhone + "/" + code + "/" + URLEncoder.encode(twoFactorTemplate.trim(), StandardCharsets.UTF_8);
      } else {
        endpoint = twoFactorBaseUrl + twoFactorApiKey.trim() + "/SMS/" + cleanPhone + "/" + code;
      }

      logger.info("Dispatching live cellular SMS via 2Factor.in to {}", to);
      final HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build();

      HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
              if (response.body() != null && response.body().contains("\"Status\":\"Success\"")) {
                logger.info("2Factor.in SMS dispatched successfully to {}: {}", to, response.body());
              } else {
                logger.warn("2Factor.in SMS response warning for {}: {}", to, response.body());
              }
            } else {
              logger.error("2Factor.in SMS HTTP error for {} (code {}): {}", to, response.statusCode(), response.body());
            }
          })
          .exceptionally(ex -> {
            logger.error("Failed to deliver 2Factor.in SMS to {}: {}", to, ex.getMessage(), ex);
            return null;
          });
    } catch (final Exception e) {
      logger.error("Exception building 2Factor.in SMS request for {}: {}", to, e.getMessage(), e);
    }
  }

  private void sendViaTwilio(final String to, final String body) {
    try {
      final String url = "https://api.twilio.com/2010-04-01/Accounts/" + twilioAccountSid + "/Messages.json";
      final String form = "To=" + URLEncoder.encode(to, StandardCharsets.UTF_8)
          + "&From=" + URLEncoder.encode(twilioFromNumber, StandardCharsets.UTF_8)
          + "&Body=" + URLEncoder.encode(body, StandardCharsets.UTF_8);

      final String auth = Base64.getEncoder().encodeToString(
          (twilioAccountSid + ":" + twilioAuthToken).getBytes(StandardCharsets.UTF_8));

      final HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Authorization", "Basic " + auth)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .timeout(Duration.ofSeconds(15))
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build();

      HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
              logger.info("Twilio SMS sent successfully to {}", to);
            } else {
              logger.error("Twilio SMS failed (HTTP {}): {}", response.statusCode(), response.body());
            }
          })
          .exceptionally(ex -> {
            logger.error("Failed to connect to Twilio SMS API: {}", ex.getMessage());
            return null;
          });

    } catch (final Exception e) {
      logger.error("Error creating Twilio SMS request: {}", e.getMessage(), e);
    }
  }

  private void sendViaWebhook(final String to, final String code, final String message) {
    try {
      final String json = String.format(
          "{\"to\":\"%s\",\"code\":\"%s\",\"message\":\"%s\"}",
          to.replace("\"", "\\\""),
          code.replace("\"", "\\\""),
          message.replace("\"", "\\\""));

      final HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(smsWebhookUrl))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build();

      HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .thenAccept(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
              logger.info("Custom SMS Webhook dispatched successfully for {}", to);
            } else {
              logger.warn("Custom SMS Webhook returned HTTP {}: {}", response.statusCode(), response.body());
            }
          })
          .exceptionally(ex -> {
            logger.error("Custom SMS Webhook error: {}", ex.getMessage());
            return null;
          });

    } catch (final Exception e) {
      logger.error("Error creating custom SMS webhook request: {}", e.getMessage(), e);
    }
  }
}
