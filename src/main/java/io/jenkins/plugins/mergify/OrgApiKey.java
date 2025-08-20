package io.jenkins.plugins.mergify;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class OrgApiKey extends AbstractDescribableImpl<OrgApiKey> {
    private final String organizationName;
    private final Secret apiKey;

    @DataBoundConstructor
    public OrgApiKey(String organizationName, Secret apiKey) {
        this.organizationName = organizationName;
        this.apiKey = apiKey;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public Secret getApiKey() {
        return apiKey;
    }

    public String getApiKeyPlainText() {
        return apiKey.getPlainText();
    }

    @Override
    public String toString() {
        return "OrgApiKey{" + "organizationName='" + organizationName + "'}";
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<OrgApiKey> {
        @Override
        public String getDisplayName() {
            return "GitHub Organization's Mergify CI Insights token";
        }

        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @POST
        public FormValidation doCheckOrganizationName(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Organization name is required.");
            }
            if (!value.matches("[a-zA-Z0-9-]+")) {
                return FormValidation.error("Organization name usually contains only letters, numbers, and dashes.");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        @POST
        public FormValidation doCheckApiKey(@QueryParameter Secret value) {
            if (value == null || value.getPlainText().isBlank()) {
                return FormValidation.error("API key is required.");
            }
            return FormValidation.ok();
        }
    }
}
