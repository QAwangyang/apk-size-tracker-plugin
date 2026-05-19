package io.jenkins.plugins.apksize;

import hudson.model.Action;
import hudson.model.Run;

import java.io.Serializable;

/**
 * Stores per-build APK/IPA size data.
 * Displayed as a sidebar entry and summary on the build page.
 */
public class ApkSizeBuildAction implements Action, Serializable {

    private static final long serialVersionUID = 1L;

    private final int buildNumber;
    private final String buildTimestamp;
    private final long apkSizeBytes;
    private final long ipaSizeBytes;
    private final String apkFileName;
    private final String ipaFileName;

    public ApkSizeBuildAction(int buildNumber, String buildTimestamp,
                              long apkSizeBytes, long ipaSizeBytes,
                              String apkFileName, String ipaFileName) {
        this.buildNumber = buildNumber;
        this.buildTimestamp = buildTimestamp;
        this.apkSizeBytes = apkSizeBytes;
        this.ipaSizeBytes = ipaSizeBytes;
        this.apkFileName = apkFileName;
        this.ipaFileName = ipaFileName;
    }

    @Override
    public String getIconFileName() {
        return "/plugin/apk-size-tracker/icons/chart-icon.svg";
    }

    @Override
    public String getDisplayName() {
        return "APK Size";
    }

    @Override
    public String getUrlName() {
        return "apkSize";
    }

    // ---- Data accessors (used by Jelly views) ----

    public int getBuildNumber() {
        return buildNumber;
    }

    public String getBuildTimestamp() {
        return buildTimestamp;
    }

    public long getApkSizeBytes() {
        return apkSizeBytes;
    }

    public String getApkSizeDisplay() {
        return formatSize(apkSizeBytes);
    }

    public long getIpaSizeBytes() {
        return ipaSizeBytes;
    }

    public String getIpaSizeDisplay() {
        return formatSize(ipaSizeBytes);
    }

    public String getApkFileName() {
        return apkFileName;
    }

    public String getIpaFileName() {
        return ipaFileName;
    }

    public boolean hasApk() {
        return apkSizeBytes >= 0;
    }

    public boolean hasIpa() {
        return ipaSizeBytes >= 0;
    }

    public String getFormattedDelta() {
        return ""; // Delta is computed at project level
    }

    static String formatSize(long bytes) {
        if (bytes < 0) return "N/A";
        double mb = bytes / (1024.0 * 1024.0);
        return String.format("%.2f MB", mb);
    }
}
