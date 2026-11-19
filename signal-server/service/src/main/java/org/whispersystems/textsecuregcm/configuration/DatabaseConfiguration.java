/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

public class DatabaseConfiguration {

  @JsonProperty
  private boolean enabled = true;

  @NotNull
  @NotEmpty
  @JsonProperty
  private String jdbcUrl = "jdbc:mysql://127.0.0.1:3306/signal?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8mb4&rewriteBatchedStatements=true";

  @NotNull
  @NotEmpty
  @JsonProperty
  private String user = "signal";

  @NotNull
  @JsonProperty
  private String password = "signal_dev_secret";

  @JsonProperty
  private int maxPoolSize = 20;

  @JsonProperty
  private int minIdle = 5;

  @JsonProperty
  private Duration connectionTimeout = Duration.ofSeconds(30);

  @JsonProperty
  private Duration idleTimeout = Duration.ofMinutes(10);

  @JsonProperty
  private boolean runFlyway = true;

  @JsonProperty
  private boolean registrationAllowlistOnly = true;

  @JsonProperty
  private boolean fixedVerificationCodeEnabled = false;

  @JsonProperty
  private String fixedVerificationCode = "123456";

  @JsonProperty
  private String adminApiKey = "signal-admin-secret";

  @JsonProperty
  private boolean attachmentUploadAllowlist = false;

  @JsonProperty
  private String twilioAccountSid;

  @JsonProperty
  private String twilioAuthToken;

  @JsonProperty
  private String twilioFromNumber;

  @JsonProperty
  private String smsWebhookUrl;

  public boolean isEnabled() {
    return enabled;
  }

  public String getJdbcUrl() {
    return jdbcUrl;
  }

  public String getUser() {
    return user;
  }

  public String getPassword() {
    return password;
  }

  public int getMaxPoolSize() {
    return maxPoolSize;
  }

  public int getMinIdle() {
    return minIdle;
  }

  public Duration getConnectionTimeout() {
    return connectionTimeout;
  }

  public Duration getIdleTimeout() {
    return idleTimeout;
  }

  public boolean isRunFlyway() {
    return runFlyway;
  }

  public boolean isRegistrationAllowlistOnly() {
    return registrationAllowlistOnly;
  }

  public boolean isFixedVerificationCodeEnabled() {
    return fixedVerificationCodeEnabled;
  }

  public String getFixedVerificationCode() {
    return fixedVerificationCode;
  }

  public String getAdminApiKey() {
    return adminApiKey;
  }

  public boolean isAttachmentUploadAllowlist() {
    return attachmentUploadAllowlist;
  }

  public String getTwilioAccountSid() {
    return twilioAccountSid;
  }

  public String getTwilioAuthToken() {
    return twilioAuthToken;
  }

  public String getTwilioFromNumber() {
    return twilioFromNumber;
  }

  public String getSmsWebhookUrl() {
    return smsWebhookUrl;
  }
}
