/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.signal.libsignal.zkgroup.backups.BackupCredentialType;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialPresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

public class MySqlAccounts extends Accounts implements AccountsRepository {

  private static final Logger log = LoggerFactory.getLogger(MySqlAccounts.class);
  private static final Duration USERNAME_RECLAIM_TTL = Duration.ofDays(3);
  private static final int MAX_DEADLOCK_RETRIES = 3;

  private final Jdbi jdbi;
  private final Clock clock;

  public MySqlAccounts(final Clock clock, final Jdbi jdbi) {
    super(clock);
    this.clock = clock;
    this.jdbi = jdbi;
  }

  @Override
  public boolean create(final Account account, final List<TransactWriteItem> additionalWriteItems)
      throws AccountAlreadyExistsException {

    if (account.getNumber().isEmpty() || account.getPhoneNumberIdentifier().isEmpty()) {
      throw new IllegalArgumentException("Phone number and phone number identifier must be set");
    }

    final byte[] accountData = serializeAccount(account);
    final String aci = account.getAccountIdentifier().toString();
    final String e164 = account.getNumber().get();
    final String pni = account.getPhoneNumberIdentifier().get().toString();
    final byte[] identityKeyBytes = account.getAccountIdentityKey() != null
        ? account.getAccountIdentityKey().serialize()
        : null;

    try {
      executeWithRetry(() -> {
        jdbi.useTransaction(handle -> {
          // 1. Insert Account record
          handle.createUpdate("""
              INSERT INTO accounts (
                account_uuid, identity_key, pni, e164, username_hash, username_link_uuid,
                version, canonically_discoverable, unregistered_at, created_at, last_seen_at, account_data
              ) VALUES (
                :accountUuid, :identityKey, :pni, :e164, :usernameHash, :usernameLinkUuid,
                :version, :canonicallyDiscoverable, :unregisteredAt, :createdAt, :lastSeenAt, :accountData
              )
              """)
              .bind("accountUuid", aci)
              .bind("identityKey", identityKeyBytes)
              .bind("pni", pni)
              .bind("e164", e164)
              .bind("usernameHash", account.getUsernameHash().orElse(null))
              .bind("usernameLinkUuid", account.getUsernameLinkHandle() != null ? account.getUsernameLinkHandle().toString() : null)
              .bind("version", account.getVersion())
              .bind("canonicallyDiscoverable", account.isDiscoverableByPhoneNumber())
              .bind("unregisteredAt", (Long) null)
              .bind("createdAt", clock.millis())
              .bind("lastSeenAt", clock.millis())
              .bind("accountData", accountData)
              .execute();

          // 2. Remove any previously deleted account entry for this PNI
          handle.createUpdate("DELETE FROM deleted_accounts WHERE account_pni = :pni")
              .bind("pni", pni)
              .execute();

          // 3. Synchronize devices
          syncDevices(handle, account);
        });
        return true;
      });
      return true;
    } catch (final org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
      final Throwable cause = e.getCause();
      if (cause instanceof java.sql.SQLException sqlEx) {
        final int errorCode = sqlEx.getErrorCode();
        final String sqlState = sqlEx.getSQLState();
        if (errorCode == 1062 || "23000".equals(sqlState)) {
          final Optional<Account> existingByE164 = getByE164(e164);
          if (existingByE164.isPresent()) {
            throw new AccountAlreadyExistsException(existingByE164.get());
          }
          final Optional<Account> existingByPni = getByPhoneNumberIdentifier(account.getPhoneNumberIdentifier().get());
          if (existingByPni.isPresent()) {
            throw new AccountAlreadyExistsException(existingByPni.get());
          }
          final Optional<Account> existingByUuid = getByAccountIdentifier(account.getAccountIdentifier());
          if (existingByUuid.isPresent()) {
            throw new IllegalArgumentException("Account identifier present with different phone number");
          }
        }
      }
      throw e;
    }
 
  }

  @Override
  boolean create(final Account account,
      final ReceiptCredentialPresentation receiptCredentialPresentation,
      final byte[] accountRecoveryPasswordInRequest,
      final List<TransactWriteItem> additionalWriteItems)
      throws AccountAlreadyExistsException, ReceiptAlreadyRedeemedException, AccountCreationConditionException {
    return create(account, additionalWriteItems);
  }

  @Override
  public void update(final Account account) throws ContestedOptimisticLockException {
    final byte[] accountData = serializeAccount(account);
    final String aci = account.getAccountIdentifier().toString();
    final int currentVersion = account.getVersion();
    final byte[] identityKeyBytes = account.getAccountIdentityKey() != null
        ? account.getAccountIdentityKey().serialize()
        : null;

    final int rowsUpdated = executeWithRetry(() -> jdbi.inTransaction(handle -> {
      final int updated = handle.createUpdate("""
          UPDATE accounts
          SET identity_key = :identityKey,
              pni = :pni,
              e164 = :e164,
              username_hash = :usernameHash,
              username_link_uuid = :usernameLinkUuid,
              version = version + 1,
              canonically_discoverable = :canonicallyDiscoverable,
              last_seen_at = :lastSeenAt,
              account_data = :accountData
          WHERE account_uuid = :accountUuid AND version = :version
          """)
          .bind("identityKey", identityKeyBytes)
          .bind("pni", account.getPhoneNumberIdentifier().map(UUID::toString).orElse(null))
          .bind("e164", account.getNumber().orElse(null))
          .bind("usernameHash", account.getUsernameHash().orElse(null))
          .bind("usernameLinkUuid", account.getUsernameLinkHandle() != null ? account.getUsernameLinkHandle().toString() : null)
          .bind("canonicallyDiscoverable", account.isDiscoverableByPhoneNumber())
          .bind("lastSeenAt", clock.millis())
          .bind("accountData", accountData)
          .bind("accountUuid", aci)
          .bind("version", currentVersion)
          .execute();

      if (updated > 0) {
        syncDevices(handle, account);
      }
      return updated;
    }));

    if (rowsUpdated == 0) {
      if (accountExists(account.getAccountIdentifier())) {
        throw new ContestedOptimisticLockException();
      } else {
        throw new IllegalArgumentException("Account not found: " + aci);
      }
    }

    account.setVersion(currentVersion + 1);
  }

  @Override
  public void updateTransactionally(final Account account,
      final Collection<TransactWriteItem> additionalWriteItems)
      throws ContestedOptimisticLockException {
    update(account);
  }

  @Override
  public Optional<Account> getByAccountIdentifier(final UUID uuid) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_uuid, e164, pni, username_hash, username_link_uuid,
               version, canonically_discoverable, unregistered_at, created_at, last_seen_at, account_data
        FROM accounts
        WHERE account_uuid = :uuid
        """)
        .bind("uuid", uuid.toString())
        .map((rs, ctx) -> mapAccount(rs))
        .findFirst());
  }

  @Override
  public CompletableFuture<Optional<Account>> getByAccountIdentifierAsync(final UUID uuid) {
    return CompletableFuture.supplyAsync(() -> getByAccountIdentifier(uuid));
  }

  @Override
  public Optional<Account> getByE164(final String number) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_uuid, e164, pni, username_hash, username_link_uuid,
               version, canonically_discoverable, unregistered_at, created_at, last_seen_at, account_data
        FROM accounts
        WHERE e164 = :e164
        """)
        .bind("e164", number)
        .map((rs, ctx) -> mapAccount(rs))
        .findFirst());
  }

  @Override
  public CompletableFuture<Optional<Account>> getByE164Async(final String number) {
    return CompletableFuture.supplyAsync(() -> getByE164(number));
  }

  @Override
  public Optional<Account> getByPhoneNumberIdentifier(final UUID phoneNumberIdentifier) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_uuid, e164, pni, username_hash, username_link_uuid,
               version, canonically_discoverable, unregistered_at, created_at, last_seen_at, account_data
        FROM accounts
        WHERE pni = :pni
        """)
        .bind("pni", phoneNumberIdentifier.toString())
        .map((rs, ctx) -> mapAccount(rs))
        .findFirst());
  }

  @Override
  public CompletableFuture<Optional<Account>> getByPhoneNumberIdentifierAsync(final UUID phoneNumberIdentifier) {
    return CompletableFuture.supplyAsync(() -> getByPhoneNumberIdentifier(phoneNumberIdentifier));
  }

  @Override
  public CompletableFuture<Optional<Account>> getByUsernameHash(final byte[] usernameHash) {
    return CompletableFuture.supplyAsync(() -> jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_uuid, e164, pni, username_hash, username_link_uuid,
               version, canonically_discoverable, unregistered_at, created_at, last_seen_at, account_data
        FROM accounts
        WHERE username_hash = :usernameHash
        """)
        .bind("usernameHash", usernameHash)
        .map((rs, ctx) -> mapAccount(rs))
        .findFirst()));
  }

  @Override
  public CompletableFuture<Optional<Account>> getByUsernameLinkHandle(final UUID usernameLinkHandle) {
    return CompletableFuture.supplyAsync(() -> jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_uuid, e164, pni, username_hash, username_link_uuid,
               version, canonically_discoverable, unregistered_at, created_at, last_seen_at, account_data
        FROM accounts
        WHERE username_link_uuid = :linkUuid
        """)
        .bind("linkUuid", usernameLinkHandle.toString())
        .map((rs, ctx) -> mapAccount(rs))
        .findFirst()));
  }

  @Override
  public Optional<UUID> findRecentlyDeletedAccountIdentifier(final UUID phoneNumberIdentifier) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_uuid FROM deleted_accounts WHERE account_pni = :pni
        """)
        .bind("pni", phoneNumberIdentifier.toString())
        .mapTo(String.class)
        .findFirst()
        .map(UUID::fromString));
  }

  @Override
  public Optional<UUID> findRecentlyDeletedPhoneNumberIdentifier(final UUID uuid) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_pni FROM deleted_accounts WHERE account_uuid = :uuid
        """)
        .bind("uuid", uuid.toString())
        .mapTo(String.class)
        .findFirst()
        .map(UUID::fromString));
  }

  @Override
  public void delete(final UUID uuid, final List<TransactWriteItem> additionalWriteItems) {
    final Optional<Account> maybeAccount = getByAccountIdentifier(uuid);
    final String uuidString = uuid.toString();

    executeWithRetry(() -> {
      jdbi.useTransaction(handle -> {
        if (maybeAccount.isPresent() && maybeAccount.get().getPhoneNumberIdentifier().isPresent()) {
          final String pni = maybeAccount.get().getPhoneNumberIdentifier().get().toString();
          handle.createUpdate("""
              INSERT INTO deleted_accounts (account_pni, account_uuid, deleted_at)
              VALUES (:pni, :uuid, :deletedAt)
              ON DUPLICATE KEY UPDATE
                account_uuid = VALUES(account_uuid),
                deleted_at = VALUES(deleted_at)
              """)
              .bind("pni", pni)
              .bind("uuid", uuidString)
              .bind("deletedAt", clock.millis())
              .execute();
        }

        handle.createUpdate("DELETE FROM accounts WHERE account_uuid = :uuid")
            .bind("uuid", uuidString)
            .execute();
      });
      return null;
    });
  }

  @Override
  public boolean accountExists(final UUID uuid) {
    return jdbi.withHandle(handle -> handle.createQuery("""
        SELECT EXISTS (SELECT 1 FROM accounts WHERE account_uuid = :uuid)
        """)
        .bind("uuid", uuid.toString())
        .mapTo(Boolean.class)
        .one());
  }

  @Override
  public void changeNumber(final Account account, final String oldNumber, final UUID oldPni,
      final String newNumber, final UUID newPni, final boolean isDiscoverable,
      final List<TransactWriteItem> additionalWriteItems)
      throws ContestedOptimisticLockException {

    account.setNumber(newNumber, newPni);
    account.setDiscoverableByPhoneNumber(isDiscoverable);
    update(account);
  }

  @Override
  public void reserveUsernameHash(final Account account, final byte[] reservedUsernameHash,
      final Duration ttl) throws ContestedOptimisticLockException {
    account.setReservedUsernameHash(reservedUsernameHash);
    update(account);
  }

  @Override
  public void confirmUsernameHash(final Account account, final byte[] usernameHash,
      @Nullable final byte[] encryptedUsername) throws ContestedOptimisticLockException {
    account.setUsernameHash(usernameHash);
    account.setUsernameLinkDetails(account.getUsernameLinkHandle(), encryptedUsername);
    account.setReservedUsernameHash(null);
    update(account);
  }

  @Override
  public void clearUsernameHash(final Account account) throws ContestedOptimisticLockException {
    account.setUsernameHash(null);
    account.setUsernameLinkDetails(null, null);
    update(account);
  }

  @Override
  public CompletionStage<Void> reclaimAccount(final Account existingAccount, final Account accountToCreate,
      final Collection<TransactWriteItem> additionalWriteItems) {

    accountToCreate.setVersion(existingAccount.getVersion());
    accountToCreate.setBackupCredentialRequests(
        existingAccount.getBackupCredentialRequest(BackupCredentialType.MESSAGES).orElse(null),
        existingAccount.getBackupCredentialRequest(BackupCredentialType.MEDIA).orElse(null));
    accountToCreate.setBackupVoucher(existingAccount.getBackupVoucher());
    accountToCreate.setBadges(clock, existingAccount.getBadges());
    accountToCreate.setZkCredentialKey(existingAccount.getZkCredentialKey().orElse(null));
    accountToCreate.setZkCredentialKeyRotationId(existingAccount.getZkCredentialKeyRotationId());
    accountToCreate.setMfaKeys(new java.util.HashMap<>(existingAccount.getMfaKeys()));

    if (existingAccount.getReservedUsernameHash().isPresent() &&
        existingAccount.getUsernameLinkHandle() != null &&
        existingAccount.getUsernameHash().isEmpty() &&
        existingAccount.getEncryptedUsername().isEmpty()) {
      accountToCreate.setReservedUsernameHash(existingAccount.getReservedUsernameHash().get());
      accountToCreate.setUsernameLinkHandle(existingAccount.getUsernameLinkHandle());
    } else if (existingAccount.getUsernameHash().isPresent()) {
      accountToCreate.setReservedUsernameHash(existingAccount.getUsernameHash().get());
      accountToCreate.setUsernameLinkHandle(existingAccount.getUsernameLinkHandle());
    }

    try {
      update(accountToCreate);
      return CompletableFuture.completedFuture(null);
    } catch (final ContestedOptimisticLockException e) {
      return CompletableFuture.failedStage(e);
    }
  }

  @Override
  public Flux<Account> getAll(final int segments, final Scheduler scheduler) {
    final List<Account> allAccounts = jdbi.withHandle(handle -> handle.createQuery("""
        SELECT account_uuid, e164, pni, username_hash, username_link_uuid,
               version, canonically_discoverable, unregistered_at, created_at, last_seen_at, account_data
        FROM accounts
        """)
        .map((rs, ctx) -> mapAccount(rs))
        .list());
    return Flux.fromIterable(allAccounts).subscribeOn(scheduler);
  }

  @Override
  public TransactWriteItem buildTransactWriteItemForLinkDevice(final String linkDeviceToken,
      final Duration tokenTtl) {
    jdbi.useHandle(handle -> handle.createUpdate("""
        INSERT INTO used_link_device_tokens (token_hash, created_at)
        VALUES (:tokenHash, :createdAt)
        ON DUPLICATE KEY UPDATE created_at = VALUES(created_at)
        """)
        .bind("tokenHash", linkDeviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .bind("createdAt", clock.millis())
        .execute());

    return TransactWriteItem.builder().build();
  }

  private void syncDevices(final org.jdbi.v3.core.Handle handle, final Account account) {
    final String accountUuid = account.getAccountIdentifier().toString();
    final List<Byte> currentDeviceIds = new ArrayList<>();

    for (final Device device : account.getDevices()) {
      currentDeviceIds.add(device.getId());
      final String authTokenHash = device.getAuthTokenHash() != null ? device.getAuthTokenHash().hash() : "";
      final String salt = device.getAuthTokenHash() != null ? device.getAuthTokenHash().salt() : "";
      final String nameString = device.getName() != null ? new String(device.getName(), java.nio.charset.StandardCharsets.UTF_8) : null;

      handle.createUpdate("""
          INSERT INTO devices (
            account_uuid, device_id, name, auth_token_hash, salt, gcm_id, apn_id, fetches_messages,
            registration_id, signed_prekey_id, created_at, last_seen_at
          ) VALUES (
            :accountUuid, :deviceId, :name, :authTokenHash, :salt, :gcmId, :apnId, :fetchesMessages,
            :registrationId, :signedPrekeyId, :createdAt, :lastSeenAt
          )
          ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            auth_token_hash = VALUES(auth_token_hash),
            salt = VALUES(salt),
            gcm_id = VALUES(gcm_id),
            apn_id = VALUES(apn_id),
            fetches_messages = VALUES(fetches_messages),
            registration_id = VALUES(registration_id),
            signed_prekey_id = VALUES(signed_prekey_id),
            last_seen_at = VALUES(last_seen_at)
          """)
          .bind("accountUuid", accountUuid)
          .bind("deviceId", device.getId())
          .bind("name", nameString)
          .bind("authTokenHash", authTokenHash)
          .bind("salt", salt)
          .bind("gcmId", device.getGcmId())
          .bind("apnId", device.getApnId())
          .bind("fetchesMessages", device.getFetchesMessages())
          .bind("registrationId", device.getAccountRegistrationId())
          .bind("signedPrekeyId", 0)
          .bind("createdAt", device.getCreated())
          .bind("lastSeenAt", device.getLastSeen())
          .execute();
    }

    if (!currentDeviceIds.isEmpty()) {
      handle.createUpdate("DELETE FROM devices WHERE account_uuid = :uuid AND device_id NOT IN (<deviceIds>)")
          .bind("uuid", accountUuid)
          .bindList("deviceIds", currentDeviceIds)
          .execute();
    }
  }

  private byte[] serializeAccount(final Account account) {
    try {
      return ACCOUNT_DDB_JSON_WRITER.writeValueAsBytes(account);
    } catch (final JsonProcessingException e) {
      throw new IllegalArgumentException("Could not serialize account data", e);
    }
  }

  private Account mapAccount(final ResultSet rs) throws SQLException {
    final byte[] accountData = rs.getBytes("account_data");
    final String accountUuidStr = rs.getString("account_uuid");
    final String e164 = rs.getString("e164");
    final String pniStr = rs.getString("pni");
    final byte[] usernameHash = rs.getBytes("username_hash");
    final String usernameLinkUuidStr = rs.getString("username_link_uuid");
    final int version = rs.getInt("version");
    final boolean discoverable = rs.getBoolean("canonically_discoverable");

    try {
      final Account account = SystemMapper.jsonMapper().readValue(accountData, Account.class);
      account.setAccountIdentifier(UUID.fromString(accountUuidStr));
      account.setNumber(e164, pniStr != null ? UUID.fromString(pniStr) : null);
      account.setUsernameHash(usernameHash);
      account.setUsernameLinkHandle(usernameLinkUuidStr != null ? UUID.fromString(usernameLinkUuidStr) : null);
      account.setVersion(version);
      account.setDiscoverableByPhoneNumber(discoverable);
      return account;
    } catch (final IOException e) {
      throw new RuntimeException("Could not deserialize account from database", e);
    }
  }

  private interface DatabaseAction<T> {
    T run() throws Exception;
  }

  private <T> T executeWithRetry(final DatabaseAction<T> action) {
    int attempts = 0;
    while (true) {
      try {
        return action.run();
      } catch (final UnableToExecuteStatementException e) {
        final Throwable cause = e.getCause();
        if (cause instanceof SQLException sqlEx) {
          final int errorCode = sqlEx.getErrorCode();
          final String sqlState = sqlEx.getSQLState();

          // Check for Deadlock (1213 / 40001) or Lock wait timeout (1205)
          if ((errorCode == 1213 || errorCode == 1205 || "40001".equals(sqlState)) && attempts < MAX_DEADLOCK_RETRIES) {
            attempts++;
            log.warn("Deadlock detected, retrying attempt {}/{}", attempts, MAX_DEADLOCK_RETRIES);
            try {
              Thread.sleep(50L * attempts);
            } catch (final InterruptedException ie) {
              Thread.currentThread().interrupt();
              throw new RuntimeException(ie);
            }
            continue;
          }
        }
        throw e;
      } catch (final RuntimeException re) {
        throw re;
      } catch (final Exception ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}
