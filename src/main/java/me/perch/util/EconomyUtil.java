package me.perch.util;

import me.perch.Jobs;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class EconomyUtil {
    private final Jobs plugin;
    private final Economy economy;
    private final boolean enabled;

    private final ConcurrentHashMap<UUID, LongAdder> pendingCents = new ConcurrentHashMap<>();

    public EconomyUtil(Jobs plugin) {
        this.plugin = plugin;

        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault not found! Money disabled.");
            this.economy = null;
            this.enabled = false;
            return;
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("No Economy Provider found! Money disabled.");
            this.economy = null;
            this.enabled = false;
            return;
        }

        this.economy = rsp.getProvider();
        this.enabled = this.economy != null;

        if (enabled) {
            Bukkit.getScheduler().runTaskTimer(plugin, () -> flush(500), 20L, 20L);
        }
    }

    public void deposit(UUID uuid, double amount) {
        if (!enabled || economy == null || amount <= 0 || uuid == null) return;

        long cents = Math.round(amount * 100.0);
        if (cents <= 0) return;

        pendingCents.computeIfAbsent(uuid, k -> new LongAdder()).add(cents);
    }

    public void deposit(org.bukkit.entity.Player player, double amount) {
        if (player == null) return;
        deposit(player.getUniqueId(), amount);
    }

    public void flushAll() {
        if (!enabled || economy == null) return;
        while (!pendingCents.isEmpty()) flush(Integer.MAX_VALUE);
    }

    private void flush(int maxPlayers) {
        if (!enabled || economy == null) return;

        int budget = Math.max(1, maxPlayers);
        Iterator<Map.Entry<UUID, LongAdder>> it = pendingCents.entrySet().iterator();

        while (it.hasNext() && budget-- > 0) {
            Map.Entry<UUID, LongAdder> e = it.next();
            long cents = e.getValue().sumThenReset();

            if (cents <= 0) {
                if (e.getValue().sum() == 0) it.remove();
                continue;
            }

            OfflinePlayer p = Bukkit.getOfflinePlayer(e.getKey());
            economy.depositPlayer(p, cents / 100.0);

            if (e.getValue().sum() == 0) it.remove();
        }
    }
}
