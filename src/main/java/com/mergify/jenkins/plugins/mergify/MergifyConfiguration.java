package com.mergify.jenkins.plugins.mergify;

import hudson.Extension;
import hudson.Util;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Extension
public class MergifyConfiguration extends GlobalConfiguration {

    private List<OrgApiKey> orgApiKeys;
    private String url;

    public MergifyConfiguration() {
        load();
        if (orgApiKeys == null) {
            orgApiKeys = new ArrayList<>();
        }
        if (url == null) {
            url = "https://api.mergify.com";
        }
    }

    public static MergifyConfiguration get() {
        return GlobalConfiguration.all().get(MergifyConfiguration.class);
    }

    public String getApiKeyForOrg(String organizationName) {
        List<OrgApiKey> orgApiKeys = getOrgApiKeys();
        if (orgApiKeys != null) {
            for (OrgApiKey entry : orgApiKeys) {
                if (entry.getOrganizationName().equals(organizationName)) {
                    // Return decrypted API key
                    return entry.getApiKey().getPlainText();
                }
            }
        }
        return null;
    }

    @Exported
    public List<OrgApiKey> getOrgApiKeys() {
        return orgApiKeys;
    }

    public void setOrgApiKeys(List<OrgApiKey> orgApiKeys) {
        this.orgApiKeys = orgApiKeys;
        save();
    }

    @Exported
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
        save();
    }

    public FormValidation doCheckUrl(@QueryParameter String value) throws IOException, ServletException {
        if (Util.fixEmptyAndTrim(value) == null) {
            return FormValidation.error("Mergify API URL cannot be empty.");
        }
        if (!value.startsWith("https://")) {
            return FormValidation.error("URL must start with 'https://'.");
        }

        try {
            URL url = new URL(value);
            if (!Pattern.matches("^https://[^/]+$", value)) {
                return FormValidation.error("URL must not contain a path. Only domain is allowed.");
            }
        } catch (MalformedURLException e) {
            return FormValidation.error("Invalid URL format.");
        }

        return FormValidation.ok();
    }

    @POST
    public FormValidation doTestConnection(@QueryParameter("url") final String value) throws IOException, ServletException {
        try {
            URL url = new URL(value);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000); // Timeout 3 sec
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int statusCode = connection.getResponseCode();
            connection.disconnect();

            if (statusCode >= 200 && statusCode < 400) {
                return FormValidation.ok("Success");
            } else {
                return FormValidation.error("HTTP " + statusCode + " error: " + connection.getResponseMessage());
            }
        } catch (Exception e) {
            return FormValidation.error("Client error : " + e.getMessage());
        }
    }
}