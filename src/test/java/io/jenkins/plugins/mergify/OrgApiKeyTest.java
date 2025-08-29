package io.jenkins.plugins.mergify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.util.Secret;
import org.junit.jupiter.api.Test;

class OrgApiKeyTest {

    @Test
    void testOrgApiKeyConstructorAndGetters() {
        String organizationName = "my-org";
        Secret apiKey = Secret.fromString("my-secret-key");

        OrgApiKey orgApiKey = new OrgApiKey(organizationName, apiKey);

        assertEquals("my-org", orgApiKey.getOrganizationName());
        assertEquals(apiKey, orgApiKey.getApiKey());
        assertEquals("my-secret-key", orgApiKey.getApiKeyPlainText());
    }

    @Test
    void testToString() {
        OrgApiKey orgApiKey = new OrgApiKey("test-org", Secret.fromString("hidden-key"));

        String expectedString = "OrgApiKey{organizationName='test-org'}";
        assertEquals(expectedString, orgApiKey.toString());
    }

    @Test
    void testDescriptorDisplayName() {
        OrgApiKey.DescriptorImpl descriptor = new OrgApiKey.DescriptorImpl();
        assertEquals("GitHub Organization's Mergify CI Insights token", descriptor.getDisplayName());
    }
}
