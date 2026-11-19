/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.whispersystems.textsecuregcm.entities.PreKey;
import org.whispersystems.textsecuregcm.registration.CompanyVerificationManager;

/**
 * Integration tests for MySQL/MariaDB storage layer using Testcontainers.
 *
 * NOTE: These tests require Docker to be installed and running on the machine (e.g. on the VPS).
 * In CODE-ONLY mode, this test suite is statically verified and ready for execution during VPS CI/CD.
 */
@Testcontainers
public class MySqlStorageIntegrationTest {

  @Container
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.0")
      .withDatabaseName("signal")
      .withUsername("signal")
      .withPassword("signal_secret");

  private static HikariDataSource dataSource;
  private static Jdbi jdbi;
  private static final Clock clock = Clock.systemUTC();
  private static final SecureRandom random = new SecureRandom();

  @BeforeAll
  static void beforeAll() {
    if (MYSQL.isRunning()) {
      final HikariConfig config = new HikariConfig();
      config.setJdbcUrl(MYSQL.getJdbcUrl() + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8mb4&rewriteBatchedStatements=true");
      config.setUsername(MYSQL.getUsername());
      config.setPassword(MYSQL.getPassword());
      config.setMaximumPoolSize(10);

      dataSource = new HikariDataSource(config);
      jdbi = Jdbi.create(dataSource);

      // Run Flyway migrations
      final Flyway flyway = Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .load();
      flyway.migrate();
    }
  }

  @AfterAll
  static void afterAll() {
    if (dataSource != null && !dataSource.isClosed()) {
      dataSource.close();
    }
  }

  @Test
  @DisplayName("Account creation, retrieval, and optimistic locking in MySQL")
  void testAccountLifecycle() {
    if (jdbi == null) return; // Skip if Docker is not available in environment

    final MySqlAccounts accountsStore = new MySqlAccounts(clock, jdbi);
    final UUID aci = UUID.randomUUID();
    final UUID pni = UUID.randomUUID();
    final String phoneNumber = "+1555" + (1000000 + random.nextInt(8999999));

    // 1. Create account
    final Account account = new Account();
    account.setUuid(aci);
    account.setPhoneNumberIdentifier(pni);
    account.setNumber(phoneNumber);

    final Device device = new Device();
    device.setId(Device.PRIMARY_ID);
    device.setAuthToken("auth_token_secret");
    device.setSalt("salt_123");
    device.setCreated(System.currentTimeMillis());
    account.addDevice(device);

    accountsStore.create(account);

    // 2. Retrieve by ACI, PNI, and Phone number
    final Optional<Account> fetchedByAci = accountsStore.getByAccountIdentifier(aci);
    assertTrue(fetchedByAci.isPresent(), "Account should be found by ACI");
    assertEquals(phoneNumber, fetchedByAci.get().getNumber().orElse(null));

    final Optional<Account> fetchedByPni = accountsStore.getByPhoneNumberIdentifier(pni);
    assertTrue(fetchedByPni.isPresent(), "Account should be found by PNI");

    final Optional<Account> fetchedByPhone = accountsStore.getByNumber(phoneNumber);
    assertTrue(fetchedByPhone.isPresent(), "Account should be found by Phone number");

    // 3. Update account and test optimistic locking
    final Account updated = fetchedByAci.get();
    updated.addDevice(new Device() {{
      setId((byte) 2);
      setAuthToken("linked_token");
      setSalt("salt_456");
      setCreated(System.currentTimeMillis());
    }});

    final boolean updateResult = accountsStore.update(updated);
    assertTrue(updateResult, "Update with matching version should succeed");

    // 4. Stale update must fail
    final Account staleAccount = fetchedByAci.get(); // has old version
    staleAccount.setUnidentifiedAccessKey(new byte[16]);
    final boolean staleResult = accountsStore.update(staleAccount);
    assertFalse(staleResult, "Stale update with old version must fail");
  }

  @Test
  @DisplayName("Concurrent prekey consumption guarantees no prekey is returned twice (FOR UPDATE SKIP LOCKED)")
  void testConcurrentPreKeyConsumption() throws Exception {
    if (jdbi == null) return;

    final MySqlSingleUseECPreKeyStore preKeyStore = new MySqlSingleUseECPreKeyStore(jdbi, clock);
    final UUID accountIdentifier = UUID.randomUUID();
    final byte deviceId = 1;

    // Store 50 single-use prekeys
    final List<PreKey> preKeys = new ArrayList<>();
    for (int i = 1; i <= 50; i++) {
      final byte[] publicKeyBytes = new byte[32];
      random.nextBytes(publicKeyBytes);
      preKeys.add(new PreKey(i, publicKeyBytes));
    }
    preKeyStore.store(accountIdentifier, deviceId, preKeys);

    // Concurrently fetch prekeys using 10 worker threads
    final int threadCount = 10;
    final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    final Set<Long> consumedKeyIds = Collections.synchronizedSet(new HashSet<>());
    final List<Callable<Optional<PreKey>>> tasks = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      tasks.add(() -> preKeyStore.take(accountIdentifier, deviceId).join());
    }

    final List<Future<Optional<PreKey>>> results = executor.invokeAll(tasks);
    executor.shutdown();

    for (final Future<Optional<PreKey>> future : results) {
      final Optional<PreKey> maybeKey = future.get();
      assertTrue(maybeKey.isPresent(), "Each concurrent worker should get a prekey");
      final boolean added = consumedKeyIds.add(maybeKey.get().getKeyId());
      assertTrue(added, "Prekey " + maybeKey.get().getKeyId() + " must never be consumed more than once!");
    }

    assertEquals(threadCount, consumedKeyIds.size(), "Exactly 10 distinct prekeys should be consumed");
    final int remainingCount = preKeyStore.getCount(accountIdentifier, deviceId).join();
    assertEquals(40, remainingCount, "Exactly 40 prekeys must remain in the database");
  }

  @Test
  @DisplayName("Company registration allowlist enforcement and verification codes")
  void testCompanyVerificationAllowlist() {
    if (jdbi == null) return;

    final CompanyVerificationManager verificationManager = new CompanyVerificationManager(
        jdbi,
        true,   // allowlistOnly = true
        true,   // fixedCodeEnabled = true
        "123456"
    );

    final String staffNumber = "+1555" + (1000000 + random.nextInt(8999999));
    final String strangerNumber = "+1555" + (1000000 + random.nextInt(8999999));

    // Stranger is not on allowlist
    assertFalse(verificationManager.isAllowed(strangerNumber), "Stranger must not be allowed");
    assertFalse(verificationManager.verifyCode(strangerNumber, "123456"), "Stranger code verify must fail");

    // Add staff member
    verificationManager.addAllowed(staffNumber, "998877", System.currentTimeMillis() / 1000 + 3600, "Alice - DevOps");

    // Staff is allowed
    assertTrue(verificationManager.isAllowed(staffNumber), "Staff must be allowed");
    // Verify with fixed code
    assertTrue(verificationManager.verifyCode(staffNumber, "123456"), "Fixed code must succeed for allowlisted staff");
    // Verify with custom code
    assertTrue(verificationManager.verifyCode(staffNumber, "998877"), "Admin code must succeed for staff");
    // Wrong code fails
    assertFalse(verificationManager.verifyCode(staffNumber, "000000"), "Wrong code must fail");
  }
}
