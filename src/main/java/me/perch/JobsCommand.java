package me.perch;

import me.perch.data.JobProfile;
import me.perch.manager.JobConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class JobsCommand implements CommandExecutor, TabCompleter {

    private final Jobs plugin;

    public JobsCommand(Jobs plugin) {
        this.plugin = plugin;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("perchjobs.admin")) {
                sendMsg(sender, "command.no-permission");
                return true;
            }
            plugin.reloadConfig();
            plugin.loadMessages();
            plugin.getJobConfigManager().reload();
            sendMsg(sender, "command.reload-success");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("setlevel")) {
            if (!sender.hasPermission("perchjobs.admin.setlevel")) {
                sendMsg(sender, "command.no-permission");
                return true;
            }
            if (args.length != 4) {
                if (sender instanceof Player p) plugin.getMessageUtil().sendMessage(p, "<red>Usage: /perchjobs setlevel <player> <job> <level>");
                else sender.sendMessage("Usage: /perchjobs setlevel <player> <job> <level>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                if (sender instanceof Player p) plugin.getMessageUtil().sendMessage(p, "<red>Player not found.");
                else sender.sendMessage("Player not found.");
                return true;
            }

            String jobId = args[2].toLowerCase(Locale.ROOT);
            JobConfigManager.JobConfig cfg = plugin.getJobConfigManager().getJob(jobId);
            if (cfg == null) {
                if (sender instanceof Player p) plugin.getMessageUtil().sendMessage(p, "<red>Unknown job.");
                else sender.sendMessage("Unknown job.");
                return true;
            }

            int level;
            try {
                level = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                if (sender instanceof Player p) plugin.getMessageUtil().sendMessage(p, "<red>Level must be a number.");
                else sender.sendMessage("Level must be a number.");
                return true;
            }

            if (level < 1) {
                if (sender instanceof Player p) plugin.getMessageUtil().sendMessage(p, "<red>Level must be at least 1.");
                else sender.sendMessage("Level must be at least 1.");
                return true;
            }

            int max = cfg.getMaxLevel();
            if (level > max) level = max;

            JobProfile profile = plugin.getJobManager().getProfile(target.getUniqueId());
            if (profile == null) {
                if (sender instanceof Player p) plugin.getMessageUtil().sendMessage(p, "<red>Profile not loaded.");
                else sender.sendMessage("Profile not loaded.");
                return true;
            }

            synchronized (profile) {
                if (!profile.isJoined(jobId)) profile.joinJob(jobId);
                profile.setLevel(jobId, level);
                profile.setXp(jobId, 0.0);
            }

            String ok = "Set " + target.getName() + "'s " + jobId + " level to " + level + ".";
            if (sender instanceof Player p) plugin.getMessageUtil().sendMessage(p, "<green>" + ok);
            else sender.sendMessage(ok);

            plugin.getMessageUtil().sendMessage(target, "<green>Your " + cfg.getDisplayName() + " level was set to <white>" + level + "<green>.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sendMsg(sender, "command.players-only");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        JobProfile profile = plugin.getJobManager().getProfile(player.getUniqueId());
        if (profile == null) {
            sendMsg(player, "profile-loading");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "join" -> {
                if (!player.hasPermission("perchjobs.join")) {
                    sendMsg(player, "command.no-permission");
                    return true;
                }

                if (args.length < 2) {
                    plugin.getMessageUtil().sendMessage(player, "<red>Usage: /jobs join <job>");
                    return true;
                }
                String jobRaw = args[1].toLowerCase(Locale.ROOT);
                String jobDisplay = capitalize(jobRaw);

                if (!player.hasPermission("perchjobs.join." + jobRaw) && !player.hasPermission("perchjobs.join.*")) {
                    sendMsg(player, "command.no-permission");
                    return true;
                }

                if (plugin.getJobConfigManager().getJob(jobRaw) == null) {
                    sendMsg(player, "jobs.invalid-job");
                    return true;
                }

                synchronized (profile) {
                    if (profile.isJoined(jobRaw)) {
                        sendMsg(player, "jobs.already-joined", "%job%", jobDisplay);
                        return true;
                    }

                    int limit = plugin.getJobManager().getPlayerJobLimit(player);
                    long currentJobs = plugin.getJobConfigManager().getAllJobIds().stream()
                            .filter(profile::isJoined)
                            .count();

                    if (currentJobs >= limit) {
                        sendMsg(player, "jobs.limit-reached");
                        return true;
                    }

                    profile.joinJob(jobRaw);
                }

                sendMsg(player, "jobs.join-success", "%job%", jobDisplay);
            }
            case "leave" -> {
                if (!player.hasPermission("perchjobs.leave")) {
                    sendMsg(player, "command.no-permission");
                    return true;
                }

                if (args.length < 2) {
                    plugin.getMessageUtil().sendMessage(player, "<red>Usage: /jobs leave <job>");
                    return true;
                }
                String jobRaw = args[1].toLowerCase(Locale.ROOT);
                String jobDisplay = capitalize(jobRaw);

                synchronized (profile) {
                    if (!profile.isJoined(jobRaw)) {
                        sendMsg(player, "jobs.not-joined", "%job%", jobDisplay);
                        return true;
                    }
                    profile.leaveJob(jobRaw);
                }

                sendMsg(player, "jobs.leave-success", "%job%", jobDisplay);
            }
            case "info" -> {
                if (!player.hasPermission("perchjobs.info")) {
                    sendMsg(player, "command.no-permission");
                    return true;
                }

                plugin.getMessageUtil().sendMessage(player, "<gradient:#adf3fd:#FD9113><bold>Your Jobs</bold></gradient>");
                boolean found = false;

                synchronized (profile) {
                    for (String id : plugin.getJobConfigManager().getAllJobIds()) {
                        if (profile.isJoined(id)) {
                            found = true;
                            int lvl = profile.getLevel(id);
                            long req = plugin.getJobConfigManager().getJob(id).getRequiredXp(lvl);
                            String reqStr = (req == -1) ? "Max" : String.valueOf(req);

                            plugin.getMessageUtil().sendMessage(player,
                                    " <gray>• <yellow>" + capitalize(id) + " <gray>Lvl <white>" + lvl + " <gray>(" + (int) profile.getXp(id) + "/" + reqStr + " XP)");
                        }
                    }
                }

                if (!found) plugin.getMessageUtil().sendMessage(player, " <gray>You have not joined any jobs.");
            }
            case "debug" -> {
                if (!sender.hasPermission("perchjobs.admin")) {
                    sendMsg(sender, "command.no-permission");
                    return true;
                }
                plugin.getDebugManager().toggleDebug(player);
            }
            default -> {
                sendMsg(player, "command.unknown-command");
                sendUsage(player);
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("perchjobs.join")) suggestions.add("join");
            if (sender.hasPermission("perchjobs.leave")) suggestions.add("leave");
            if (sender.hasPermission("perchjobs.info")) suggestions.add("info");
            if (sender.hasPermission("perchjobs.admin")) {
                suggestions.add("reload");
                suggestions.add("debug");
            }
            if (sender.hasPermission("perchjobs.admin.setlevel")) suggestions.add("setlevel");
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("setlevel") && sender.hasPermission("perchjobs.admin.setlevel")) {
                suggestions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
            } else if (sender instanceof Player player) {
                JobProfile profile = plugin.getJobManager().getProfile(player.getUniqueId());
                if (profile != null) {
                    if (args[0].equalsIgnoreCase("join") && player.hasPermission("perchjobs.join")) {
                        int limit = plugin.getJobManager().getPlayerJobLimit(player);
                        long currentCount = plugin.getJobConfigManager().getAllJobIds().stream()
                                .filter(profile::isJoined).count();

                        if (currentCount < limit) {
                            plugin.getJobConfigManager().getAllJobIds().forEach(job -> {
                                if (!profile.isJoined(job) && (player.hasPermission("perchjobs.join." + job) || player.hasPermission("perchjobs.join.*"))) {
                                    suggestions.add(job);
                                }
                            });
                        }
                    } else if (args[0].equalsIgnoreCase("leave") && player.hasPermission("perchjobs.leave")) {
                        plugin.getJobConfigManager().getAllJobIds().forEach(job -> {
                            if (profile.isJoined(job)) suggestions.add(job);
                        });
                    }
                }
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("setlevel") && sender.hasPermission("perchjobs.admin.setlevel")) {
                suggestions.addAll(plugin.getJobConfigManager().getAllJobIds());
            }
        }

        String currentArg = args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(currentArg))
                .collect(Collectors.toList());
    }

    private void sendUsage(Player p) {
        for (String line : plugin.getMessagesConfig().getStringList("command.usage")) {
            plugin.getMessageUtil().sendMessage(p, line);
        }
    }

    private void sendMsg(CommandSender sender, String key) {
        String msg = plugin.getMessagesConfig().getString(key);
        if (msg != null) plugin.getMessageUtil().sendMessage(sender, msg);
    }

    private void sendMsg(CommandSender sender, String key, String ph, String val) {
        String msg = plugin.getMessagesConfig().getString(key);
        if (msg != null) plugin.getMessageUtil().sendMessage(sender, msg.replace(ph, val));
    }
}
