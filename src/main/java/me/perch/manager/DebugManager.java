package me.perch.manager;

import me.perch.Jobs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.Collectors;

public class DebugManager {

    private final Jobs plugin;
    private final Set<UUID> debuggingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public DebugManager(Jobs plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 20L, 20L);
    }

    public void toggleDebug(Player player) {
        UUID uuid = player.getUniqueId();
        if (debuggingPlayers.remove(uuid)) {
            buckets.remove(uuid);
            plugin.getMessageUtil().sendMessage(player, "<red>Debug mode disabled.");
        } else {
            debuggingPlayers.add(uuid);
            buckets.put(uuid, new Bucket(System.currentTimeMillis() / 1000));
            plugin.getMessageUtil().sendMessage(player, "<green>Debug mode enabled. <gray>Perform actions to see stats in chat.");
        }
    }

    public void recordAttempt(UUID uuid, String jobId, double units) {
        if (!debuggingPlayers.contains(uuid)) return;
        if (units <= 0) return;
        Bucket b = buckets.computeIfAbsent(uuid, k -> new Bucket(System.currentTimeMillis() / 1000));
        b.addAttempt(jobId, units);
    }

    public void recordSuccess(UUID uuid, String jobId, double units) {
        if (!debuggingPlayers.contains(uuid)) return;
        if (units <= 0) return;
        Bucket b = buckets.computeIfAbsent(uuid, k -> new Bucket(System.currentTimeMillis() / 1000));
        b.addSuccess(jobId, units);
    }

    private void tick() {
        if (debuggingPlayers.isEmpty()) return;

        long nowSec = System.currentTimeMillis() / 1000;

        for (UUID uuid : debuggingPlayers) {

            Bucket b = buckets.computeIfAbsent(uuid, k -> new Bucket(nowSec));
            Flush flush = b.rotateIfNeeded(nowSec);
            if (flush == null || flush.stats.isEmpty()) continue;

            String message = flush.stats.entrySet().stream()
                    .map(entry -> {
                        String job = capitalize(entry.getKey());
                        double tries = entry.getValue()[0].sum();
                        double paid = entry.getValue()[1].sum();
                        String color = (paid >= tries - 1e-9) ? "<green>" : (paid > 0 ? "<yellow>" : "<red>");
                        return "<bold>" + job + ":</bold> <gray>" + fmt(tries) + " try " + color + fmt(paid) + " paid";
                    })
                    .collect(Collectors.joining(" <dark_gray>| <reset>"));

            Component comp = MiniMessage.miniMessage().deserialize("<gradient:#adf3fd:#FD9113>[Debug]</gradient> " + message);

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.sendMessage(comp);
                } else {

                    debuggingPlayers.remove(uuid);
                    buckets.remove(uuid);
                }
            });
        }
    }

    private static final class Bucket {
        private long currentSecond;
        private Map<String, DoubleAdder[]> currentStats;

        Bucket(long second) {
            this.currentSecond = second;
            this.currentStats = new ConcurrentHashMap<>();
        }

        void addAttempt(String jobId, double units) {
            synchronized (this) {
                DoubleAdder[] a = currentStats.computeIfAbsent(jobId, k -> new DoubleAdder[]{new DoubleAdder(), new DoubleAdder()});
                a[0].add(units);
            }
        }

        void addSuccess(String jobId, double units) {
            synchronized (this) {
                DoubleAdder[] a = currentStats.computeIfAbsent(jobId, k -> new DoubleAdder[]{new DoubleAdder(), new DoubleAdder()});
                a[1].add(units);
            }
        }

        Flush rotateIfNeeded(long nowSec) {
            synchronized (this) {
                if (nowSec <= currentSecond) return null;
                Map<String, DoubleAdder[]> flushed = currentStats;
                long flushedSecond = currentSecond;
                currentSecond = nowSec;
                currentStats = new ConcurrentHashMap<>();
                return new Flush(flushedSecond, flushed);
            }
        }
    }

    private static final class Flush {
        final long second;
        final Map<String, DoubleAdder[]> stats;

        Flush(long second, Map<String, DoubleAdder[]> stats) {
            this.second = second;
            this.stats = stats;
        }
    }

    private static String fmt(double v) {
        double r = Math.rint(v);
        if (Math.abs(v - r) < 1e-6) return String.valueOf((long) r);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}