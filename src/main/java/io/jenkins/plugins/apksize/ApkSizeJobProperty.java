package io.jenkins.plugins.apksize;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Logger;

/**
 * JobProperty that renders the APK/IPA Size Trend chart on the project page's side panel.
 * Auto-attached by {@link ApkSizePublisher} on first build.
 *
 * Summary template: {@code summary.jelly} renders an iframe pointing to
 * the {@code /job/{name}/apkSizeTrend/widget} endpoint.
 */
public class ApkSizeJobProperty extends JobProperty<Job<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(ApkSizeJobProperty.class.getName());

    @DataBoundConstructor
    public ApkSizeJobProperty() {
        LOGGER.fine("ApkSizeJobProperty created");
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "APK/IPA Size Trend Widget";
        }
    }
}
