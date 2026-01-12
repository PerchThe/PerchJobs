package me.perch.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlacedBlockTracker {

    private final long ttlMs;
    private final Map<Long, Long> placedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> worldSalt = new ConcurrentHashMap<>();

    public PlacedBlockTracker(long ttlMs) {
        this.ttlMs = Math.max(1L, ttlMs);
    }

    public void record(Block block) {
        placedAt.put(key(block.getLocation()), System.currentTimeMillis());
    }

    public boolean isRecent(Block block) {
        long k = key(block.getLocation());
        Long t = placedAt.get(k);
        if (t == null) return false;
        long now = System.currentTimeMillis();
        if (now - t <= ttlMs) return true;
        placedAt.remove(k, t);
        return false;
    }

    public boolean shouldBlockAndRecord(Block block) {
        long k = key(block.getLocation());
        long now = System.currentTimeMillis();
        Long prev = placedAt.put(k, now);
        if (prev == null) return false;
        return now - prev <= ttlMs;
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        placedAt.entrySet().removeIf(e -> now - e.getValue() > ttlMs);
    }

    private long key(Location loc) {
        World w = loc.getWorld();
        if (w == null) return 0L;

        long salt = worldSalt.computeIfAbsent(w.getUID(), u -> {
            long s = u.getMostSignificantBits() ^ u.getLeastSignificantBits();
            return s == 0L ? 0x9E3779B97F4A7C15L : s;
        });

        long x = loc.getBlockX();
        long y = loc.getBlockY();
        long z = loc.getBlockZ();

        long k1 = (x & 0x3FFFFFFL) << 38;
        long k2 = (z & 0x3FFFFFFL) << 12;
        long k3 = (y & 0xFFFL);

        return (k1 | k2 | k3) ^ salt;
    }
}
