package io.jenkins.plugins.mergify;

import hudson.util.FormValidation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class MergifyConfigurationTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testDoCheckUrl_ValidUrl() throws IOException, ServletException {
        MergifyConfiguration config = new MergifyConfiguration();
        FormValidation result = config.doCheckUrl("https://api.mergify.com");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    public void testDoCheckUrl_EmptyUrl() throws IOException, ServletException {
        MergifyConfiguration config = new MergifyConfiguration();
        FormValidation result = config.doCheckUrl("");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertEquals("Mergify API URL cannot be empty.", result.getMessage());
    }

    @Test
    public void testDoCheckUrl_NullUrl() throws IOException, ServletException {
        MergifyConfiguration config = new MergifyConfiguration();
        FormValidation result = config.doCheckUrl(null);
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertEquals("Mergify API URL cannot be empty.", result.getMessage());
    }

    @Test
    public void testDoCheckUrl_MissingHttps() throws IOException, ServletException {
        MergifyConfiguration config = new MergifyConfiguration();
        FormValidation result = config.doCheckUrl("http://api.mergify.com");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertEquals("URL must start with &#039;https://&#039;.", result.getMessage());
    }

    @Test
    public void testDoCheckUrl_UrlWithPath() throws IOException, ServletException {
        MergifyConfiguration config = new MergifyConfiguration();
        FormValidation result = config.doCheckUrl("https://api.mergify.com/");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertEquals("URL must not contain ending /.", result.getMessage());
    }

    /*
    FIXME(sileht): new URL() does not raise the expected MalformedURLException in test

    @Test
    public void testDoCheckUrl_InvalidUrlFormat() throws IOException, ServletException {
        MergifyConfiguration config = new MergifyConfiguration();
        FormValidation result = config.doCheckUrl("https://api..co");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertEquals("Invalid URL format.", result.getMessage());
    }*/

    @Test
    public void testDoTestConnection_Success() throws IOException, ServletException {
        MergifyConfiguration config = spy(new MergifyConfiguration());

        // Mock URL connection
        HttpURLConnection mockConnection = mock(HttpURLConnection.class);
        when(mockConnection.getResponseCode()).thenReturn(200);
        when(mockConnection.getResponseMessage()).thenReturn("OK");

        // Mock URL.openConnection() to return mock HttpURLConnection
        doReturn(mockConnection).when(config).openConnection(Mockito.any(URL.class));

        FormValidation result = config.doTestConnection("https://api.mergify.com");

        assertEquals(FormValidation.Kind.OK, result.kind);
        assertEquals("Success", result.getMessage());
    }

    @Test
    public void testDoTestConnection_HttpError() throws IOException, ServletException {
        MergifyConfiguration config = spy(new MergifyConfiguration());

        HttpURLConnection mockConnection = mock(HttpURLConnection.class);
        when(mockConnection.getResponseCode()).thenReturn(500);
        when(mockConnection.getResponseMessage()).thenReturn("Internal Server Error");

        doReturn(mockConnection).when(config).openConnection(any(URL.class));

        FormValidation result = config.doTestConnection("https://api.mergify.com");

        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertEquals("HTTP 500 error: Internal Server Error", result.getMessage());
    }

    @Test
    public void testDoTestConnection_ClientError() throws IOException, ServletException {
        MergifyConfiguration config = new MergifyConfiguration();
        FormValidation result = config.doTestConnection("invalid_url");

        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertTrue(result.getMessage().startsWith("Client error :"));
    }
}
