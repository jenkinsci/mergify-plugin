package com.mergify.jenkins.plugins.mergify;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

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
        return "OrgApiKey{" + "organizationName='" + organizationName + '}';
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<OrgApiKey> {
        @Override
        public String getDisplayName() {
            return "Organization API Key";
        }
    }
}