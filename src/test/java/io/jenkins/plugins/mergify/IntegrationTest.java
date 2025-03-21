package io.jenkins.plugins.mergify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.tasks.Shell;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

public class IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(IntegrationTest.class.getName());

    @ClassRule
    @ConfiguredWithCode("test.yml")
    public static JenkinsConfiguredWithCodeRule jenkinsRule = new JenkinsConfiguredWithCodeRule();

    static {
        TracerService.SPAN_EXPORTER_BACKEND = TracerService.SpanExporterBackend.MEMORY;
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        LOGGER.info("Jenkins is starting...");
        jenkinsRule.waitUntilNoActivity();
        LOGGER.info("Jenkins started");

        ExtensionList<TracerService> tracerServiceExt =
                jenkinsRule.getInstance().getExtensionList(TracerService.class);
        assert tracerServiceExt.size() == 1;
    }

    @Before
    public void before() {
        InMemorySpanExporter spanExporter = TracerService.getInMemorySpanExpoter();
        spanExporter.reset();
    }

    List<SpanData> getSpans() {
        TracerService.forceFlush();
        InMemorySpanExporter spanExporter = TracerService.getInMemorySpanExpoter();
        return spanExporter.getFinishedSpanItems();
    }

    private String runCommand(File dir, String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        return new String(process.getInputStream().readAllBytes()).trim();
    }

    public File createGitRepository(String jobName) throws Exception {
        File repoDir = Files.createTempDirectory(jobName).toFile();

        // Initialize a real Git repository using system commands
        runCommand(repoDir, "git init");
        runCommand(repoDir, "git config user.name 'Test User'");
        runCommand(repoDir, "git config user.email 'test@example.com'");
        runCommand(repoDir, "touch README.md");
        runCommand(repoDir, "git add README.md");
        runCommand(repoDir, "git commit -m 'Initial commit'");
        runCommand(repoDir, "git branch -m main");
        return repoDir;
    }

    @Test
    public void testFreestyleJob() throws Exception {
        final String jobName = "test-freestyle";
        FreeStyleProject project = jenkinsRule.createFreeStyleProject(jobName);

        // Initialize a fake Git repository
        File repoDir = createGitRepository(jobName);
        String commit = runCommand(repoDir, "git rev-parse HEAD").trim();

        GitSCM gitSCM = new GitSCM(
                Collections.singletonList(new UserRemoteConfig(repoDir.toURI().toString(), null, null, null)),
                Collections.singletonList(new BranchSpec("*/main")),
                null,
                null,
                Collections.emptyList());
        project.setScm(gitSCM);
        String githubProjectUrl = "https://github.com/mergifyio/plugin";
        project.addProperty(new GithubProjectProperty(githubProjectUrl));
        project.getBuildersList().add(new Shell("echo 'Hello World...'"));

        jenkinsRule.buildAndAssertSuccess(project);

        List<SpanData> spans = getSpans();
        assertEquals(2, spans.size());

        String expectedTraceId = spans.get(0).getTraceId();
        spans.forEach(span -> assertEquals(expectedTraceId, span.getTraceId()));

        spans.forEach(span -> assertEquals(StatusData.ok(), span.getStatus()));

        // Common attributes Span
        for (SpanData span : spans) {
            Map<AttributeKey<?>, Object> attributes = span.getAttributes().asMap();
            assertEquals("jenkins", attributes.get(TraceUtils.CICD_PROVIDER_NAME));
            assertEquals("test-freestyle #1", attributes.get(TraceUtils.CICD_PIPELINE_NAME));
            assertEquals("test-freestyle#1", attributes.get(TraceUtils.CICD_PIPELINE_ID));
            assertTrue(
                    ((String) attributes.get(TraceUtils.CICD_PIPELINE_URL)).contains("/jenkins/job/test-freestyle/1/"));
            assertEquals("main", attributes.get(TraceUtils.VCS_REF_BASE_NAME));
            assertEquals(commit, attributes.get(TraceUtils.VCS_REF_HEAD_REVISION));
            assertEquals("https://github.com/mergifyio/plugin/", attributes.get(TraceUtils.VCS_REPOSITORY_URL_FULL));
            assertEquals("GitHubProjectProperty", attributes.get(TraceUtils.VCS_REPOSITORY_URL_SOURCE));
            assertEquals("mergifyio/plugin", attributes.get(TraceUtils.VCS_REPOSITORY_NAME));
        }

        // Step Attributes
        assertEquals("Shell", spans.get(0).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
    }

    @Test
    public void testPipelineJob() throws Exception {
        final String jobName = "test-pipeline";
        WorkflowJob job = jenkinsRule.createProject(WorkflowJob.class, jobName);

        // Initialize a fake Git repository
        File repoDir = createGitRepository(jobName);
        String commit = runCommand(repoDir, "git rev-parse HEAD").trim();

        // Define the pipeline script
        String pipelineScript = String.format(
                "pipeline {\n" + "    agent any\n"
                        + "    stages {\n"
                        + "        stage('Checkout') {\n"
                        + "            steps {\n"
                        + "                checkout([$class: 'GitSCM', branches: [[name: '*/main']], userRemoteConfigs: [[url: '%s']]])\n"
                        + "            }\n"
                        + "        }\n"
                        + "        stage('Build') {\n"
                        + "            steps {\n"
                        + "                sh 'echo Hello World...'\n"
                        + "            }\n"
                        + "        }\n"
                        + "    }\n"
                        + "}",
                repoDir.toURI());

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        job.addProperty(new GithubProjectProperty("https://github.com/mergifyio/plugin"));

        jenkinsRule.buildAndAssertSuccess(job);

        List<SpanData> spans = getSpans();
        assertEquals(3, spans.size());

        String expectedTraceId = spans.get(0).getTraceId();
        spans.forEach(span -> assertEquals(expectedTraceId, span.getTraceId()));

        spans.forEach(span -> assertEquals(StatusData.ok(), span.getStatus()));

        // Common attributes Span
        for (SpanData span : spans) {
            Map<AttributeKey<?>, Object> attributes = span.getAttributes().asMap();
            assertEquals("jenkins", attributes.get(TraceUtils.CICD_PROVIDER_NAME));
            assertEquals("test-pipeline #1", attributes.get(TraceUtils.CICD_PIPELINE_NAME));
            assertEquals("test-pipeline#1", attributes.get(TraceUtils.CICD_PIPELINE_ID));
            assertTrue(
                    ((String) attributes.get(TraceUtils.CICD_PIPELINE_URL)).contains("/jenkins/job/test-pipeline/1/"));
            assertEquals("main", attributes.get(TraceUtils.VCS_REF_BASE_NAME));
            assertEquals(commit, attributes.get(TraceUtils.VCS_REF_HEAD_REVISION));
            assertEquals("https://github.com/mergifyio/plugin/", attributes.get(TraceUtils.VCS_REPOSITORY_URL_FULL));
            assertEquals("GitHubProjectProperty", attributes.get(TraceUtils.VCS_REPOSITORY_URL_SOURCE));
            assertEquals("mergifyio/plugin", attributes.get(TraceUtils.VCS_REPOSITORY_NAME));
        }

        // Step Attributes
        assertEquals("Stage(Checkout)", spans.get(0).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
        assertEquals("Stage(Build)", spans.get(1).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
    }
}
