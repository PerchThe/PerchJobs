package me.perch.hooks;

import me.perch.Jobs;
import me.perch.data.JobProfile;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.stream.Collectors;

public class PerchJobsExpansion extends PlaceholderExpansion {

    private final Jobs plugin;

    public PerchJobsExpansion(Jobs plugin) { this.plugin = plugin; }
    @Override public @NotNull String getIdentifier() { return "perchjobs"; }
    @Override public @NotNull String getAuthor() { return "Perch"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String fmt0(long v) {
        return String.valueOf(v);
    }

    private String fmt0(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        return String.valueOf(Math.round(v));
    }

    private String fmt1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.0";
        return String.format(Locale.US, "%.1f", v);
    }

    private String fmt2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.00";
        return String.format(Locale.US, "%.2f", v);
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        String p = params.toLowerCase(Locale.ROOT);

        if (p.equals("limit")) {
            if (player.isOnline()) return fmt0(plugin.getJobManager().getPlayerJobLimit(player.getPlayer()));
            return fmt0(plugin.getConfig().getInt("jobs.limit", 2));
        }

        JobProfile profile = plugin.getJobManager().getProfile(player.getUniqueId());

        if (p.equals("jobs_count")) {
            if (profile == null) return "0";
            long count = plugin.getJobConfigManager().getAllJobIds().stream()
                    .filter(profile::isJoined)
                    .count();
            return fmt0(count);
        }

        if (p.equals("jobs_list")) {
            if (profile == null) return "";
            return plugin.getJobConfigManager().getAllJobIds().stream()
                    .filter(profile::isJoined)
                    .map(this::capitalize)
                    .collect(Collectors.joining(", "));
        }

        if (p.equals("total_level")) {
            if (profile == null) return "0";
            long total = 0;
            for (String job : plugin.getJobConfigManager().getAllJobIds()) {
                total += profile.getLevel(job);
            }
            return fmt0(total);
        }

        if (p.startsWith("count_")) return fmt0(plugin.getLeaderboardManager().getCount(p.replace("count_", "")));

        if (p.startsWith("rank_")) {
            int rank = plugin.getLeaderboardManager().getRank(p.replace("rank_", ""), player.getUniqueId());
            return (rank == 0) ? ">100" : String.valueOf(rank);
        }

        if (p.startsWith("top_name_")) {
            try {
                String[] parts = p.split("_");
                int rank = Integer.parseInt(parts[parts.length - 1]);
                String job = p.replace("top_name_", "").replace("_" + rank, "");
                var topUUID = plugin.getLeaderboardManager().getPlayerAtRank(job, rank);
                return (topUUID != null) ? plugin.getServer().getOfflinePlayer(topUUID).getName() : "---";
            } catch (Exception e) { return "---"; }
        }

        if (profile == null) return "";

        if (p.startsWith("bonus_")) {
            String job = p.substring("bonus_".length());
            return fmt1(profile.getTenureBonusPercent(job));
        }

        if (p.startsWith("level_")) {
            String job = p.replace("level_", "");
            return String.valueOf(profile.getLevel(job));
        }

        if (p.startsWith("income_")) {
            String job = p.replace("income_", "");
            var config = plugin.getJobConfigManager().getJob(job);
            if (config == null) return "0.00";
            int level = profile.getLevel(job);
            double income = config.getIncome(level);
            return fmt2(income);
        }

        if (p.startsWith("xp_total_")) {
            String job = p.replace("xp_total_", "");
            var config = plugin.getJobConfigManager().getJob(job);
            if (config == null) return "0.0";
            int level = profile.getLevel(job);
            double total = profile.getXp(job) + config.getCumulativeXpBeforeLevel(level);
            return fmt1(total);
        }

        if (p.startsWith("xp_") && !p.contains("_req") && !p.contains("_percent") && !p.contains("_total")) {
            String job = p.replace("xp_", "");
            return fmt1(profile.getXp(job));
        }

        if (p.startsWith("xp_req_")) {
            String job = p.replace("xp_req_", "");
            var config = plugin.getJobConfigManager().getJob(job);
            if (config == null) return "0";
            long req = config.getRequiredXp(profile.getLevel(job));
            return (req == -1) ? "Max" : fmt0(req);
        }

        if (p.startsWith("xp_percent_")) {
            String job = p.replace("xp_percent_", "");
            var config = plugin.getJobConfigManager().getJob(job);
            if (config == null) return "0.0";
            long req = config.getRequiredXp(profile.getLevel(job));
            if (req <= 0) return fmt1(100);
            double pct = (profile.getXp(job) / req) * 100.0;
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            return fmt1(pct);
        }

        if (p.startsWith("in_job_")) return String.valueOf(profile.isJoined(p.replace("in_job_", "")));

        return null;
    }
}
