package com.mergify.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.opentelemetry.api.trace.Span;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class JobMetadataTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Mock
    private Span span;

    private JobMetadata jobMetadata;

    @Before
    public void setUp() throws IOException, ExecutionException, InterruptedException {
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("test-job");
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jobMetadata = new JobMetadata(build);
    }

    @Before
    public void resetMocks() {
        reset(span);
    }

    @Test
    public void testOnlyAddRepositoryURL() {
        jobMetadata.addRepositoryURL("SOURCE", "https://github.com/owner/repo.git");

        jobMetadata.setCommonSpanAttributes(span);

        // Some required info are missing, ensure attributes are not set
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PROVIDER_NAME), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PIPELINE_NAME), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PIPELINE_ID), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PIPELINE_URL), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REF_BASE_NAME), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REF_HEAD_REVISION), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REPOSITORY_URL_FULL), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REPOSITORY_URL_SOURCE), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REPOSITORY_NAME), anyString());
    }

    @Test
    public void testWithProjectRepositoryURL() {
        jobMetadata.addRepositoryURL("PROJECT", "https://github.com/owner/repo-project.git");

        EnvVars envVars = new EnvVars();
        envVars.put("GIT_URL", "https://github.com/owner/repo.git");
        envVars.put("GIT_COMMIT", "abcdef123456");
        envVars.put("GIT_BRANCH", "main");

        jobMetadata.setSCMCheckoutInfoFromEnvs(envVars);

        jobMetadata.setCommonSpanAttributes(span);

        verify(span).setAttribute(TraceUtils.CICD_PROVIDER_NAME, "jenkins");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_NAME, "test-job #1");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_ID, "test-job#1");
        verify(span).setAttribute(eq(TraceUtils.CICD_PIPELINE_URL), contains("/jenkins/job/test-job/1/"));
        verify(span).setAttribute(TraceUtils.VCS_REF_BASE_NAME, "main");
        verify(span).setAttribute(TraceUtils.VCS_REF_HEAD_REVISION, "abcdef123456");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_FULL, "https://github.com/owner/repo-project.git");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_SOURCE, "PROJECT");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_NAME, "owner/repo-project");
    }

    @Test
    public void testSetSCMCheckoutInfoFromEnvs() {
        EnvVars envVars = new EnvVars();
        envVars.put("GIT_URL", "https://github.com/owner/repo.git");
        envVars.put("GIT_COMMIT", "abcdef123456");
        envVars.put("GIT_BRANCH", "main");

        jobMetadata.setSCMCheckoutInfoFromEnvs(envVars);

        jobMetadata.setCommonSpanAttributes(span);

        verify(span).setAttribute(TraceUtils.CICD_PROVIDER_NAME, "jenkins");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_NAME, "test-job #1");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_ID, "test-job#1");
        verify(span).setAttribute(eq(TraceUtils.CICD_PIPELINE_URL), contains("/jenkins/job/test-job/1/"));
        verify(span).setAttribute(TraceUtils.VCS_REF_BASE_NAME, "main");
        verify(span).setAttribute(TraceUtils.VCS_REF_HEAD_REVISION, "abcdef123456");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_FULL, "https://github.com/owner/repo.git");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_SOURCE, "SCMCheckoutURL");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_NAME, "owner/repo");
    }

    @Test
    public void testGetRepositoryName() {
        assertEquals("owner/repo", JobMetadata.getRepositoryName("https://github.com/owner/repo.git"));
        assertEquals("owner/repo", JobMetadata.getRepositoryName("git@github.com:owner/repo.git"));
        assertEquals("owner/repo", JobMetadata.getRepositoryName("https://github.com/owner/repo"));
        assertNull(JobMetadata.getRepositoryName("invalid-url"));
    }
}
