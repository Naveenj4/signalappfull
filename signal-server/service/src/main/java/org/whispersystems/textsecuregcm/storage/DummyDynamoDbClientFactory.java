/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Provides no-op dummy implementations of DynamoDbClient and DynamoDbAsyncClient
 * when MySQL/MariaDB is configured as the primary database, eliminating the need
 * for AWS credentials, endpoints, or DynamoDB Local.
 */
public final class DummyDynamoDbClientFactory {

  private DummyDynamoDbClientFactory() {}

  public static DynamoDbClient createDummySyncClient() {
    return (DynamoDbClient) Proxy.newProxyInstance(
        DynamoDbClient.class.getClassLoader(),
        new Class<?>[]{DynamoDbClient.class},
        (proxy, method, args) -> {
          final String name = method.getName();
          if ("close".equals(name)) {
            return null;
          }
          if ("toString".equals(name)) {
            return "DummyDynamoDbSyncClient";
          }
          if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
          }
          if ("equals".equals(name)) {
            return proxy == (args != null && args.length > 0 ? args[0] : null);
          }
          return null;
        }
    );
  }

  public static DynamoDbAsyncClient createDummyAsyncClient() {
    return (DynamoDbAsyncClient) Proxy.newProxyInstance(
        DynamoDbAsyncClient.class.getClassLoader(),
        new Class<?>[]{DynamoDbAsyncClient.class},
        (proxy, method, args) -> {
          final String name = method.getName();
          if ("close".equals(name)) {
            return null;
          }
          if ("toString".equals(name)) {
            return "DummyDynamoDbAsyncClient";
          }
          if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
          }
          if ("equals".equals(name)) {
            return proxy == (args != null && args.length > 0 ? args[0] : null);
          }
          if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
            return CompletableFuture.completedFuture(null);
          }
          return null;
        }
    );
  }
}
