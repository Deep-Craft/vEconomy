package com.vitor.api;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Public API for interaction with vEconomy.
 * Thread-safe implementation.
 * <p>
 * Note: Methods interacting with balances may block the thread if the player
 * is offline (due to Database I/O). It is recommended to call these methods asynchronously.
 */
public interface vEconomyAPI {

    /**
     * Checks if a specific currency exists in the registry.
     * @param currencyId The ID of the currency to check.
     * @return true if it exists, false otherwise.
     */
    boolean currencyExists(String currencyId);

    /**
     * Returns a set of all registered currency IDs.
     * @return Unmodifiable set of currency IDs.
     */
    Set<String> getRegisteredCurrencies();

    /**
     * Gets the current balance of a player.
     * If the player is offline, this method performs a database lookup.
     *
     * @param playerUuid The UUID of the player.
     * @param currencyId The ID of the currency.
     * @return The current balance, or default starting balance if new.
     */
    BigDecimal getBalance(UUID playerUuid, String currencyId);

    /**
     * Adds an amount to the player's balance.
     * Supports both online and offline players.
     *
     * @param playerUuid The UUID of the player.
     * @param currencyId The ID of the currency.
     * @param amount The amount to deposit (must be positive).
     * @return The new balance after the deposit.
     * @throws IllegalArgumentException if amount is negative or null.
     */
    BigDecimal deposit(UUID playerUuid, String currencyId, BigDecimal amount);

    /**
     * Removes an amount from the player's balance.
     * Checks for sufficient funds before withdrawing.
     *
     * @param playerUuid The UUID of the player.
     * @param currencyId The ID of the currency.
     * @param amount The amount to withdraw (must be positive).
     * @return The new balance after the withdrawal.
     * @throws IllegalArgumentException if amount is negative.
     * @throws IllegalStateException if the player has insufficient funds.
     */
    BigDecimal withdraw(UUID playerUuid, String currencyId, BigDecimal amount);

    /**
     * Sets the player's balance to a fixed amount.
     *
     * @param playerUuid The UUID of the player.
     * @param currencyId The ID of the currency.
     * @param amount The specific amount to set.
     */
    void setBalance(UUID playerUuid, String currencyId, BigDecimal amount);

    /**
     * Gets the average input (earnings) per second.
     * Based on a sliding window algorithm.
     * Returns 0.0 if the player is offline.
     */
    double getInputPerSecond(UUID playerUuid, String currencyId);

    /**
     * Gets the average output (spending) per second.
     * Returns 0.0 if the player is offline.
     */
    double getOutputPerSecond(UUID playerUuid, String currencyId);

    /**
     * Formats a BigDecimal amount according to the currency configuration.
     * E.g., 1000.50 -> "$1,000.50"
     */
    String format(String currencyId, BigDecimal amount);
}