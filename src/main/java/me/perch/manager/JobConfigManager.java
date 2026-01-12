package me.perch.manager;

import me.perch.Jobs;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class JobConfigManager {

    private final Jobs plugin;
    private volatile Map<String, JobConfig> jobs = Map.of();
    private volatile Map<Material, List<String>> materialCache = Map.of();

    public JobConfigManager(Jobs plugin) {
        this.plugin = plugin;
        loadJobs();
    }

    public void reload() {
        loadJobs();
    }

    public JobConfig getJob(String id) {
        return jobs.get(id);
    }

    public Set<String> getAllJobIds() {
        return jobs.keySet();
    }

    public List<String> getJobsForBlock(Material mat) {
        return materialCache.getOrDefault(mat, List.of());
    }

    public boolean isWhitelistedFor(String jobId, Material mat) {
        JobConfig cfg = jobs.get(jobId);
        if (cfg == null) return false;
        Set<Material> wl = cfg.getWhitelist();
        return !wl.isEmpty() && wl.contains(mat);
    }

    private void loadJobs() {
        int maxLevel = plugin.getConfig().getInt("max-level", 100);

        File jobsFolder = new File(plugin.getDataFolder(), "jobs");
        if (!jobsFolder.exists()) jobsFolder.mkdirs();

        String[] defaults = {"miner.yml", "lumberjack.yml", "farmer.yml", "builder.yml", "fisherman.yml"};
        for (String def : defaults) {
            File file = new File(jobsFolder, def);
            if (!file.exists()) {
                try { plugin.saveResource("jobs/" + def, false); } catch (Exception ignored) {}
            }
        }

        File[] files = jobsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        Map<String, JobConfig> newJobs = new HashMap<>();
        Map<Material, List<String>> newCache = new HashMap<>();

        for (File file : files) {
            String id = file.getName().replace(".yml", "").toLowerCase(Locale.ROOT);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

            Set<Material> whitelist = new HashSet<>();
            for (String s : config.getStringList("whitelist")) {
                try {
                    whitelist.add(Material.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (Exception ignored) {}
            }

            for (Material mat : whitelist) {
                newCache.computeIfAbsent(mat, k -> new ArrayList<>()).add(id);
            }

            Set<Material> blacklist = new HashSet<>();
            for (String s : config.getStringList("blacklist")) {
                try { blacklist.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); } catch (Exception ignored) {}
            }

            Set<Material> allowedTools = new HashSet<>();
            for (String s : config.getStringList("tools")) {
                try { allowedTools.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); } catch (Exception ignored) {}
            }

            String xpFormula = config.getString("xp-req-formula", "100 * (1.085 ^ (level - 1))");
            long[] xpTable = new long[maxLevel + 2];
            try {
                for (int lvl = 1; lvl <= maxLevel; lvl++) {
                    Expression e = new ExpressionBuilder(xpFormula).variables("level").build().setVariable("level", lvl);
                    xpTable[lvl] = (long) e.evaluate();
                }
            } catch (Exception ex) {
                Arrays.fill(xpTable, Long.MAX_VALUE);
                plugin.getLogger().warning("Invalid XP Formula for job " + id);
            }

            String incomeFormula = config.getString("income-formula", "0.05 + (level * 0.036)");
            double[] incomeTable = new double[maxLevel + 2];
            try {
                for (int lvl = 1; lvl <= maxLevel; lvl++) {
                    Expression e = new ExpressionBuilder(incomeFormula).variables("level").build().setVariable("level", lvl);
                    double val = e.evaluate();
                    incomeTable[lvl] = Math.max(0.0, val);
                }
            } catch (Exception ex) {
                Arrays.fill(incomeTable, 0.0);
                plugin.getLogger().warning("Invalid Income Formula for job " + id);
            }

            double stackDecay = config.getDouble("stack-decay-multiplier", 1.0);
            int maxActions = config.getInt("max-actions-per-second", 0);

            JobConfig jobConfig = new JobConfig(
                    id,
                    config.getString("display-name", id),
                    config.getDouble("xp-per-action", 1.0),
                    stackDecay,
                    maxActions,
                    whitelist,
                    blacklist,
                    allowedTools,
                    xpTable,
                    incomeTable,
                    maxLevel
            );

            newJobs.put(id, jobConfig);
            plugin.getLogger().info("Loaded job: " + id);
        }

        Map<Material, List<String>> frozenCache = new HashMap<>();
        for (Map.Entry<Material, List<String>> e : newCache.entrySet()) {
            frozenCache.put(e.getKey(), List.copyOf(e.getValue()));
        }

        jobs = Map.copyOf(newJobs);
        materialCache = Map.copyOf(frozenCache);
    }

    public static class JobConfig {
        private final String id;
        private final String displayName;
        private final double xpPerAction;
        private final double stackDecayMultiplier;
        private final int maxActionsPerSecond;
        private final Set<Material> whitelist;
        private final Set<Material> blacklist;
        private final Set<Material> allowedTools;
        private final long[] xpTable;
        private final long[] xpPrefix;
        private final double[] incomeTable;
        private final int maxLevel;

        public JobConfig(String id, String displayName, double xpPerAction,
                         double stackDecayMultiplier, int maxActionsPerSecond,
                         Set<Material> whitelist, Set<Material> blacklist, Set<Material> allowedTools,
                         long[] xpTable, double[] incomeTable, int maxLevel) {
            this.id = id;
            this.displayName = displayName;
            this.xpPerAction = xpPerAction;
            this.stackDecayMultiplier = stackDecayMultiplier;
            this.maxActionsPerSecond = maxActionsPerSecond;
            this.whitelist = Set.copyOf(whitelist);
            this.blacklist = Set.copyOf(blacklist);
            this.allowedTools = Set.copyOf(allowedTools);
            this.xpTable = xpTable;
            this.incomeTable = incomeTable;
            this.maxLevel = maxLevel;

            this.xpPrefix = new long[maxLevel + 2];
            long acc = 0L;
            xpPrefix[1] = 0L;
            for (int lvl = 2; lvl <= maxLevel; lvl++) {
                long prevReq = xpTable[lvl - 1];
                if (prevReq > 0 && acc < Long.MAX_VALUE - prevReq) acc += prevReq;
                else acc = Long.MAX_VALUE;
                xpPrefix[lvl] = acc;
            }
        }

        public boolean isValidBlock(Material mat) {
            if (!blacklist.isEmpty() && blacklist.contains(mat)) return false;
            if (!whitelist.isEmpty() && !whitelist.contains(mat)) return false;
            return true;
        }

        public boolean isValidTool(Material tool) {
            if (allowedTools.isEmpty()) return true;
            return allowedTools.contains(tool);
        }

        public long getRequiredXp(int level) {
            if (level >= maxLevel) return -1;
            if (level < 1) return xpTable[1];
            return xpTable[level];
        }

        public long getCumulativeXpBeforeLevel(int level) {
            if (level <= 1) return 0L;
            if (level > maxLevel) return xpPrefix[maxLevel];
            return xpPrefix[level];
        }

        public double getIncome(int level) {
            if (level < 1) return incomeTable[1];
            if (level > maxLevel) return incomeTable[maxLevel];
            return incomeTable[level];
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }
        public double getXpPerAction() { return xpPerAction; }
        public double getStackDecayMultiplier() { return stackDecayMultiplier; }
        public int getMaxActionsPerSecond() { return maxActionsPerSecond; }
        public Set<Material> getWhitelist() { return whitelist; }
        public Set<Material> getBlacklist() { return blacklist; }
        public Set<Material> getAllowedTools() { return allowedTools; }
        public int getMaxLevel() { return maxLevel; }
    }
}
