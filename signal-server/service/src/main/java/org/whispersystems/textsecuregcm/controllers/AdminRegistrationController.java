/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.registration.CompanyVerificationManager;
import org.whispersystems.textsecuregcm.registration.CompanyVerificationManager.AllowedRegistration;

/**
 * Admin management REST endpoint for company registration allowlists and verification codes.
 * Accessible at `/v1/admin/registrations`.
 */
@Path("/v1/admin/registrations")
@Tag(name = "Admin Registration")
@Produces(MediaType.APPLICATION_JSON)
public class AdminRegistrationController {

  private static final Logger logger = LoggerFactory.getLogger(AdminRegistrationController.class);

  public static final String ADMIN_KEY_HEADER = "X-Admin-Api-Key";

  public record AddRegistrationRequest(
      @NotBlank @JsonProperty("identifier") String identifier,
      @JsonProperty("displayName") String displayName,
      @JsonProperty("email") String email,
      @JsonProperty("verificationCode") String verificationCode,
      @JsonProperty("expiresAt") Long expiresAt,
      @JsonProperty("notes") String notes) {}

  public record SetCodeRequest(
      @JsonProperty("code") String code,
      @JsonProperty("validityMinutes") Integer validityMinutes) {}

  private final CompanyVerificationManager companyVerificationManager;
  private final String adminApiKey;

  public AdminRegistrationController(
      final CompanyVerificationManager companyVerificationManager,
      final String adminApiKey) {
    this.companyVerificationManager = Objects.requireNonNull(companyVerificationManager);
    this.adminApiKey = adminApiKey != null ? adminApiKey : "signal-admin-secret";
  }

  private void checkAuth(final String key) {
    if (key == null || !adminApiKey.equals(key.trim())) {
      logger.warn("Unauthorized attempt to access AdminRegistrationController");
      throw new ForbiddenException("Invalid admin API key");
    }
  }

  @GET
  @Operation(summary = "List all allowlisted registrations")
  public List<AllowedRegistration> listAll(@HeaderParam(ADMIN_KEY_HEADER) final String key) {
    checkAuth(key);
    return companyVerificationManager.listAllowed();
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Add or update an allowlisted phone number")
  public Response add(
      @HeaderParam(ADMIN_KEY_HEADER) final String key,
      @Valid final AddRegistrationRequest req) {
    checkAuth(key);

    companyVerificationManager.addAllowed(
        req.identifier(),
        req.displayName(),
        req.email(),
        req.verificationCode(),
        req.expiresAt(),
        req.notes());

    logger.info("Admin allowlisted staff number: {} (name: {})", req.identifier(), req.displayName());
    return Response.ok().entity(companyVerificationManager.listAllowed()).build();
  }

  @DELETE
  @Path("/{identifier}")
  @Operation(summary = "Remove a phone number from the allowlist")
  public Response remove(
      @HeaderParam(ADMIN_KEY_HEADER) final String key,
      @PathParam("identifier") final String identifier) {
    checkAuth(key);

    final boolean removed = companyVerificationManager.removeAllowed(identifier);
    logger.info("Admin removed number {}: success={}", identifier, removed);
    return Response.ok().build();
  }

  @PUT
  @Path("/{identifier}/disable")
  @Operation(summary = "Disable an allowlisted user")
  public Response disableUser(
      @HeaderParam(ADMIN_KEY_HEADER) final String key,
      @PathParam("identifier") final String identifier) {
    checkAuth(key);

    final boolean disabled = companyVerificationManager.disableUser(identifier);
    logger.info("Admin disabled user {}: success={}", identifier, disabled);
    return Response.ok().entity(java.util.Map.of("identifier", identifier, "enabled", false)).build();
  }

  @PUT
  @Path("/{identifier}/enable")
  @Operation(summary = "Enable an allowlisted user")
  public Response enableUser(
      @HeaderParam(ADMIN_KEY_HEADER) final String key,
      @PathParam("identifier") final String identifier) {
    checkAuth(key);

    final boolean enabled = companyVerificationManager.enableUser(identifier);
    logger.info("Admin enabled user {}: success={}", identifier, enabled);
    return Response.ok().entity(java.util.Map.of("identifier", identifier, "enabled", true)).build();
  }

  @POST
  @Path("/{identifier}/generate-code")
  @Operation(summary = "Generate a fresh 6-digit one-time verification code (default 24h validity)")
  public Response generateCode(
      @HeaderParam(ADMIN_KEY_HEADER) final String key,
      @PathParam("identifier") final String identifier) {
    checkAuth(key);

    // Default 24 hours (86400 seconds) as per Step B.2
    final String code = companyVerificationManager.generateVerificationCode(identifier, 86400);
    logger.info("Admin generated 24h OTP for {}: {}", identifier, code);
    return Response.ok().entity(java.util.Map.of(
        "identifier", identifier,
        "verificationCode", code,
        "expiresInHours", 24,
        "attemptsRemaining", 5)).build();
  }

  @GET
  @Path("/{identifier}/code")
  @Operation(summary = "Get the active verification code for a phone number")
  public Response getCode(
      @HeaderParam(ADMIN_KEY_HEADER) final String key,
      @PathParam("identifier") final String identifier) {
    checkAuth(key);

    return companyVerificationManager.getActiveVerificationCode(identifier)
        .map(code -> Response.ok().entity(java.util.Map.of("identifier", identifier, "verificationCode", code)).build())
        .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).entity(java.util.Map.of("error", "No active verification code found")).build());
  }

  @POST
  @Path("/{identifier}/code")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Set a custom one-time verification code for an allowlisted number")
  public Response setCode(
      @HeaderParam(ADMIN_KEY_HEADER) final String key,
      @PathParam("identifier") final String identifier,
      final SetCodeRequest req) {
    checkAuth(key);

    final int minutes = req.validityMinutes() != null ? req.validityMinutes() : 1440; // 24h default
    final long expiresAt = Instant.now().plusSeconds(minutes * 60L).getEpochSecond();
    final String code = req.code() != null ? req.code() : "123456";

    companyVerificationManager.setVerificationCode(identifier, code, expiresAt);
    logger.info("Admin custom verification code for {}: expires in {} min", identifier, minutes);
    return Response.ok().entity(java.util.Map.of("identifier", identifier, "verificationCode", code, "expiresAt", expiresAt)).build();
  }
}
