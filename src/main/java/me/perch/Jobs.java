package me.perch;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.perch.hooks.PerchJobsExpansion;
import me.perch.manager.DebugManager;
import me.perch.manager.JobConfigManager;
import me.perch.manager.JobManager;
import me.perch.manager.LeaderboardManager;
import me.perch.util.EconomyUtil;
import me.perch.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Jobs extends JavaPlugin {

    private static Jobs instance;
    private HikariDataSource dataSource;
    private JobManager jobManager;
    private JobConfigManager jobConfigManager;
    private LeaderboardManager leaderboardManager;
    private DebugManager debugManager;
    private MessageUtil messageUtil;
    private EconomyUtil economyUtil;
    private FileConfiguration messagesConfig;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadMessages();
        initDatabase();

        this.messageUtil = new MessageUtil();
        this.economyUtil = new EconomyUtil(this);
        this.jobConfigManager = new JobConfigManager(this);
        this.leaderboardManager = new LeaderboardManager(this);
        this.debugManager = new DebugManager(this);
        this.jobManager = new JobManager(this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PerchJobsExpansion(this).register();
        }

        JobsCommand cmdExecutor = new JobsCommand(this);

        if (getCommand("jobs") != null) {
            getCommand("jobs").setExecutor(cmdExecutor);
            getCommand("jobs").setTabCompleter(cmdExecutor);
        }

        if (getCommand("perchjobs") != null) {
            getCommand("perchjobs").setExecutor(cmdExecutor);
            getCommand("perchjobs").setTabCompleter(cmdExecutor);
        }

        long interval = getConfig().getLong("auto-save-interval", 12000L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, jobManager::saveAllDirty, interval, interval);
    }

    @Override
    public void onDisable() {
        if (jobManager != null) {
            jobManager.shutdown();
        }
        if (dataSource != null) dataSource.close();
    }

    public void loadMessages() {
        File msgFile = new File(getDataFolder(), "messages.yml");
        if (!msgFile.exists()) saveResource("messages.yml", false);
        this.messagesConfig = YamlConfiguration.loadConfiguration(msgFile);
    }

    private void initDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + new File(getDataFolder(), "jobs.db").getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");

        this.dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS job_data (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "data TEXT)");

            stmt.execute("CREATE TABLE IF NOT EXISTS job_levels (" +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "job_id VARCHAR(32) NOT NULL, " +
                    "level INT NOT NULL, " +
                    "xp DOUBLE NOT NULL, " +
                    "PRIMARY KEY (uuid, job_id))");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_job_rank ON job_levels(job_id, level DESC, xp DESC)");

        } catch (SQLException e) {
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public static Jobs getInstance() { return instance; }
    public HikariDataSource getDataSource() { return dataSource; }
    public JobManager getJobManager() { return jobManager; }
    public JobConfigManager getJobConfigManager() { return jobConfigManager; }
    public LeaderboardManager getLeaderboardManager() { return leaderboardManager; }
    public DebugManager getDebugManager() { return debugManager; }
    public MessageUtil getMessageUtil() { return messageUtil; }
    public EconomyUtil getEconomyUtil() { return economyUtil; }
    public FileConfiguration getMessagesConfig() { return messagesConfig; }
}
