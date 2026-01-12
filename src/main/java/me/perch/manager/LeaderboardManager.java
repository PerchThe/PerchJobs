package me.perch.manager;

import me.perch.Jobs;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LeaderboardManager {

    private final Jobs plugin;

    private volatile Map<String, List<UUID>> topLists = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> totalPlayers = new ConcurrentHashMap<>();

    public LeaderboardManager(Jobs plugin) {
        this.plugin = plugin;

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 100L, 6000L);
    }

    private void refresh() {
        if (plugin.getDataSource() == null) return;

        Set<String> jobs = plugin.getJobConfigManager().getAllJobIds();
        Map<String, List<UUID>> newTopLists = new HashMap<>();
        Map<String, Integer> newTotals = new HashMap<>();

        try (Connection conn = plugin.getDataSource().getConnection()) {

            for (String job : jobs) {

                List<UUID> top = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT uuid FROM job_levels WHERE job_id=? ORDER BY level DESC, xp DESC LIMIT 100")) {
                    ps.setString(1, job);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        top.add(UUID.fromString(rs.getString("uuid")));
                    }
                }
                newTopLists.put(job, top);


                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM job_levels WHERE job_id=?")) {
                    ps.setString(1, job);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) newTotals.put(job, rs.getInt(1));
                }
            }

            topLists = Map.copyOf(newTopLists);
            totalPlayers = Map.copyOf(newTotals);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getRank(String jobId, UUID uuid) {
        List<UUID> list = topLists.get(jobId);
        if (list == null) return 0;
        int index = list.indexOf(uuid);
        return (index == -1) ? 0 : index + 1;
    }

    public int getCount(String jobId) {
        return totalPlayers.getOrDefault(jobId, 0);
    }

    public UUID getPlayerAtRank(String jobId, int rank) {
        List<UUID> list = topLists.get(jobId);
        if (list == null || rank < 1 || rank > list.size()) return null;
        return list.get(rank - 1);
    }
}