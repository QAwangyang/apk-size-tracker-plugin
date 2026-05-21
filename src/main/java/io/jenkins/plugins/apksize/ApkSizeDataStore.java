package io.jenkins.plugins.apksize;

import hudson.model.Job;
import hudson.model.Run;
import hudson.model.Result;
import jenkins.model.Jenkins;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Persistent data store for APK/IPA size records.
 *
 * Stores build records in a JSON file under JENKINS_HOME/apk-size-tracker/.
 * Thread-safe: per-file locks via ConcurrentHashMap, atomic writes via temp+rename.
 *
 * File format:
 *   $JENKINS_HOME/apk-size-tracker/{sanitized-job-name}.json
 *
 * Flow:
 *   Publisher.perform() → appendBuild()  (incremental, ~1ms)
 *   TrendAction        → loadBuilds()    (file read, ~1ms)
 *                       → scanAllBuilds() (full scan, on first access only)
 */
public class ApkSizeDataStore {

    private static final Logger LOGGER = Logger.getLogger(ApkSizeDataStore.class.getName());
    private static final String DATA_DIR = "apk-size-tracker";
    private static final int MAX_BUILDS = 5000;
    private static final int SCAN_LIMIT = 5000;
    private static final int DATA_VERSION = 3;

    /** Per-file locks for thread-safe concurrent writes across builds */
    private static final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    private ApkSizeDataStore() {}

    // ── Public API ──────────────────────────────────────────────

    /**
     * Append or update a build record. Called by ApkSizePublisher.perform().
     * Thread-safe: synchronized per file path, atomic write via temp+rename.
     */
    public static void appendBuild(Job<?, ?> job, int buildNumber,
                                    long apkBytes, long ipaBytes, long hapBytes,
                                    long durationMs) {
        File file = getDataFile(job);
        Object lock = fileLocks.computeIfAbsent(file.getAbsolutePath(), k -> new Object());

        synchronized (lock) {
            try {
                List<BuildRecord> builds = readBuildsFromFile(file);
                String duration = formatDuration(durationMs);

                // Update existing entry or append new one
                boolean updated = false;
                for (int i = 0; i < builds.size(); i++) {
                    if (builds.get(i).buildNumber == buildNumber) {
                        builds.set(i, new BuildRecord(buildNumber, apkBytes, ipaBytes, hapBytes, duration));
                        updated = true;
                        LOGGER.fine("Updated build #" + buildNumber + " in data file");
                        break;
                    }
                }
                if (!updated) {
                    builds.add(new BuildRecord(buildNumber, apkBytes, ipaBytes, hapBytes, duration));
                    LOGGER.fine("Appended build #" + buildNumber + " to data file");
                }

                // Sort ascending by build number
                builds.sort(Comparator.comparingInt(b -> b.buildNumber));

                // Trim to max
                if (builds.size() > MAX_BUILDS) {
                    builds = builds.subList(builds.size() - MAX_BUILDS, builds.size());
                }

                writeBuildsToFile(file, builds, job.getDisplayName());

            } catch (Exception e) {
                LOGGER.warning("Failed to append build #" + buildNumber + ": " + e.getMessage());
            }
        }
    }

    /**
     * Load build records from data file. Returns null if file doesn't exist or is corrupted.
     */
    public static List<BuildRecord> loadBuilds(Job<?, ?> job) {
        File file = getDataFile(job);
        if (!file.exists()) return null;
        try {
            return readBuildsFromFile(file);
        } catch (Exception e) {
            LOGGER.warning("Failed to read data file (will re-scan): " + e.getMessage());
            file.delete(); // corrupt file → remove so next load triggers re-scan
            return null;
        }
    }

    /**
     * Save a full build list to file. Used after initial scan.
     */
    public static void saveBuilds(Job<?, ?> job, List<BuildRecord> builds) {
        File file = getDataFile(job);
        Object lock = fileLocks.computeIfAbsent(file.getAbsolutePath(), k -> new Object());
        synchronized (lock) {
            writeBuildsToFile(file, builds, job.getDisplayName());
        }
    }

    /**
     * Scan all successful builds of a job (one-time initialization).
     * Slower but comprehensive — called only when data file doesn't exist.
     */
    public static List<BuildRecord> scanAllBuilds(Job<?, ?> job) {
        List<? extends Run<?, ?>> builds = job.getBuilds();
        int limit = Math.min(builds.size(), SCAN_LIMIT);
        LOGGER.info("Scanning " + limit + "/" + builds.size() + " builds for initial data file...");

        List<BuildRecord> records = new ArrayList<>();

        // Iterate oldest → newest so records are naturally ascending
        for (int i = limit - 1; i >= 0; i--) {
            Run<?, ?> build = builds.get(i);
            if (build.getResult() == null || !build.getResult().equals(Result.SUCCESS)) continue;

            long apkBytes = -1, ipaBytes = -1, hapBytes = -1;
            boolean found = false;

            // Phase 1: read from ApkSizeBuildAction (plugin-already-installed builds)
            ApkSizeBuildAction ba = build.getAction(ApkSizeBuildAction.class);
            if (ba != null) {
                found = true;
                if (ba.hasApk()) apkBytes = ba.getApkSizeBytes();
                if (ba.hasIpa()) ipaBytes = ba.getIpaSizeBytes();
                if (ba.hasHap()) hapBytes = ba.getHapSizeBytes();
            }

            // Phase 2: scan archived artifacts directly (historical builds)
            if (!found) {
                for (Run.Artifact artifact : build.getArtifacts()) {
                    String fn = artifact.getFileName().toLowerCase();
                    File f = artifact.getFile();
                    if (f != null && f.exists()) {
                        if (fn.endsWith(".apk") || fn.endsWith(".aab")) apkBytes = f.length();
                        else if (fn.endsWith(".ipa")) ipaBytes = f.length();
                        else if (fn.endsWith(".hap") || fn.endsWith(".app")) hapBytes = f.length();
                    }
                }
            }

            String duration = formatDuration(build.getDuration());
            records.add(new BuildRecord(build.getNumber(), apkBytes, ipaBytes, hapBytes, duration));
        }

        records.sort(Comparator.comparingInt(b -> b.buildNumber));
        LOGGER.info("Initial scan complete: " + records.size() + " build records");
        return records;
    }

    /**
     * Convert BuildRecords to the chart-ready JSON format that doIndex() expects.
     * Same structure as the original buildTrendJson() output — fully compatible.
     */
    public static String toChartJson(Job<?, ?> job, List<BuildRecord> records) {
        StringBuilder j = new StringBuilder(8192);
        j.append("{\"job\":\"").append(escapeJson(job.getDisplayName())).append("\",");

        List<String> apkBns = new ArrayList<>();
        List<String> apkSizes = new ArrayList<>();
        List<String> apkDur = new ArrayList<>();
        List<String> ipaBns = new ArrayList<>();
        List<String> ipaSizes = new ArrayList<>();
        List<String> ipaDur = new ArrayList<>();
        List<String> hapBns = new ArrayList<>();
        List<String> hapSizes = new ArrayList<>();
        List<String> hapDur = new ArrayList<>();

        for (BuildRecord rec : records) {
            if (rec.apkMb >= 0) {
                apkBns.add(String.valueOf(rec.buildNumber));
                apkSizes.add(String.format(Locale.US, "%.3f", rec.apkMb));
                apkDur.add(escapeJson(rec.duration));
            }
            if (rec.ipaMb >= 0) {
                ipaBns.add(String.valueOf(rec.buildNumber));
                ipaSizes.add(String.format(Locale.US, "%.3f", rec.ipaMb));
                ipaDur.add(escapeJson(rec.duration));
            }
            if (rec.hapMb >= 0) {
                hapBns.add(String.valueOf(rec.buildNumber));
                hapSizes.add(String.format(Locale.US, "%.3f", rec.hapMb));
                hapDur.add(escapeJson(rec.duration));
            }
        }

        // APK
        j.append("\"apk\":{");
        j.append("\"buildNumbers\":[").append(String.join(",", apkBns)).append("],");
        j.append("\"sizesMb\":[").append(String.join(",", apkSizes)).append("],");
        j.append("\"durations\":[").append(joinQuoted(apkDur)).append("]},");

        // IPA
        j.append("\"ipa\":{");
        j.append("\"buildNumbers\":[").append(String.join(",", ipaBns)).append("],");
        j.append("\"sizesMb\":[").append(String.join(",", ipaSizes)).append("],");
        j.append("\"durations\":[").append(joinQuoted(ipaDur)).append("]},");

        // HAP
        j.append("\"hap\":{");
        j.append("\"buildNumbers\":[").append(String.join(",", hapBns)).append("],");
        j.append("\"sizesMb\":[").append(String.join(",", hapSizes)).append("],");
        j.append("\"durations\":[").append(joinQuoted(hapDur)).append("]},");

        // Diff
        j.append("\"diff\":{");
        appendDiff(j, "apk", apkBns, apkSizes);
        j.append(",");
        appendDiff(j, "ipa", ipaBns, ipaSizes);
        j.append(",");
        appendDiff(j, "hap", hapBns, hapSizes);
        j.append("},");

        // Timestamp
        j.append("\"lastUpdated\":\"")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()))
            .append("\"}");

        return j.toString();
    }

    // ── File I/O ────────────────────────────────────────────────

    private static File getDataDir() {
        File dir = new File(Jenkins.get().getRootDir(), DATA_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File getDataFile(Job<?, ?> job) {
        String name = job.getFullName().replace('/', '_').replace('\\', '_')
                .replace(':', '_').replace(' ', '_');
        return new File(getDataDir(), name + ".json");
    }

    private static List<BuildRecord> readBuildsFromFile(File file) throws IOException {
        List<BuildRecord> records = new ArrayList<>();
        if (!file.exists()) return records;

        byte[] raw = Files.readAllBytes(file.toPath());
        String content = new String(raw, StandardCharsets.UTF_8);

        // Locate the "builds" array
        int arrStart = indexOfJsonKey(content, "builds");
        if (arrStart < 0) return records;
        if (content.charAt(arrStart) != '[') return records;

        int depth = 1;
        int arrEnd = arrStart + 1;
        while (arrEnd < content.length() && depth > 0) {
            char c = content.charAt(arrEnd);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            arrEnd++;
        }
        if (depth != 0) return records;

        // Extract and parse each {...} object
        String arrayBody = content.substring(arrStart + 1, arrEnd - 1);
        int pos = 0;
        while (true) {
            int objStart = arrayBody.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(arrayBody, objStart);
            if (objEnd < 0) break;

            String obj = arrayBody.substring(objStart, objEnd + 1);
            pos = objEnd + 1;

            int num = extractIntValue(obj, "num");
            if (num <= 0) continue;

            double apk = extractDoubleValue(obj, "apkMb");
            double ipa = extractDoubleValue(obj, "ipaMb");
            double hap = extractDoubleValue(obj, "hapMb");
            String dur = extractStringValue(obj, "duration");

            records.add(new BuildRecord(num, apk >= 0 ? apk : -1, ipa >= 0 ? ipa : -1, hap >= 0 ? hap : -1, dur));
        }

        records.sort(Comparator.comparingInt(b -> b.buildNumber));
        return records;
    }

    private static void writeBuildsToFile(File file, List<BuildRecord> builds, String jobName) {
        try {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("{\"version\":").append(DATA_VERSION).append(",");
            sb.append("\"jobName\":\"").append(escapeJson(jobName)).append("\",");
            sb.append("\"builds\":[");
            for (int i = 0; i < builds.size(); i++) {
                if (i > 0) sb.append(",");
                BuildRecord b = builds.get(i);
                sb.append("{");
                sb.append("\"num\":").append(b.buildNumber).append(",");
                sb.append("\"apkMb\":").append(String.format(Locale.US, "%.3f", b.apkMb >= 0 ? b.apkMb : -1)).append(",");
                sb.append("\"ipaMb\":").append(String.format(Locale.US, "%.3f", b.ipaMb >= 0 ? b.ipaMb : -1)).append(",");
                sb.append("\"hapMb\":").append(String.format(Locale.US, "%.3f", b.hapMb >= 0 ? b.hapMb : -1)).append(",");
                sb.append("\"duration\":\"").append(escapeJson(b.duration)).append("\"");
                sb.append("}");
            }
            sb.append("],");
            sb.append("\"lastUpdated\":\"")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()))
                .append("\"}");

            // Atomic write: temp → rename
            File tmpFile = new File(file.getAbsolutePath() + ".tmp");
            Files.write(tmpFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            boolean renamed = tmpFile.renameTo(file);
            if (!renamed) {
                LOGGER.warning("Atomic rename failed, fallback to direct write");
                Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
                tmpFile.delete();
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to write data file " + file.getName() + ": " + e.getMessage());
        }
    }

    // ── JSON helpers (no external dependency) ────────────────────

    private static int indexOfJsonKey(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return -1;
        int start = idx + search.length();
        // Skip whitespace
        while (start < json.length() && json.charAt(start) <= ' ') start++;
        return start;
    }

    private static int findMatchingBrace(String s, int openPos) {
        if (openPos >= s.length() || (s.charAt(openPos) != '{' && s.charAt(openPos) != '[')) return -1;
        char open = s.charAt(openPos);
        char close = (open == '{') ? '}' : ']';
        int depth = 1;
        int pos = openPos + 1;
        boolean inString = false;
        while (pos < s.length() && depth > 0) {
            char c = s.charAt(pos);
            if (inString) {
                if (c == '\\') { pos += 2; continue; }
                if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == open) depth++;
                else if (c == close) depth--;
            }
            pos++;
        }
        return depth == 0 ? pos - 1 : -1;
    }

    private static int extractIntValue(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int start = idx + search.length();
        while (start < json.length() && json.charAt(start) <= ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end > start) return Integer.parseInt(json.substring(start, end));
        return 0;
    }

    private static double extractDoubleValue(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return -1;
        int start = idx + search.length();
        while (start < json.length() && json.charAt(start) <= ' ') start++;
        if (start >= json.length()) return -1;
        // Handle null value
        if (json.startsWith("null", start)) return -1;
        int end = start;
        boolean hasDot = false;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            if (json.charAt(end) == '.') hasDot = true;
            end++;
        }
        if (end > start) {
            String val = json.substring(start, end);
            return Double.parseDouble(val);
        }
        return -1;
    }

    private static String extractStringValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int start = idx + search.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"') break;
            end++;
        }
        if (end < json.length()) return json.substring(start, end);
        return "";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String joinQuoted(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i)).append("\"");
        }
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static String formatDuration(long ms) {
        if (ms < 0) return "-";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec = sec % 60;
        if (min < 60) return min + "m " + sec + "s";
        long hour = min / 60;
        min = min % 60;
        return hour + "h " + min + "m " + sec + "s";
    }

    private static void appendDiff(StringBuilder j, String type, List<String> bns, List<String> sizes) {
        if (bns.size() >= 2) {
            int last = bns.size() - 1;
            int prev = last - 1;
            double diffMb = Double.parseDouble(sizes.get(last)) - Double.parseDouble(sizes.get(prev));
            j.append("\"").append(type).append("\":{\"latestBN\":").append(bns.get(last))
                .append(",\"prevBN\":").append(bns.get(prev))
                .append(",\"diffMb\":").append(String.format(Locale.US, "%.3f", diffMb)).append("}");
        } else {
            j.append("\"").append(type).append("\":null");
        }
    }

    // ── Data class ──────────────────────────────────────────────

    /** Immutable record for a single build's size data. */
    public static class BuildRecord {
        public final int buildNumber;
        public final double apkMb;
        public final double ipaMb;
        public final double hapMb;
        public final String duration;

        public BuildRecord(int buildNumber, long apkBytes, long ipaBytes, long hapBytes, String duration) {
            this.buildNumber = buildNumber;
            this.apkMb = apkBytes >= 0 ? bytesToMb(apkBytes) : -1;
            this.ipaMb = ipaBytes >= 0 ? bytesToMb(ipaBytes) : -1;
            this.hapMb = hapBytes >= 0 ? bytesToMb(hapBytes) : -1;
            this.duration = duration != null ? duration : "-";
        }

        public BuildRecord(int buildNumber, double apkMb, double ipaMb, double hapMb, String duration) {
            this.buildNumber = buildNumber;
            this.apkMb = apkMb;
            this.ipaMb = ipaMb;
            this.hapMb = hapMb;
            this.duration = duration != null ? duration : "-";
        }

        private static double bytesToMb(long bytes) {
            return bytes / (1024.0 * 1024.0);
        }
    }
}
