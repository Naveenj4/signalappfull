/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.dropwizard.lifecycle.Managed;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.DatabaseConfiguration;

import javax.sql.DataSource;

public class DatabaseConnectionManager implements Managed {

  private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionManager.class);

  private final DatabaseConfiguration config;
  private HikariDataSource dataSource;
  private Jdbi jdbi;

  public DatabaseConnectionManager(final DatabaseConfiguration config) {
    this.config = config;
    init();
  }

  private void init() {
    log.info("Initializing MySQL/MariaDB connection pool for {}", config.getJdbcUrl());
    final HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
    hikariConfig.setJdbcUrl(config.getJdbcUrl());
    hikariConfig.setUsername(config.getUser());
    hikariConfig.setPassword(config.getPassword());
    hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
    hikariConfig.setMinimumIdle(config.getMinIdle());
    hikariConfig.setConnectionTimeout(config.getConnectionTimeout().toMillis());
    hikariConfig.setIdleTimeout(config.getIdleTimeout().toMillis());
    hikariConfig.setPoolName("SignalMySQLPool");

    this.dataSource = new HikariDataSource(hikariConfig);

    if (config.isRunFlyway()) {
      runFlywayMigrations();
    }

    this.jdbi = Jdbi.create(this.dataSource)
        .installPlugin(new SqlObjectPlugin());
  }

  private void runFlywayMigrations() {
    log.info("Running Flyway migrations on MySQL/MariaDB...");
    try {
      final Flyway flyway = Flyway.configure()
          .dataSource(this.dataSource)
          .locations("classpath:db/migration")
          .baselineOnMigrate(true)
          .load();
      final var result = flyway.migrate();
      log.info("Flyway successfully applied {} migrations.", result.migrationsExecuted);
    } catch (final Exception e) {
      log.error("Flyway migration failed: {}", e.getMessage(), e);
      throw new RuntimeException("Could not run Flyway migrations", e);
    }
  }

  public DataSource getDataSource() {
    return dataSource;
  }

  public Jdbi getJdbi() {
    return jdbi;
  }

  @Override
  public void start() {
    // Pool initialized eagerly for repository construction
  }

  @Override
  public void stop() {
    if (dataSource != null && !dataSource.isClosed()) {
      log.info("Closing MySQL/MariaDB connection pool");
      dataSource.close();
    }
  }
}
