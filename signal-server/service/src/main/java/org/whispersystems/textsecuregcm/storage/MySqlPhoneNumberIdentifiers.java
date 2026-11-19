/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.UUIDUtil;
import org.whispersystems.textsecuregcm.util.Util;

/**
 * MySQL/MariaDB implementation of PhoneNumberIdentifiers.
 * Stores phone number to PNI mappings in the `phone_number_identifiers` table.
 */
public class MySqlPhoneNumberIdentifiers extends PhoneNumberIdentifiers {

  private static final Logger logger = LoggerFactory.getLogger(MySqlPhoneNumberIdentifiers.class);

  private final Jdbi jdbi;

  public MySqlPhoneNumberIdentifiers(final Jdbi jdbi) {
    super();
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi must not be null");
  }

  @Override
  public CompletableFuture<UUID> getPhoneNumberIdentifier(final String phoneNumber) {
    final List<String> allPhoneNumberForms = Util.getAlternateForms(phoneNumber);
    final Map<String, UUID> existingAssociations = fetchPhoneNumbersSync(allPhoneNumberForms);

    if (existingAssociations.containsKey(phoneNumber)) {
      return CompletableFuture.completedFuture(existingAssociations.get(phoneNumber));
    }

    final UUID pniToUse = existingAssociations.values().stream()
        .findFirst()
        .orElseGet(UUID::randomUUID);

    savePniAssociations(allPhoneNumberForms, pniToUse);
    return CompletableFuture.completedFuture(pniToUse);
  }

  @Override
  public CompletableFuture<List<String>> getPhoneNumber(final UUID phoneNumberIdentifier) {
    final List<String> numbers = jdbi.withHandle(handle ->
        handle.createQuery("SELECT phone_number FROM phone_number_identifiers WHERE pni = :pni")
            .bind("pni", UUIDUtil.toBytes(phoneNumberIdentifier))
            .mapTo(String.class)
            .list()
    );
    return CompletableFuture.completedFuture(numbers);
  }

  @Override
  public CompletableFuture<UUID> setPni(
      final String originalPhoneNumber,
      final List<String> allPhoneNumberForms,
      final UUID pni) {
    savePniAssociations(allPhoneNumberForms, pni);
    return CompletableFuture.completedFuture(pni);
  }

  @Override
  CompletableFuture<Map<String, UUID>> fetchPhoneNumbers(final List<String> phoneNumbers) {
    return CompletableFuture.completedFuture(fetchPhoneNumbersSync(phoneNumbers));
  }

  @Override
  CompletableFuture<Void> regeneratePhoneNumberIdentifierMappings(final Account account) {
    return account.getNumber()
        .map(phoneNumber -> {
          final UUID pni = account.getPhoneNumberIdentifier()
              .orElseThrow(() -> new AssertionError("Account has a phone number, but no PNI"));
          return setPni(phoneNumber, Util.getAlternateForms(phoneNumber), pni).thenRun(Util.NOOP);
        })
        .orElseGet(() -> CompletableFuture.completedFuture(null));
  }

  private Map<String, UUID> fetchPhoneNumbersSync(final List<String> phoneNumbers) {
    if (phoneNumbers == null || phoneNumbers.isEmpty()) {
      return Map.of();
    }
    return jdbi.withHandle(handle -> {
      final Map<String, UUID> map = new HashMap<>();
      for (final String num : phoneNumbers) {
        handle.createQuery("SELECT pni FROM phone_number_identifiers WHERE phone_number = :phone_number")
            .bind("phone_number", num)
            .mapTo(byte[].class)
            .findOne()
            .ifPresent(bytes -> map.put(num, UUIDUtil.fromBytes(bytes)));
      }
      return map;
    });
  }

  private void savePniAssociations(final List<String> phoneNumbers, final UUID pni) {
    final byte[] pniBytes = UUIDUtil.toBytes(pni);
    jdbi.useTransaction(handle -> {
      for (final String num : phoneNumbers) {
        handle.createUpdate(
            "INSERT INTO phone_number_identifiers (phone_number, pni) " +
            "VALUES (:phone_number, :pni) " +
            "ON DUPLICATE KEY UPDATE pni = VALUES(pni)")
            .bind("phone_number", num)
            .bind("pni", pniBytes)
            .execute();
      }
    });
  }
}
