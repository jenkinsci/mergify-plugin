package io.jenkins.plugins.mergify;

import hudson.Extension;
import hudson.Util;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
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

@Extension
public class MergifyConfiguration extends GlobalConfiguration implements MergifyConfigurationProvider {

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
        this.url = Util.fixEmptyAndTrim(url);
        save();
    }

    @Override
    public void save() {
        super.save();
        TracerService.clearMergifySpanExporters();
    }

    @POST
    public FormValidation doCheckUrl(@QueryParameter("url") final String value) throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.READ);
        String valueTrim = Util.fixEmptyAndTrim(value);
        if (valueTrim == null) {
            return FormValidation.error("Mergify API URL cannot be empty.");
        }

        try {
            new URL(valueTrim);
        } catch (MalformedURLException e) {
            return FormValidation.error("Invalid URL format.");
        }

        if (!valueTrim.startsWith("https://")) {
            return FormValidation.error("URL must start with 'https://'.");
        }

        if (valueTrim.endsWith("/")) {
            return FormValidation.error("URL must not contain ending /.");
        }

        return FormValidation.ok();
    }

    // For easier mock testing
    protected HttpURLConnection openConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    @POST
    public FormValidation doTestConnection(@QueryParameter("url") final String value)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            URL url = new URL(value);
            HttpURLConnection connection = openConnection(url);
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
