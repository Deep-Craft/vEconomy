package com.vitor;

import com.vitor.api.vEconomyAPI;
import com.vitor.model.UserAccount;
import com.vitor.storage.DatabaseManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class vEconomy extends JavaPlugin implements Listener, vEconomyAPI {

    private DatabaseManager dbManager;
    private final Map<UUID, UserAccount> accountCache = new ConcurrentHashMap<>();
    private final Map<String, CurrencyConfig> currencyConfigs = new HashMap<>();
    private boolean loggingEnabled;

    // Record for currency configuration (Java 21 feature)
    public record CurrencyConfig(String id, String name, String symbol, double startBalance, String formatPattern) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        // Initialize Database
        try {
            this.dbManager = new DatabaseManager(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to connect to database! Disabling plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register API
        getServer().getServicesManager().register(vEconomyAPI.class, this, this, ServicePriority.Highest);

        // Register Commands and Events
        Objects.requireNonNull(getCommand("veconomy")).setExecutor(new EconomyCommand());
        Objects.requireNonNull(getCommand("money")).setExecutor(new EconomyCommand());
        getServer().getPluginManager().registerEvents(this, this);

        // Hook PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new EconomyExpansion().register();
        }

        // Auto-Save Task (Async) - every 5 minutes (6000 ticks)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (loggingEnabled) getLogger().info("Starting auto-save...");
            accountCache.forEach((uuid, acc) -> dbManager.saveAccount(uuid, acc));
        }, 6000L, 6000L);

        // Metrics Task (Sliding Windows) - every 1 second (20 ticks)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            accountCache.values().forEach(UserAccount::tickMetrics);
        }, 20L, 20L);

        getLogger().info("vEconomy loaded with Java 21 and HikariCP.");
    }

    @Override
    public void onDisable() {
        // Save everything on shutdown
        if (dbManager != null) {
            getLogger().info("Saving data...");
            // Synchronous save on shutdown to guarantee persistence
            accountCache.forEach((uuid, acc) -> dbManager.saveAccount(uuid, acc));
            dbManager.close();
        }
    }

    private void loadConfiguration() {
        this.loggingEnabled = getConfig().getBoolean("logging");
        this.currencyConfigs.clear();

        var section = getConfig().getConfigurationSection("currencies");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String name = section.getString(key + ".display_name");
                String symbol = section.getString(key + ".symbol");
                double start = section.getDouble(key + ".start_balance");
                String format = section.getString(key + ".format");
                currencyConfigs.put(key, new CurrencyConfig(key, name, symbol, start, format));
            }
        }
    }

    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    public vEconomyAPI getAPI() {
        return this;
    }

    private Map<String, Double> getDefaultBalances() {
        Map<String, Double> defaults = new HashMap<>();
        currencyConfigs.values().forEach(c -> defaults.put(c.id, c.startBalance));
        return defaults;
    }

    // --- API Implementation ---

    @Override
    public boolean currencyExists(String currencyId) {
        return currencyConfigs.containsKey(currencyId);
    }

    @Override
    public Set<String> getRegisteredCurrencies() {
        return Collections.unmodifiableSet(currencyConfigs.keySet());
    }

    /**
     * Gets balance. If user is not in cache (offline), loads temporarily from DB.
     */
    @Override
    public BigDecimal getBalance(UUID playerUuid, String currencyId) {
        UserAccount acc = accountCache.get(playerUuid);
        if (acc != null) {
            return acc.getBalance(currencyId);
        }

        // Fallback for offline player (synchronous load)
        UserAccount offlineAcc = dbManager.loadAccount(playerUuid, getDefaultBalances());
        return offlineAcc.getBalance(currencyId);
    }

    @Override
    public BigDecimal deposit(UUID playerUuid, String currencyId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("Amount cannot be negative");

        UserAccount acc = accountCache.get(playerUuid);
        if (acc != null) {
            // Online player logic
            BigDecimal current = acc.getBalance(currencyId);
            BigDecimal neo = current.add(amount);
            acc.setBalance(currencyId, neo);
            if (loggingEnabled) getLogger().info("Deposit (Online): " + playerUuid + " +" + amount + " " + currencyId);
            return neo;
        } else {
            // Offline player logic (Load -> Modify -> Save)
            UserAccount offlineAcc = dbManager.loadAccount(playerUuid, getDefaultBalances());
            BigDecimal current = offlineAcc.getBalance(currencyId);
            BigDecimal neo = current.add(amount);
            offlineAcc.setBalance(currencyId, neo);
            dbManager.saveAccount(playerUuid, offlineAcc); // Immediate save
            if (loggingEnabled) getLogger().info("Deposit (Offline): " + playerUuid + " +" + amount + " " + currencyId);
            return neo;
        }
    }

    @Override
    public BigDecimal withdraw(UUID playerUuid, String currencyId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("Amount cannot be negative");

        // Helper interface to avoid code duplication for online/offline
        UserAccount acc = accountCache.get(playerUuid);
        boolean isOffline = (acc == null);

        if (isOffline) {
            acc = dbManager.loadAccount(playerUuid, getDefaultBalances());
        }

        BigDecimal current = acc.getBalance(currencyId);

        if (current.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds");
        }

        BigDecimal neo = current.subtract(amount);
        acc.setBalance(currencyId, neo);

        if (isOffline) {
            dbManager.saveAccount(playerUuid, acc);
        }

        if (loggingEnabled) getLogger().info("Withdraw (" + (isOffline ? "Offline" : "Online") + "): " + playerUuid + " -" + amount + " " + currencyId);
        return neo;
    }

    @Override
    public void setBalance(UUID playerUuid, String currencyId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("Amount cannot be negative");

        UserAccount acc = accountCache.get(playerUuid);
        boolean isOffline = (acc == null);

        if (isOffline) {
            acc = dbManager.loadAccount(playerUuid, getDefaultBalances());
        }

        acc.setBalance(currencyId, amount);

        if (isOffline) {
            dbManager.saveAccount(playerUuid, acc);
        }

        if (loggingEnabled) getLogger().info("Set (" + (isOffline ? "Offline" : "Online") + "): " + playerUuid + " = " + amount + " " + currencyId);
    }

    @Override
    public double getInputPerSecond(UUID playerUuid, String currencyId) {
        UserAccount acc = accountCache.get(playerUuid);
        // Divided by 5 because the window is 5s
        return acc != null ? acc.getInputRate(currencyId) / 5.0 : 0.0;
    }

    @Override
    public double getOutputPerSecond(UUID playerUuid, String currencyId) {
        UserAccount acc = accountCache.get(playerUuid);
        return acc != null ? acc.getOutputRate(currencyId) / 5.0 : 0.0;
    }

    @Override
    public String format(String currencyId, BigDecimal amount) {
        CurrencyConfig conf = currencyConfigs.get(currencyId);
        if (conf == null) return amount.toString();
        return conf.symbol + new DecimalFormat(conf.formatPattern).format(amount);
    }

    // --- Listeners ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Asynchronous blocking load (safe in this event)
        try {
            UserAccount acc = dbManager.loadAccount(event.getUniqueId(), getDefaultBalances());
            accountCache.put(event.getUniqueId(), acc);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error loading data for " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Critical error loading economy data.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        UserAccount acc = accountCache.remove(uuid);
        if (acc != null) {
            // Save async to not block main thread on quit
            CompletableFuture.runAsync(() -> dbManager.saveAccount(uuid, acc));
        }
    }

    // --- Placeholders ---

    private class EconomyExpansion extends PlaceholderExpansion {
        @Override
        public @NotNull String getIdentifier() { return "veconomy"; }
        @Override
        public @NotNull String getAuthor() { return "SoldadoHumano"; }
        @Override
        public @NotNull String getVersion() { return "1.1.0"; }
        @Override
        public boolean persist() { return true; }

        @Override
        public String onPlaceholderRequest(Player player, @NotNull String params) {
            if (player == null) return "";

            // params: <currency>_balance, <currency>_input, <currency>_output
            String[] parts = params.split("_");
            if (parts.length < 2) return null;

            String currency = parts[0];
            String type = parts[1];

            if (!currencyConfigs.containsKey(currency)) return null;

            return switch (type) {
                case "balance" -> format(currency, getBalance(player.getUniqueId(), currency));
                case "input" -> String.format("%.2f/s", getInputPerSecond(player.getUniqueId(), currency));
                case "output" -> String.format("%.2f/s", getOutputPerSecond(player.getUniqueId(), currency));
                case "raw" -> getBalance(player.getUniqueId(), currency).toPlainString();
                default -> null;
            };
        }
    }

    // --- Commands ---

    private class EconomyCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
            try {
                // Command: /money [player]
                if (command.getName().equalsIgnoreCase("money")) {
                    if (!sender.hasPermission("vEconomy.balance")) {
                        sender.sendMessage(getConfig().getString("messages.no_permission", "§cNo permission."));
                        return true;
                    }

                    if (args.length == 0) {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("§cOnly players can use this command without arguments.");
                            return true;
                        }
                        sender.sendMessage("§aCurrent balance: " + format("money", getBalance(player.getUniqueId(), "money")));
                        return true;
                    } else {
                        // Check other player's balance (Async to support offline lookup)
                        String targetName = args[0];
                        CompletableFuture.runAsync(() -> {
                            // Deprecated method but necessary for name->uuid lookup.
                            // In production, consider caching names or using a proxy API.
                            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                            // Check if player actually exists in server history or is online
                            if (!target.hasPlayedBefore() && !target.isOnline()) {
                                sender.sendMessage("§cPlayer never played before.");
                                return;
                            }

                            BigDecimal bal = getBalance(target.getUniqueId(), "money");
                            sender.sendMessage("§aBalance of " + target.getName() + ": " + format("money", bal));
                        });
                        return true;
                    }
                }

                // Command: /veconomy ...
                if (!sender.hasPermission("vEconomy.admin")) {
                    sender.sendMessage(getConfig().getString("messages.no_permission", "§cNo Permission.").replace("&", "§"));
                    return true;
                }

                if (args.length == 0) {
                    sender.sendMessage("§bvEconomy §7- Usage: /veco [give/take/set/wipe]");
                    return true;
                }

                String subCmd = args[0].toLowerCase();

                if (subCmd.equals("wipe")) {
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /veco wipe <player>");
                        return true;
                    }
                    String targetName = args[1];
                    CompletableFuture.runAsync(() -> {
                        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                        if (target.hasPlayedBefore() || target.isOnline()) {
                            accountCache.remove(target.getUniqueId()); // Remove from cache
                            dbManager.wipeUser(target.getUniqueId()); // Remove from DB
                            sender.sendMessage("§aUser " + targetName + " deleted from database.");
                        } else {
                            sender.sendMessage("§cPlayer not found.");
                        }
                    });
                    return true;
                }

                // Handling give/take/set logic
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /veco " + subCmd + " <player> <currency> <amount>");
                    return true;
                }

                String targetName = args[1];
                String currencyID = args[2];
                String amountStr = args[3];

                if (!getAPI().currencyExists(currencyID)) {
                    sender.sendMessage("§cCurrency '" + currencyID + "' does not exist.");
                    return true;
                }

                CompletableFuture.runAsync(() -> {
                    try {
                        BigDecimal amount = new BigDecimal(amountStr);
                        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

                        if (!target.hasPlayedBefore() && !target.isOnline()) {
                            sender.sendMessage("§cPlayer never played on this server.");
                            return;
                        }

                        switch (subCmd) {
                            case "give" -> {
                                BigDecimal newBal = deposit(target.getUniqueId(), currencyID, amount);
                                sender.sendMessage("§aAdded " + format(currencyID, amount) + " to " + target.getName() + ". New: " + format(currencyID, newBal));
                            }
                            case "take" -> {
                                try {
                                    BigDecimal newBal = withdraw(target.getUniqueId(), currencyID, amount);
                                    sender.sendMessage("§aRemoved " + format(currencyID, amount) + " from " + target.getName() + ". New: " + format(currencyID, newBal));
                                } catch (IllegalStateException e) {
                                    sender.sendMessage("§cError: Player has insufficient funds.");
                                }
                            }
                            case "set" -> {
                                setBalance(target.getUniqueId(), currencyID, amount);
                                sender.sendMessage("§aSet balance of " + target.getName() + " to " + format(currencyID, amount));
                            }
                            default -> sender.sendMessage("§cUnknown subcommand.");
                        }

                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cInvalid number format.");
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage("§cError: " + e.getMessage());
                    } catch (Exception e) {
                        getLogger().log(Level.SEVERE, "Command error", e);
                        sender.sendMessage("§cAn internal error occurred.");
                    }
                });

            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Unexpected command exception", e);
                sender.sendMessage("§cCritical error executing command.");
            }
            return true;
        }
    }
}