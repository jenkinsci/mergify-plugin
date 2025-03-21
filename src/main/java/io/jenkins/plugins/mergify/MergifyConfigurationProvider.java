package io.jenkins.plugins.mergify;

// Mainly for testing purposes
public interface MergifyConfigurationProvider {
    String getUrl();

    String getApiKeyForOrg(String org);
}
