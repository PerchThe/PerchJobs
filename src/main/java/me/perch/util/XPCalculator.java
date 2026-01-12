package me.perch.util;

import me.perch.Jobs;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;

public class XPCalculator {

    private final long[] xpCache;
    private final int maxLevel;

    public XPCalculator(Jobs plugin) {
        this.maxLevel = plugin.getConfig().getInt("max-level", 100);
        this.xpCache = new long[maxLevel + 2];

        String formula = plugin.getConfig().getString("level-formula", "100 * (1.085 ^ (level - 1))");
        plugin.getLogger().info("Caching XP formula: " + formula);

        for (int lvl = 1; lvl <= maxLevel; lvl++) {
            try {
                Expression e = new ExpressionBuilder(formula)
                        .variables("level")
                        .build()
                        .setVariable("level", lvl);
                this.xpCache[lvl] = (long) e.evaluate();
            } catch (Exception ex) {
                plugin.getLogger().severe("Bad formula at level " + lvl + ": " + ex.getMessage());
                this.xpCache[lvl] = Long.MAX_VALUE;
            }
        }
    }

    public long getXpRequired(int level) {
        if (level >= maxLevel) return -1;
        if (level < 1) return xpCache[1];
        return xpCache[level];
    }
}