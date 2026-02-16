package com.vitor.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.locks.StampedLock;

/**
 * Represents a player's account holding all their currency balances.
 * Implements StampedLock for high-performance optimistic concurrency control.
 */
public class UserAccount {

    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<String, MetricTracker> metrics = new ConcurrentHashMap<>();

    // Lock to ensure consistency in critical read/write operations
    private final StampedLock lock = new StampedLock();

    // Flag to mark if the object has changed and needs to be saved to DB
    private volatile boolean dirty = false;

    /**
     * Safe retrieval of balance using optimistic locking.
     */
    public BigDecimal getBalance(String currency) {
        long stamp = lock.tryOptimisticRead();
        BigDecimal val = balances.getOrDefault(currency, BigDecimal.ZERO);

        // If the lock was acquired by a writer during read, upgrade to read lock
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                val = balances.getOrDefault(currency, BigDecimal.ZERO);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return val;
    }

    /**
     * Checks if the user has enough money.
     */
    public boolean hasEnough(String currency, BigDecimal required) {
        return getBalance(currency).compareTo(required) >= 0;
    }

    /**
     * Updates the balance and metrics.
     * Thread-safe using write lock.
     */
    public void setBalance(String currency, BigDecimal amount) {
        long stamp = lock.writeLock();
        try {
            BigDecimal old = balances.getOrDefault(currency, BigDecimal.ZERO);
            balances.put(currency, amount);
            dirty = true;

            // Update metrics only if value changed
            if (old.compareTo(amount) != 0) {
                updateMetrics(currency, old, amount);
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setClean() {
        this.dirty = false;
    }

    private void updateMetrics(String currency, BigDecimal oldVal, BigDecimal newVal) {
        MetricTracker tracker = metrics.computeIfAbsent(currency, k -> new MetricTracker());
        double diff = newVal.subtract(oldVal).doubleValue();
        if (diff > 0) {
            tracker.addInput(diff);
        } else if (diff < 0) {
            tracker.addOutput(Math.abs(diff));
        }
    }

    public double getInputRate(String currency) {
        MetricTracker tracker = metrics.get(currency);
        return tracker == null ? 0.0 : tracker.calculateAverageInput();
    }

    public double getOutputRate(String currency) {
        MetricTracker tracker = metrics.get(currency);
        return tracker == null ? 0.0 : tracker.calculateAverageOutput();
    }

    /**
     * Tick metrics for sliding window calculation.
     */
    public void tickMetrics() {
        metrics.values().forEach(MetricTracker::tick);
    }

    /**
     * Inner class to track metrics per second using a Sliding Window algorithm.
     * Maintains history of the last 5 seconds to smooth out values.
     */
    public static class MetricTracker {
        private final DoubleAdder currentSecondInput = new DoubleAdder();
        private final DoubleAdder currentSecondOutput = new DoubleAdder();

        // 5-second window
        private final double[] inputHistory = new double[5];
        private final double[] outputHistory = new double[5];
        private int pointer = 0;

        public void addInput(double val) {
            currentSecondInput.add(val);
        }

        public void addOutput(double val) {
            currentSecondOutput.add(val);
        }

        // Called by global Scheduler every 1 second (20 ticks)
        public synchronized void tick() {
            inputHistory[pointer] = currentSecondInput.sumThenReset();
            outputHistory[pointer] = currentSecondOutput.sumThenReset();
            pointer = (pointer + 1) % 5;
        }

        public synchronized double calculateAverageInput() {
            double sum = 0;
            for (double v : inputHistory) sum += v;
            return sum;
        }

        public synchronized double calculateAverageOutput() {
            double sum = 0;
            for (double v : outputHistory) sum += v;
            return sum;
        }
    }
}