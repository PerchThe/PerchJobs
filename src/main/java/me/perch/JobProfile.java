package me.perch.data;

import com.google.gson.Gson;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JobProfile {

    private static final Gson GSON = new Gson();

    private static final int TENURE_MAX_PERCENT = 10;

    private final Map<String, Integer> levels = new ConcurrentHashMap<>();
    private final Map<String, Double> xp = new ConcurrentHashMap<>();
    private final Map<String, Long> jobStartEpochDay = new ConcurrentHashMap<>();
    private Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    private transient volatile boolean dirty = false;
    private transient long revision = 0L;

    private void touch() {
        dirty = true;
        revision++;
    }

    public long getRevision() {
        return revision;
    }

    private long todayEpochDay() {
        return LocalDate.now(ZoneId.systemDefault()).toEpochDay();
    }

    public void joinJob(String job) {
        levels.putIfAbsent(job, 1);
        xp.putIfAbsent(job, 0.0);
        activeJobs.add(job);
        jobStartEpochDay.put(job, todayEpochDay());
        touch();
    }

    public void leaveJob(String job) {
        activeJobs.remove(job);
        jobStartEpochDay.remove(job);
        touch();
    }

    public boolean isJoined(String job) {
        return activeJobs.contains(job);
    }

    public void addXp(String job, double amount) {
        if (!levels.containsKey(job)) return;
        xp.merge(job, amount, Double::sum);
        touch();
    }

    public void addXpWithTenure(String job, double baseAmount) {
        if (!levels.containsKey(job)) return;
        double mult = getTenureMultiplier(job);
        xp.merge(job, baseAmount * mult, Double::sum);
        touch();
    }

    public void setLevel(String job, int level) {
        levels.put(job, level);
        touch();
    }

    public void setXp(String job, double amount) {
        xp.put(job, amount);
        touch();
    }

    public int getLevel(String job) { return levels.getOrDefault(job, 1); }
    public double getXp(String job) { return xp.getOrDefault(job, 0.0); }

    public long getJobStartEpochDay(String job) {
        return jobStartEpochDay.getOrDefault(job, 0L);
    }

    public int getTenureBonusPercent(String job) {
        if (!isJoined(job)) return 0;
        long start = jobStartEpochDay.getOrDefault(job, 0L);
        if (start <= 0L) return 0;
        long days = Math.max(0L, todayEpochDay() - start);
        return (int) Math.min((long) TENURE_MAX_PERCENT, days);
    }

    public double getTenureMultiplier(String job) {
        int pct = getTenureBonusPercent(job);
        return 1.0 + (pct / 100.0);
    }

    public Map<String, Integer> getLevelsMap() {
        return levels;
    }

    public boolean isDirty() { return dirty; }
    public void setClean() { dirty = false; }

    public String serialize() { return GSON.toJson(this); }

    public static JobProfile deserialize(String json) {
        JobProfile temp = GSON.fromJson(json, JobProfile.class);
        JobProfile safeProfile = new JobProfile();

        if (temp.levels != null) safeProfile.levels.putAll(temp.levels);
        if (temp.xp != null) safeProfile.xp.putAll(temp.xp);

        if (temp.activeJobs != null) {
            safeProfile.activeJobs.addAll(temp.activeJobs);
        } else {
            safeProfile.activeJobs.addAll(safeProfile.levels.keySet());
        }

        if (temp.jobStartEpochDay != null) safeProfile.jobStartEpochDay.putAll(temp.jobStartEpochDay);

        long today = safeProfile.todayEpochDay();
        for (String job : safeProfile.activeJobs) {
            safeProfile.jobStartEpochDay.putIfAbsent(job, today);
        }

        return safeProfile;
    }

    public Map<String, Integer> snapshotLevels() {
        return new HashMap<>(levels);
    }

    public Map<String, Double> snapshotXp() {
        return new HashMap<>(xp);
    }

    public Set<String> snapshotActiveJobs() {
        return new HashSet<>(activeJobs);
    }

    public Map<String, Long> snapshotJobStartEpochDay() {
        return new HashMap<>(jobStartEpochDay);
    }
}
