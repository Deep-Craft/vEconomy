<div align="center">
<h1>💰 vEconomy</h1>
<p><i>High-performance multi-currency economy system for Minecraft servers.</i></p>

<div>
<img src="https://img.shields.io/badge/Java-21%2B-orange?style=flat-square" alt="Java Version">
<img src="https://img.shields.io/badge/Platform-Spigot%20%7C%20Paper-green?style=flat-square" alt="Platform">
</div>
</div>

<br />

---

## Overview
vEconomy is a robust, high-performance economy plugin built for modern Minecraft servers. It addresses the common bottlenecks of traditional economy systems by using advanced concurrency patterns and optimized database management, ensuring data integrity even under heavy load.

## ⚡ Performance & Scalability
* StampedLock Concurrency: Utilizes StampedLock for optimistic read operations, providing near-zero overhead for balance checks while maintaining strict thread safety for writes.
* HikariCP Integration: Managed connection pooling specifically tuned for MariaDB/MySQL to prevent "lag spikes" during database I/O.
* Sliding Window Metrics: Implements a real-time sliding window algorithm to track earnings and spending rates (Input/Output) per second.

## 🛡️ Data Integrity
* Financial Precision: Uses BigDecimal throughout the entire architecture to prevent precision loss and rounding errors common in double or float implementations.
* Hybrid Offline Support: Sophisticated handling for offline players—loading, modifying, and persisting data directly to the storage layer without polluting the active memory cache.
* Asynchronous Persistence: Intelligent auto-save cycles that run off-thread to keep the main server tick fluid.

## 🧩 Developer-Centric
* Multi-Currency Support: Scalable system allowing multiple independent currencies (e.g., Dollars, Cash, Gems) via config.yml.
* Decoupled API: A clean interface designed for easy integration with other plugins and web-based dashboards.

## Requirements
* Java 21 or higher.
* MariaDB (Recommended) or MySQL.
* Spigot/Paper API 1.21.x (Compatible with modern stable versions).

## Commands & Permissions

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/money [player]` | View your or someone else's balance. | `vEconomy.balance` |
| `/veco give <p> <cur> <amt>` | Add funds to a player's account. | `vEconomy.admin` |
| `/veco take <p> <cur> <amt>` | Remove funds from a player's account. | `vEconomy.admin` |
| `/veco set <p> <cur> <amt>` | Set a fixed balance for a player. | `vEconomy.admin` |
| `/veco wipe <p>` | Permanently delete a player's data. | `vEconomy.admin` |

## Placeholders
The following placeholders are available via PlaceholderAPI:

| Placeholder | Description |
| :--- | :--- |
| `%veconomy_<currency>_balance%` | Formatted balance. |
| `%veconomy_<currency>_input%` | Average earnings per second. |
| `%veconomy_<currency>_output%` | Average spending per second. |
| `%veconomy_<currency>_raw%` | Raw unformatted decimal. |

## License
This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).

---

<div align="center">
<p>Developed with ❤️ by <b>vitor1227_OP (SoldadoHumano)</b></p>
</div>
