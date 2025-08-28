package io.jenkins.plugins.mergify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import hudson.EnvVars;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@WithJenkins
class JobMetadataTest {

    @Mock
    private Span span;

    private JobMetadata jobMetadata;

    private JenkinsRule jenkinsRule;

    @BeforeEach
    void beforeEach(JenkinsRule rule) throws Exception {
        jenkinsRule = rule;
        jenkinsRule.jenkins.setNumExecutors(2);
        FreeStyleProject project = jenkinsRule.createFreeStyleProject("test-job");
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jobMetadata = new JobMetadata(build);
    }

    @AfterEach
    void afterEach() {
        reset(span);
    }

    @Test
    void testOnlyAddRepositoryURL() {
        jobMetadata.addRepositoryURL("SOURCE", "https://github.com/owner/repo.git");

        jobMetadata.setCommonSpanAttributes(span);

        // Some required info are missing, ensure attributes are not set
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PROVIDER_NAME), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PIPELINE_NAME), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PIPELINE_RUN_ID), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.CICD_PIPELINE_URL), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REF_HEAD_NAME), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REF_HEAD_REVISION), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REPOSITORY_URL_FULL), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REPOSITORY_URL_SOURCE), anyString());
        verify(span, never()).setAttribute(eq(TraceUtils.VCS_REPOSITORY_NAME), anyString());
    }

    @Test
    void testWithProjectRepositoryURL() {
        jobMetadata.addRepositoryURL("PROJECT", "https://github.com/owner/repo-project.git");

        EnvVars envVars = new EnvVars();
        envVars.put("GIT_URL", "https://github.com/owner/repo.git");
        envVars.put("GIT_COMMIT", "abcdef123456");
        envVars.put("GIT_BRANCH", "origin/main");

        jobMetadata.setSCMCheckoutInfoFromEnvs(envVars);

        jobMetadata.setCommonSpanAttributes(span);

        verify(span).setAttribute(TraceUtils.CICD_PROVIDER_NAME, "jenkins");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_NAME, "test-job");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RUN_ID, "test-job#1");
        verify(span).setAttribute(eq(TraceUtils.CICD_PIPELINE_URL), contains("/jenkins/job/test-job/1/"));
        verify(span).setAttribute(TraceUtils.VCS_REF_HEAD_NAME, "main");
        verify(span).setAttribute(TraceUtils.VCS_REF_HEAD_REVISION, "abcdef123456");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_FULL, "https://github.com/owner/repo-project.git");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_SOURCE, "PROJECT");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_NAME, "owner/repo-project");
    }

    @Test
    void testSetSCMCheckoutInfoFromEnvs() {
        EnvVars envVars = new EnvVars();
        envVars.put("GIT_URL", "https://github.com/owner/repo.git");
        envVars.put("GIT_COMMIT", "abcdef123456");
        envVars.put("GIT_BRANCH", "origin/main");

        jobMetadata.setSCMCheckoutInfoFromEnvs(envVars);

        jobMetadata.setCommonSpanAttributes(span);

        verify(span).setAttribute(TraceUtils.CICD_PROVIDER_NAME, "jenkins");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_NAME, "test-job");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RUN_ID, "test-job#1");
        verify(span).setAttribute(eq(TraceUtils.CICD_PIPELINE_URL), contains("/jenkins/job/test-job/1/"));
        verify(span).setAttribute(TraceUtils.VCS_REF_HEAD_NAME, "main");
        verify(span).setAttribute(TraceUtils.VCS_REF_HEAD_REVISION, "abcdef123456");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_FULL, "https://github.com/owner/repo.git");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_URL_SOURCE, "SCMCheckoutURL");
        verify(span).setAttribute(TraceUtils.VCS_REPOSITORY_NAME, "owner/repo");
    }

    @Test
    void testGetRepositoryName() {
        assertEquals("owner/repo", JobMetadata.getRepositoryName("https://github.com/owner/repo.git"));
        assertEquals("owner/repo", JobMetadata.getRepositoryName("git@github.com:owner/repo.git"));
        assertEquals("owner/repo", JobMetadata.getRepositoryName("https://github.com/owner/repo"));
        assertEquals("owner/repo", JobMetadata.getRepositoryName("https://github.com/owner/repo/"));
        assertNull(JobMetadata.getRepositoryName("invalid-url"));
    }
}
