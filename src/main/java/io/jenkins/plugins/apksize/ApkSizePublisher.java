package io.jenkins.plugins.apksize;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Post-build step that captures APK/IPA sizes and stores them
 * as per-build actions. Trend chart available via sidebar "APK Size Trend".
 */
public class ApkSizePublisher extends Recorder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(ApkSizePublisher.class.getName());

    private boolean trackIos = true;
    private boolean trackAndroid = true;
    private boolean trackHarmony = true;

    @DataBoundConstructor
    public ApkSizePublisher() {}

    public boolean isTrackIos() { return trackIos; }
    @DataBoundSetter
    public void setTrackIos(boolean v) { this.trackIos = v; }

    public boolean isTrackAndroid() { return trackAndroid; }
    @DataBoundSetter
    public void setTrackAndroid(boolean v) { this.trackAndroid = v; }

    public boolean isTrackHarmony() { return trackHarmony; }
    @DataBoundSetter
    public void setTrackHarmony(boolean v) { this.trackHarmony = v; }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace,
                        @Nonnull Launcher launcher, @Nonnull TaskListener listener)
            throws InterruptedException, IOException {

        String projectName = run.getParent().getFullName();
        listener.getLogger().println("========================================");
        listener.getLogger().println("[APK Size Tracker] Build #" + run.getNumber() + " for project: " + projectName);
        listener.getLogger().println("[APK Size Tracker] Scanning artifacts...");
        listener.getLogger().println("========================================");
        LOGGER.fine("perform() called for " + projectName + " #" + run.getNumber());

        long apkSizeBytes = -1, ipaSizeBytes = -1, hapSizeBytes = -1;
        String apkName = null, ipaName = null, hapName = null;

        int artifactCount = run.getArtifacts().size();
        LOGGER.fine("Found " + artifactCount + " archived artifacts");
        listener.getLogger().println("[APK Size Tracker] Found " + artifactCount + " archived artifact(s)");

        for (Run.Artifact artifact : run.getArtifacts()) {
            String fn = artifact.getFileName().toLowerCase();
            java.io.File f = artifact.getFile();
            if (f == null || !f.exists()) {
                LOGGER.fine("Artifact file not found on disk: " + artifact.getFileName());
                continue;
            }

            // Android: .apk or .aab
            if (trackAndroid && apkSizeBytes < 0 && (fn.endsWith(".apk") || fn.endsWith(".aab"))) {
                apkSizeBytes = f.length();
                apkName = artifact.getFileName();
                String size = formatSize(apkSizeBytes);
                String ext = fn.endsWith(".aab") ? "AAB" : "APK";
                listener.getLogger().println("[APK Size Tracker]   ✓ " + ext + ": " + apkName + " (" + size + ")");
                LOGGER.fine("APK captured: " + apkName + " = " + apkSizeBytes + " bytes");

            // iOS: .ipa
            } else if (trackIos && ipaSizeBytes < 0 && fn.endsWith(".ipa")) {
                ipaSizeBytes = f.length();
                ipaName = artifact.getFileName();
                String size = formatSize(ipaSizeBytes);
                listener.getLogger().println("[APK Size Tracker]   ✓ IPA: " + ipaName + " (" + size + ")");
                LOGGER.fine("IPA captured: " + ipaName + " = " + ipaSizeBytes + " bytes");

            // HarmonyOS: .hap or .app
            } else if (trackHarmony && hapSizeBytes < 0 && (fn.endsWith(".hap") || fn.endsWith(".app"))) {
                hapSizeBytes = f.length();
                hapName = artifact.getFileName();
                String size = formatSize(hapSizeBytes);
                String ext = fn.endsWith(".app") ? "APP" : "HAP";
                listener.getLogger().println("[APK Size Tracker]   ✓ " + ext + ": " + hapName + " (" + size + ")");
                LOGGER.fine("HAP captured: " + hapName + " = " + hapSizeBytes + " bytes");

            } else {
                // Already captured a file for this platform, or not a tracked type
                if (!fn.endsWith(".apk") && !fn.endsWith(".aab")
                    && !fn.endsWith(".ipa")
                    && !fn.endsWith(".hap") && !fn.endsWith(".app")) {
                    listener.getLogger().println("[APK Size Tracker]   Skipped: " + artifact.getFileName());
                }
            }
        }

        run.addAction(new ApkSizeBuildAction(
            run.getNumber(), run.getTimestampString(),
            apkSizeBytes, ipaSizeBytes, hapSizeBytes, apkName, ipaName, hapName));
        LOGGER.fine("ApkSizeBuildAction added to build #" + run.getNumber());

        // Persist to data file for fast retrieval on Trend page
        Job<?, ?> job = run.getParent();
        ApkSizeDataStore.appendBuild(
            job, run.getNumber(),
            apkSizeBytes, ipaSizeBytes, hapSizeBytes, run.getDuration());
        LOGGER.fine("ApkSizeDataStore updated for build #" + run.getNumber());

        // Auto-attach ApkSizeJobProperty so the trend chart shows on project page
        if (job.getProperty(ApkSizeJobProperty.class) == null) {
            try {
                job.addProperty(new ApkSizeJobProperty());
                job.save();
                LOGGER.fine("ApkSizeJobProperty auto-attached to " + job.getFullName());
            } catch (Exception e) {
                LOGGER.warning("Failed to auto-attach ApkSizeJobProperty: " + e.getMessage());
            }
        }

        if (apkSizeBytes < 0 && ipaSizeBytes < 0) {
            listener.getLogger().println("[APK Size Tracker] ⚠ WARNING: No APK/IPA found. Ensure 'Archive artifacts' runs before this step.");
            LOGGER.warning("No APK/IPA artifacts found for " + projectName + " #" + run.getNumber());
        } else {
            listener.getLogger().println("[APK Size Tracker] ✓ Data captured for " + run.getNumber());
            if (apkSizeBytes >= 0) listener.getLogger().println("[APK Size Tracker]   APK: " + formatSize(apkSizeBytes));
            if (ipaSizeBytes >= 0) listener.getLogger().println("[APK Size Tracker]   IPA: " + formatSize(ipaSizeBytes));
            listener.getLogger().println("[APK Size Tracker] ✓ Action registered: ApkSizeBuildAction");
        }
        listener.getLogger().println("[APK Size Tracker] ✓ Project action registered: ApkSizeTrendAction (sidebar link)");
        listener.getLogger().println("[APK Size Tracker] ✓ View chart at: Project page → 'APK Size Trend' sidebar link");
        listener.getLogger().println("[APK Size Tracker] ✓ Direct URL: /job/" + projectName + "/apkSizeTrend/");
        listener.getLogger().println("========================================");
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * Returns the project-level action (sidebar link "APK Size Trend")
     * when this publisher is configured on a project.
     * This is more reliable than TransientProjectActionFactory.
     */
    @Override
    public Action getProjectAction(AbstractProject<?, ?> project) {
        String name = project != null ? project.getFullName() : "null";
        LOGGER.info("getProjectAction() called for: " + name);
        if (project != null) {
            return new ApkSizeTrendAction(project);
        }
        return null;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) { return true; }
        @Override @Nonnull
        public String getDisplayName() { return "Track APK/IPA Size"; }
    }

    static String formatSize(long bytes) {
        if (bytes < 0) return "N/A";
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
