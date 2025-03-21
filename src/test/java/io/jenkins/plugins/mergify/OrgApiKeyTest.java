package io.jenkins.plugins.mergify;

import static org.junit.Assert.assertEquals;

import hudson.util.Secret;
import org.junit.Test;

public class OrgApiKeyTest {

    @Test
    public void testOrgApiKeyConstructorAndGetters() {
        String organizationName = "my-org";
        Secret apiKey = Secret.fromString("my-secret-key");

        OrgApiKey orgApiKey = new OrgApiKey(organizationName, apiKey);

        assertEquals("my-org", orgApiKey.getOrganizationName());
        assertEquals(apiKey, orgApiKey.getApiKey());
        assertEquals("my-secret-key", orgApiKey.getApiKeyPlainText());
    }

    @Test
    public void testToString() {
        OrgApiKey orgApiKey = new OrgApiKey("test-org", Secret.fromString("hidden-key"));

        String expectedString = "OrgApiKey{organizationName='test-org'}";
        assertEquals(expectedString, orgApiKey.toString());
    }

    @Test
    public void testDescriptorDisplayName() {
        OrgApiKey.DescriptorImpl descriptor = new OrgApiKey.DescriptorImpl();
        assertEquals("Organization API Key", descriptor.getDisplayName());
    }
}
