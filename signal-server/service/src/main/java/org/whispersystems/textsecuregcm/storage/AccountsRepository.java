/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

public interface AccountsRepository {

  boolean create(Account account, List<TransactWriteItem> additionalWriteItems)
      throws AccountAlreadyExistsException;

  void update(Account account) throws ContestedOptimisticLockException;

  void updateTransactionally(Account account, Collection<TransactWriteItem> additionalWriteItems)
      throws ContestedOptimisticLockException;

  Optional<Account> getByAccountIdentifier(UUID uuid);

  CompletableFuture<Optional<Account>> getByAccountIdentifierAsync(UUID uuid);

  Optional<Account> getByE164(String number);

  CompletableFuture<Optional<Account>> getByE164Async(String number);

  Optional<Account> getByPhoneNumberIdentifier(UUID phoneNumberIdentifier);

  CompletableFuture<Optional<Account>> getByPhoneNumberIdentifierAsync(UUID phoneNumberIdentifier);

  CompletableFuture<Optional<Account>> getByUsernameHash(byte[] usernameHash);

  CompletableFuture<Optional<Account>> getByUsernameLinkHandle(UUID usernameLinkHandle);

  Optional<UUID> findRecentlyDeletedAccountIdentifier(UUID phoneNumberIdentifier);

  Optional<UUID> findRecentlyDeletedPhoneNumberIdentifier(UUID uuid);

  void delete(UUID uuid, List<TransactWriteItem> additionalWriteItems);

  boolean accountExists(UUID uuid);

  void changeNumber(Account account, String oldNumber, UUID oldPni, String newNumber, UUID newPni,
      boolean isDiscoverable, List<TransactWriteItem> additionalWriteItems)
      throws ContestedOptimisticLockException;

  void reserveUsernameHash(Account account, byte[] reservedUsernameHash, Duration ttl)
      throws ContestedOptimisticLockException;

  void confirmUsernameHash(Account account, byte[] usernameHash, @Nullable byte[] encryptedUsername)
      throws ContestedOptimisticLockException;

  void clearUsernameHash(Account account) throws ContestedOptimisticLockException;

  CompletionStage<Void> reclaimAccount(Account existingAccount, Account newAccount,
      Collection<TransactWriteItem> additionalWriteItems);

  Flux<Account> getAll(int segments, Scheduler scheduler);

  TransactWriteItem buildTransactWriteItemForLinkDevice(String linkDeviceToken, Duration tokenTtl);
}

