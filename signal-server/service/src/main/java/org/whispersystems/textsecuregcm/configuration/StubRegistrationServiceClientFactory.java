/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dropwizard.core.setup.Environment;
import java.util.concurrent.ScheduledExecutorService;
import org.whispersystems.textsecuregcm.registration.CompanyVerificationManager;
import org.whispersystems.textsecuregcm.registration.RegistrationServiceClient;
import org.whispersystems.textsecuregcm.registration.StubRegistrationServiceClient;

@JsonTypeName("stub")
public record StubRegistrationServiceClientFactory(
    @JsonProperty("fixedCode") String fixedCode,
    @JsonProperty("allowlistOnly") Boolean allowlistOnly,
    @JsonProperty("fixedCodeEnabled") Boolean fixedCodeEnabled
) implements RegistrationServiceClientFactory {

  @Override
  public RegistrationServiceClient build(
      final Environment environment,
      final ScheduledExecutorService identityRefreshExecutor) {
    // When built standalone without Jdbi, creates an in-memory verification client
    final boolean allowlist = allowlistOnly != null ? allowlistOnly : false;
    final boolean fixedEnabled = fixedCodeEnabled != null ? fixedCodeEnabled : true;
    final String code = fixedCode != null ? fixedCode : "123456";

    // Jdbi is injected directly in WhisperServerService when database is enabled
    return new StubRegistrationServiceClient(
        new CompanyVerificationManager(
            org.jdbi.v3.core.Jdbi.create("jdbc:h2:mem:stub_reg;DB_CLOSE_DELAY=-1"),
            allowlist,
            fixedEnabled,
            code
        )
    );
  }
}
