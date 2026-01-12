package me.perch.manager;

import me.perch.Jobs;
import me.perch.data.JobProfile;
import me.perch.util.PlacedBlockTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class JobManager implements Listener {

    private final Jobs plugin;
    private final Map<UUID, JobProfile> activeProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, StrictRateLimiter>> rateLimiters = new ConcurrentHashMap<>();
    private final PlacedBlockTracker placedBlockTracker = new PlacedBlockTracker(3000L);
    private final PlacedBlockTracker builderCooldownTracker = new PlacedBlockTracker(3000L);

    public JobManager(Jobs plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            placedBlockTracker.cleanup();
            builderCooldownTracker.cleanup();
        }, 100L, 100L);
        for (Player p : Bukkit.getOnlinePlayers()) loadProfile(p.getUniqueId());
    }

    public void loadProfile(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = plugin.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT data FROM job_data WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                JobProfile profile = rs.next() ? JobProfile.deserialize(rs.getString("data")) : new JobProfile();
                activeProfiles.put(uuid, profile);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        loadProfile(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        JobProfile profile = activeProfiles.remove(uuid);
        if (profile != null && profile.isDirty()) saveProfileAsync(uuid, profile);
        rateLimiters.remove(uuid);
    }

    public void saveAllDirty() {
        activeProfiles.forEach((uuid, profile) -> {
            if (profile.isDirty()) saveProfileAsync(uuid, profile);
        });
    }

    public void shutdown() {
        activeProfiles.forEach((uuid, profile) -> {
            if (profile.isDirty()) {
                try {
                    saveProfileSync(uuid, profile);
                } catch (Exception e) {
                    if (!e.getMessage().contains("SQLITE_READONLY_DBMOVED")) e.printStackTrace();
                }
            }
        });
    }

    public int getPlayerJobLimit(Player p) {
        for (int i = 20; i >= 1; i--) {
            if (p.hasPermission("perchjobs.limit." + i)) return i;
        }
        return plugin.getConfig().getInt("jobs.limit", 2);
    }

    private void saveProfileAsync(UUID uuid, JobProfile profile) {
        CompletableFuture.runAsync(() -> saveProfileSync(uuid, profile));
    }

    private void saveProfileSync(UUID uuid, JobProfile profile) {
        final long rev;
        final String json;
        final Map<String, Integer> levelsSnapshot;
        final Map<String, Double> xpSnapshot;

        synchronized (profile) {
            rev = profile.getRevision();
            json = profile.serialize();
            levelsSnapshot = profile.snapshotLevels();
            xpSnapshot = profile.snapshotXp();
        }

        try (Connection conn = plugin.getDataSource().getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO job_data(uuid, data) VALUES(?, ?) " +
                            "ON CONFLICT(uuid) DO UPDATE SET data=excluded.data")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, json);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO job_levels (uuid, job_id, level, xp) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT(uuid, job_id) DO UPDATE SET level=excluded.level, xp=excluded.xp")) {
                for (Map.Entry<String, Integer> entry : levelsSnapshot.entrySet()) {
                    String job = entry.getKey();
                    ps.setString(1, uuid.toString());
                    ps.setString(2, job);
                    ps.setInt(3, entry.getValue());
                    ps.setDouble(4, xpSnapshot.getOrDefault(job, 0.0));
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            conn.setAutoCommit(true);

            synchronized (profile) {
                if (profile.getRevision() == rev) profile.setClean();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public JobProfile getProfile(UUID uuid) {
        return activeProfiles.get(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        Material mat = e.getBlock().getType();

        JobProfile profile = activeProfiles.get(uuid);
        if (profile == null) return;

        var jcm = plugin.getJobConfigManager();
        if (jcm.isWhitelistedFor("farmer", mat) || jcm.isWhitelistedFor("lumberjack", mat) || jcm.isWhitelistedFor("miner", mat)) {
            placedBlockTracker.record(e.getBlock());
        }

        var cfg = jcm.getJob("builder");
        if (cfg == null) return;
        if (!profile.isJoined("builder")) return;

        plugin.getDebugManager().recordAttempt(uuid, "builder", 1.0);

        if (!cfg.isValidBlock(mat)) return;
        if (!tryRateLimit(uuid, "builder", cfg.getMaxActionsPerSecond())) return;

        boolean blocked = builderCooldownTracker.isRecent(e.getBlock());
        builderCooldownTracker.record(e.getBlock());
        if (blocked) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> processAction(uuid, "builder", mat, 1.0));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBerryHarvest(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isRightClick()) return;

        Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        if (clicked.getType() != Material.SWEET_BERRY_BUSH) return;
        if (!(clicked.getBlockData() instanceof Ageable ageable)) return;

        int beforeAge = ageable.getAge();
        if (beforeAge < 2) return;

        UUID uuid = e.getPlayer().getUniqueId();
        JobProfile profile = activeProfiles.get(uuid);
        if (profile == null) return;
        if (!profile.isJoined("farmer")) return;

        var cfg = plugin.getJobConfigManager().getJob("farmer");
        if (cfg == null) return;

        plugin.getDebugManager().recordAttempt(uuid, "farmer", 1.0);

        Material mat = Material.SWEET_BERRY_BUSH;
        if (!cfg.isValidBlock(mat)) return;
        if (!tryRateLimit(uuid, "farmer", cfg.getMaxActionsPerSecond())) return;

        if (plugin.getJobConfigManager().isWhitelistedFor("farmer", mat)) {
            if (placedBlockTracker.isRecent(clicked)) return;
        }

        Location loc = clicked.getLocation();

        Bukkit.getScheduler().runTask(plugin, () -> {
            Block now = loc.getBlock();
            if (now.getType() != Material.SWEET_BERRY_BUSH) return;
            if (!(now.getBlockData() instanceof Ageable after)) return;
            if (after.getAge() >= beforeAge) return;

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> processAction(uuid, "farmer", Material.SWEET_BERRY_BUSH, 1.0));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        UUID uuid = e.getPlayer().getUniqueId();
        Material mat = block.getType();

        List<String> potentialJobs = plugin.getJobConfigManager().getJobsForBlock(mat);
        if (potentialJobs.isEmpty()) return;

        JobProfile profile = activeProfiles.get(uuid);
        if (profile == null) return;

        if (builderCooldownTracker.isRecent(block)) return;

        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        Material toolType = hand.getType();

        int extraBlocks = 0;
        if (mat == Material.SUGAR_CANE || mat == Material.BAMBOO || mat == Material.CACTUS) {
            Block above = block.getRelative(BlockFace.UP);
            while (above.getType() == mat) {
                extraBlocks++;
                above = above.getRelative(BlockFace.UP);
            }
        }
        final int finalExtra = extraBlocks;

        List<JobAction> actions = new ArrayList<>();

        for (String jobId : potentialJobs) {
            if (!profile.isJoined(jobId)) continue;

            plugin.getDebugManager().recordAttempt(uuid, jobId, 1.0);

            var cfg = plugin.getJobConfigManager().getJob(jobId);
            if (cfg == null) continue;

            if (!cfg.isValidTool(toolType)) continue;
            if (!cfg.isValidBlock(mat)) continue;
            if (!tryRateLimit(uuid, jobId, cfg.getMaxActionsPerSecond())) continue;

            boolean applyRecentPlaced = jobId.equals("farmer") || jobId.equals("lumberjack") || jobId.equals("miner");
            if (applyRecentPlaced && plugin.getJobConfigManager().isWhitelistedFor(jobId, mat)) {
                if (placedBlockTracker.isRecent(block)) continue;
            }

            if (jobId.equals("farmer")) {
                boolean requireFullyGrown =
                        mat == Material.WHEAT ||
                                mat == Material.POTATOES ||
                                mat == Material.CARROTS ||
                                mat == Material.BEETROOTS ||
                                mat == Material.NETHER_WART ||
                                mat == Material.COCOA ||
                                mat == Material.SWEET_BERRY_BUSH;

                if (requireFullyGrown) {
                    if (!(block.getBlockData() instanceof Ageable crop)) continue;
                    if (crop.getAge() < crop.getMaximumAge()) continue;
                }
            }

            double decay = cfg.getStackDecayMultiplier();
            double totalAmount = 1.0;
            double currentBlockValue = 1.0;

            for (int i = 0; i < finalExtra; i++) {
                currentBlockValue *= decay;
                totalAmount += currentBlockValue;
            }

            actions.add(new JobAction(jobId, totalAmount));
        }

        if (actions.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (JobAction a : actions) {
                processAction(uuid, a.jobId, mat, a.amount);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(e.getCaught() instanceof Item item)) return;

        UUID uuid = e.getPlayer().getUniqueId();
        Material mat = item.getItemStack().getType();

        JobProfile profile = activeProfiles.get(uuid);
        if (profile == null) return;

        var cfg = plugin.getJobConfigManager().getJob("fisherman");
        if (cfg == null) return;

        if (profile.isJoined("fisherman")) plugin.getDebugManager().recordAttempt(uuid, "fisherman", 1.0);

        if (!cfg.isValidBlock(mat)) return;
        if (!tryRateLimit(uuid, "fisherman", cfg.getMaxActionsPerSecond())) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> processAction(uuid, "fisherman", mat, 1.0));
    }

    private boolean tryRateLimit(UUID uuid, String jobId, int maxActions) {
        if (maxActions <= 0) return true;
        Map<String, StrictRateLimiter> userBuckets = rateLimiters.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        StrictRateLimiter limiter = userBuckets.computeIfAbsent(jobId, k -> new StrictRateLimiter(maxActions));
        return limiter.tryAction();
    }

    private void processAction(UUID uuid, String jobId, Material mat, double amount) {
        JobProfile profile = activeProfiles.get(uuid);
        if (profile == null || !profile.isJoined(jobId)) return;

        var cfg = plugin.getJobConfigManager().getJob(jobId);
        if (cfg == null) return;
        if (!cfg.isValidBlock(mat)) return;

        plugin.getDebugManager().recordSuccess(uuid, jobId, amount);

        double moneyToPay;
        boolean leveled;
        int newLevelVal;
        String displayName = cfg.getDisplayName();

        synchronized (profile) {
            double gainedXp = cfg.getXpPerAction() * amount;
            profile.addXp(jobId, gainedXp);

            int currentLevel = profile.getLevel(jobId);
            double tenureMult = profile.getTenureMultiplier(jobId);
            moneyToPay = cfg.getIncome(currentLevel) * amount * tenureMult;

            long req = cfg.getRequiredXp(currentLevel);
            leveled = req != -1 && profile.getXp(jobId) >= req;
            newLevelVal = currentLevel;

            if (leveled) {
                newLevelVal = currentLevel + 1;
                profile.setLevel(jobId, newLevelVal);
                profile.setXp(jobId, profile.getXp(jobId) - req);
            }
        }

        if (moneyToPay <= 0 && !leveled) return;

        final int finalNewLevel = newLevelVal;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                if (moneyToPay > 0) plugin.getEconomyUtil().deposit(p, moneyToPay);

                if (leveled) {
                    String msg = plugin.getMessagesConfig().getString("level-up", "Level Up!")
                            .replace("%job%", displayName)
                            .replace("%level%", String.valueOf(finalNewLevel));
                    plugin.getMessageUtil().sendMessage(p, msg);
                }
            }
        });
    }

    private static class JobAction {
        final String jobId;
        final double amount;

        JobAction(String jobId, double amount) {
            this.jobId = jobId;
            this.amount = amount;
        }
    }

    private static class StrictRateLimiter {
        private final int maxActions;
        private long currentSecond;
        private int count;

        public StrictRateLimiter(int maxActions) {
            this.maxActions = maxActions;
            this.currentSecond = System.currentTimeMillis() / 1000;
            this.count = 0;
        }

        public synchronized boolean tryAction() {
            long nowSec = System.currentTimeMillis() / 1000;
            if (nowSec > currentSecond) {
                currentSecond = nowSec;
                count = 0;
            }
            if (count < maxActions) {
                count++;
                return true;
            }
            return false;
        }
    }
}
