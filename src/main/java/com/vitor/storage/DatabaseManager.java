package com.vitor.storage;

import com.vitor.model.UserAccount;
import com.vitor.vEconomy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {

    private final vEconomy plugin;
    private HikariDataSource dataSource;
    private final String tableName = "balances";

    public DatabaseManager(vEconomy plugin) {
        this.plugin = plugin;
        initConnection();
        initTables();
    }

    private void initConnection() {
        String host = plugin.getConfig().getString("database.host");
        String port = plugin.getConfig().getString("database.port");
        String dbName = plugin.getConfig().getString("database.database");
        String user = plugin.getConfig().getString("database.username");
        String pass = plugin.getConfig().getString("database.password");

        HikariConfig config = new HikariConfig();

        // Force MariaDB driver for better compatibility with your setup
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + dbName);
        config.setUsername(user);
        config.setPassword(pass);

        // Performance tuning
        config.setMaximumPoolSize(plugin.getConfig().getInt("database.pool_size", 10));
        config.setConnectionTimeout(plugin.getConfig().getLong("database.connection_timeout", 5000));
        config.setPoolName("vEconomy-Hikari");

        // HikariCP recommended settings for MySQL/MariaDB
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection()) {
            plugin.getLogger().info("Database connection (MariaDB) established successfully!");
        } catch (SQLException e) {
            // Runtime exception to stop plugin loading if DB fails
            throw new RuntimeException("Failed to connect to database!", e);
        }
    }

    private void initTables() {
        // Schema:
        // uuid: Player UUID
        // currency_id: Currency identifier (max 32 chars)
        // amount: High precision decimal (30 digits, 4 decimal places)
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
                uuid VARCHAR(36) NOT NULL,
                currency_id VARCHAR(32) NOT NULL,
                amount DECIMAL(30, 4) NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, currency_id),
                INDEX idx_uuid (uuid)
            ) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        """.formatted(tableName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error creating tables!", e);
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * Loads account data synchronously.
     * Applies default balances if no data is found.
     */
    public UserAccount loadAccount(UUID uuid, Map<String, Double> defaultBalances) {
        UserAccount account = new UserAccount();

        // 1. Apply initial defaults first
        defaultBalances.forEach((currency, startBal) ->
                account.setBalance(currency, BigDecimal.valueOf(startBal)));

        // 2. Overwrite with database data
        String sql = "SELECT currency_id, amount FROM " + tableName + " WHERE uuid = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String currency = rs.getString("currency_id");
                    BigDecimal amount = rs.getBigDecimal("amount");
                    account.setBalance(currency, amount);
                }
            }
            // Mark as clean since it matches DB
            account.setClean();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load account: " + uuid, e);
        }
        return account;
    }

    /**
     * Saves user account data to the database.
     */
    public void saveAccount(UUID uuid, UserAccount account) {
        if (!account.isDirty()) return; // Skip if no changes

        String sql = """
            INSERT INTO %s (uuid, currency_id, amount) 
            VALUES (?, ?, ?) 
            ON DUPLICATE KEY UPDATE amount = VALUES(amount)
        """.formatted(tableName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // Batch update for performance
            for (String currency : plugin.getAPI().getRegisteredCurrencies()) {
                BigDecimal balance = account.getBalance(currency);
                ps.setString(1, uuid.toString());
                ps.setString(2, currency);
                ps.setBigDecimal(3, balance);
                ps.addBatch();
            }

            ps.executeBatch();
            account.setClean();

            if (plugin.isLoggingEnabled()) {
                plugin.getLogger().info("Data saved for: " + uuid);
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "CRITICAL ERROR SAVING: " + uuid, e);
        }
    }

    public void wipeUser(UUID uuid) {
        String sql = "DELETE FROM " + tableName + " WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error wiping user: " + uuid, e);
        }
    }
}