package io.jenkins.plugins.mergify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.NullSCM;
import hudson.slaves.DumbSlave;
import hudson.tasks.Fingerprinter;
import hudson.tasks.Shell;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.branch.Branch;
import jenkins.branch.BranchSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMDiscoverBranches;
import jenkins.scm.impl.mock.MockSCMDiscoverChangeRequests;
import jenkins.scm.impl.mock.MockSCMSource;
import org.jenkinsci.plugins.github_branch_source.BranchSCMHead;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;
import org.jenkinsci.plugins.github_branch_source.SSHCheckoutTrait;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries;
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration;
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class IntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(IntegrationTest.class.getName());
    private static final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();

    private static JenkinsRule jenkinsRule;

    @BeforeAll
    static void beforeAll(JenkinsRule rule) throws Exception {
        LOGGER.info("Jenkins is starting...");

        ConfigurationAsCode.get()
                .configure(IntegrationTest.class.getResource("test.yml").toString());
        rule.waitUntilNoActivity();
        jenkinsRule = rule;

        LOGGER.info("Jenkins started");

        MergifySpanExporter.setTestExporter(spanExporter);

        ExtensionList<TracerService> tracerServiceExt =
                jenkinsRule.getInstance().getExtensionList(TracerService.class);
        assertEquals(1, tracerServiceExt.size());
    }

    @BeforeEach
    void beforeEach() {
        spanExporter.reset();
    }

    private List<SpanData> getSpans() {
        TracerService.forceFlush();
        return spanExporter.getFinishedSpanItems();
    }

    private String runCommand(File dir, String command) throws Exception {
        ProcessBuilder pb;
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("bash", "-c", command);
        }
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        return new String(process.getInputStream().readAllBytes()).trim();
    }

    private File createGitRepository(String jobName) throws Exception {
        File repoDir = Files.createTempDirectory(jobName).toFile();

        // Initialize a real Git repository using system commands
        runCommand(repoDir, "git init");
        runCommand(repoDir, "git config user.name 'Test User'");
        runCommand(repoDir, "git config user.email 'test@example.com'");
        assertTrue(new File(repoDir, "README.md").createNewFile());
        runCommand(repoDir, "git add README.md");
        runCommand(repoDir, "git commit -m 'Initial commit'");
        runCommand(repoDir, "git branch -m main");
        return repoDir;
    }

    @Test
    void testFreestyleJob() throws Exception {
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
        project.getBuildersList().add(new Shell("touch foobar"));
        project.getPublishersList().add(new Fingerprinter("foobar"));

        jenkinsRule.buildAndAssertSuccess(project);

        List<SpanData> spans = getSpans();
        assertEquals(4, spans.size());

        String expectedTraceId = spans.get(0).getTraceId();
        spans.forEach(span -> assertEquals(expectedTraceId, span.getTraceId()));

        spans.forEach(span -> assertEquals(StatusData.ok(), span.getStatus()));

        // Common attributes Span
        for (SpanData span : spans) {
            Map<AttributeKey<?>, Object> attributes = span.getAttributes().asMap();
            assertEquals("jenkins", attributes.get(TraceUtils.CICD_PROVIDER_NAME));
            assertEquals("test-freestyle", attributes.get(TraceUtils.CICD_PIPELINE_NAME));
            assertEquals("test-freestyle#1", attributes.get(TraceUtils.CICD_PIPELINE_RUN_ID));
            assertTrue(
                    ((String) attributes.get(TraceUtils.CICD_PIPELINE_URL)).contains("/jenkins/job/test-freestyle/1/"));
            assertEquals("main", attributes.get(TraceUtils.VCS_REF_HEAD_NAME));
            assertEquals(commit, attributes.get(TraceUtils.VCS_REF_HEAD_REVISION));
            assertEquals("https://github.com/mergifyio/plugin/", attributes.get(TraceUtils.VCS_REPOSITORY_URL_FULL));
            assertEquals("GitHubProjectProperty", attributes.get(TraceUtils.VCS_REPOSITORY_URL_SOURCE));
            assertEquals("mergifyio/plugin", attributes.get(TraceUtils.VCS_REPOSITORY_NAME));
            assertEquals("built-in", attributes.get(TraceUtils.CICD_PIPELINE_RUNNER_NAME));
            assertEquals("Default", attributes.get(TraceUtils.CICD_PIPELINE_RUNNER_GROUP_NAME));
        }

        // Step Attributes
        assertEquals("Execute shell", spans.get(0).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
        assertEquals("Execute shell", spans.get(1).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
        assertEquals("Fingerprinter", spans.get(2).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
    }

    @Test
    void testPipelineJob() throws Exception {
        final String jobName = "test-pipeline";
        WorkflowJob job = jenkinsRule.createProject(WorkflowJob.class, jobName);

        // Initialize a fake Git repository
        File repoDir = createGitRepository(jobName);
        String commit = runCommand(repoDir, "git rev-parse HEAD").trim();

        // Define the pipeline script
        String pipelineScript = String.format(
                """
                        pipeline {
                            agent any
                            stages {
                                stage('Checkout') {
                                    steps {
                                        checkout([$class: 'GitSCM', branches: [[name: '*/main']], userRemoteConfigs: [[url: '%s']]])
                                    }
                                }
                                stage('Build') {
                                    steps {
                                        sh 'echo Hello World...'
                                    }
                                }
                            }
                        }""",
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
            assertEquals("test-pipeline", attributes.get(TraceUtils.CICD_PIPELINE_NAME));
            assertEquals("test-pipeline#1", attributes.get(TraceUtils.CICD_PIPELINE_RUN_ID));
            assertTrue(
                    ((String) attributes.get(TraceUtils.CICD_PIPELINE_URL)).contains("/jenkins/job/test-pipeline/1/"));
            assertEquals("main", attributes.get(TraceUtils.VCS_REF_HEAD_NAME));
            assertEquals(commit, attributes.get(TraceUtils.VCS_REF_HEAD_REVISION));
            assertEquals("https://github.com/mergifyio/plugin/", attributes.get(TraceUtils.VCS_REPOSITORY_URL_FULL));
            assertEquals("GitHubProjectProperty", attributes.get(TraceUtils.VCS_REPOSITORY_URL_SOURCE));
            assertEquals("mergifyio/plugin", attributes.get(TraceUtils.VCS_REPOSITORY_NAME));
            assertEquals("built-in", attributes.get(TraceUtils.CICD_PIPELINE_RUNNER_NAME));
            assertEquals("Default", attributes.get(TraceUtils.CICD_PIPELINE_RUNNER_GROUP_NAME));
        }

        // Step Attributes expected:<Stage([Checkout])> but was:<Stage([{ (Checkout)])>
        assertEquals(
                "Stage({ (Checkout))", spans.get(0).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
        assertEquals("Stage({ (Build))", spans.get(1).getAttributes().asMap().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
    }

    @Test
    void testPipelineJobWithPerStageAgent() throws Exception {
        final String jobName = "test-pipeline-agent";

        DumbSlave linuxAgent = jenkinsRule.createOnlineSlave(Label.parseExpression("linux"));

        WorkflowJob job = jenkinsRule.createProject(WorkflowJob.class, jobName);

        File repoDir = createGitRepository(jobName);

        String pipelineScript = String.format(
                """
                        pipeline {
                            agent none
                            stages {
                                stage('Build') {
                                    agent { label 'linux' }
                                    steps {
                                        checkout([$class: 'GitSCM', branches: [[name: '*/main']], userRemoteConfigs: [[url: '%s']]])
                                        sh 'echo hi'
                                    }
                                }
                            }
                        }""",
                repoDir.toURI());

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        job.addProperty(new GithubProjectProperty("https://github.com/mergifyio/plugin"));

        jenkinsRule.buildAndAssertSuccess(job);

        List<SpanData> spans = getSpans();

        SpanData stageSpan = spans.stream()
                .filter(s -> "step".equals(s.getAttributes().get(TraceUtils.CICD_PIPELINE_TASK_SCOPE)))
                .findFirst()
                .orElse(null);
        assertNotNull(stageSpan, "expected a stage span");

        Map<AttributeKey<?>, Object> stageAttrs = stageSpan.getAttributes().asMap();
        assertEquals(linuxAgent.getNodeName(), stageAttrs.get(TraceUtils.CICD_PIPELINE_RUNNER_NAME));
        @SuppressWarnings("unchecked")
        List<String> stageLabels = (List<String>) stageAttrs.get(TraceUtils.CICD_PIPELINE_LABELS);
        assertTrue(stageLabels.contains("linux"), "stage labels should contain 'linux'; got " + stageLabels);

        SpanData jobSpan = spans.stream()
                .filter(s -> "job".equals(s.getAttributes().get(TraceUtils.CICD_PIPELINE_TASK_SCOPE)))
                .findFirst()
                .orElse(null);
        assertNotNull(jobSpan, "expected a job span");
        Map<AttributeKey<?>, Object> jobAttrs = jobSpan.getAttributes().asMap();
        assertEquals(linuxAgent.getNodeName(), jobAttrs.get(TraceUtils.CICD_PIPELINE_RUNNER_NAME));
    }

    @Test
    void testMultibranchJobs() throws Exception {
        File repoDir = createGitRepository("test-multibranch");
        String jenkinsfile = String.format(
                """
                        node {
                            checkout([$class: 'GitSCM', branches: [[name: '*/main']], userRemoteConfigs: [[url: '%s']]])
                        }""",
                repoDir.toURI());

        MockFolder folder = jenkinsRule.createFolder("team");
        WorkflowJob plainJob = folder.createProject(WorkflowJob.class, "plain");
        plainJob.setDefinition(new CpsFlowDefinition(jenkinsfile, true));
        WorkflowRun plainRun = jenkinsRule.buildAndAssertSuccess(plainJob);

        try (MockSCMController controller = MockSCMController.create()) {
            controller.createRepository("my-repo");
            controller.addFile(
                    "my-repo",
                    "master",
                    "add Jenkinsfile",
                    "Jenkinsfile",
                    jenkinsfile.getBytes(StandardCharsets.UTF_8));
            controller.cloneBranch("my-repo", "master", "feature/x");
            String changeRequest = "CR-" + controller.openChangeRequest("my-repo", "master");

            WorkflowMultiBranchProject project = folder.createProject(WorkflowMultiBranchProject.class, "my-repo");
            project.getSourcesList()
                    .add(new BranchSource(new MockSCMSource(
                            controller,
                            "my-repo",
                            new MockSCMDiscoverBranches(),
                            new MockSCMDiscoverChangeRequests(ChangeRequestCheckoutStrategy.HEAD))));
            project.scheduleBuild2(0).getFuture().get();
            jenkinsRule.waitUntilNoActivity();

            Map<String, SpanData> jobSpanByRun = getSpans().stream()
                    .filter(s -> "job".equals(s.getAttributes().get(TraceUtils.CICD_PIPELINE_TASK_SCOPE)))
                    .collect(Collectors.toMap(s -> s.getAttributes().get(TraceUtils.CICD_PIPELINE_RUN_ID), s -> s));
            assertEquals(4, jobSpanByRun.size());

            for (String branchJob : List.of("master", "feature%2Fx", changeRequest)) {
                WorkflowRun run = jenkinsRule.assertBuildStatusSuccess(
                        project.getItem(branchJob).getLastBuild());
                SpanData jobSpan = jobSpanByRun.get(run.getExternalizableId());
                assertNotNull(jobSpan, "no job span for " + run);
                assertEquals("team/my-repo", jobSpan.getName());
                assertEquals("team/my-repo", jobSpan.getAttributes().get(TraceUtils.CICD_PIPELINE_NAME));
                assertEquals("team/my-repo", jobSpan.getAttributes().get(TraceUtils.CICD_PIPELINE_TASK_NAME));
            }
            SpanData plainJobSpan = jobSpanByRun.get(plainRun.getExternalizableId());
            assertEquals("team » plain", plainJobSpan.getName());
            assertEquals("team » plain", plainJobSpan.getAttributes().get(TraceUtils.CICD_PIPELINE_NAME));
        }
    }

    private String commitFile(File repoDir, String path, String content) throws Exception {
        File file = new File(repoDir, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
        runCommand(repoDir, "git add " + path);
        runCommand(repoDir, "git commit -m 'add " + path + "'");
        return runCommand(repoDir, "git rev-parse HEAD");
    }

    @Test
    void testMultibranchJobAfterSharedLibraryCheckout() throws Exception {
        File libraryRepo = createGitRepository("shared-library");
        commitFile(libraryRepo, "vars/greet.groovy", "def call() { echo 'hello' }");
        runCommand(libraryRepo, "git branch -m v1");
        File projectRepo = createGitRepository("my-app");
        String projectCommit = commitFile(projectRepo, "Jenkinsfile", "greet()\nnode { checkout scm }");
        runCommand(projectRepo, "git checkout -b no-checkout");
        commitFile(projectRepo, "Jenkinsfile", "greet()");
        runCommand(projectRepo, "git checkout main");
        String projectUrl = projectRepo.toPath().toUri().toString();

        // An implicit library is checked out when the Jenkinsfile compiles, before
        // the build checks out its own repository.
        LibraryConfiguration library = new LibraryConfiguration(
                "shared-library",
                new SCMSourceRetriever(
                        new GitSCMSource(libraryRepo.toPath().toUri().toString())));
        library.setDefaultVersion("v1");
        library.setImplicit(true);
        List<LibraryConfiguration> previousLibraries = GlobalLibraries.get().getLibraries();
        GlobalLibraries.get().setLibraries(List.of(library));
        // Both repositories are local directories, which Git checkouts refuse by default.
        boolean allowLocalCheckout = GitSCM.ALLOW_LOCAL_CHECKOUT;
        GitSCM.ALLOW_LOCAL_CHECKOUT = true;
        try {
            WorkflowMultiBranchProject project = jenkinsRule.createProject(WorkflowMultiBranchProject.class, "my-app");
            GitSCMSource source = new GitSCMSource(projectUrl);
            source.setTraits(List.of(new BranchDiscoveryTrait()));
            project.getSourcesList().add(new BranchSource(source));
            project.scheduleBuild2(0).getFuture().get();
            jenkinsRule.waitUntilNoActivity();

            Map<String, SpanData> jobSpanByRun = getSpans().stream()
                    .filter(s -> "job".equals(s.getAttributes().get(TraceUtils.CICD_PIPELINE_TASK_SCOPE)))
                    .collect(Collectors.toMap(s -> s.getAttributes().get(TraceUtils.CICD_PIPELINE_RUN_ID), s -> s));
            WorkflowRun run =
                    jenkinsRule.assertBuildStatusSuccess(project.getItem("main").getLastBuild());
            jenkinsRule.assertLogContains("Loading library shared-library@v1", run);
            Map<AttributeKey<?>, Object> attributes =
                    jobSpanByRun.get(run.getExternalizableId()).getAttributes().asMap();
            assertEquals(projectUrl, attributes.get(TraceUtils.VCS_REPOSITORY_URL_FULL));
            assertEquals("SCMRemoteURL", attributes.get(TraceUtils.VCS_REPOSITORY_URL_SOURCE));
            assertEquals(projectCommit, attributes.get(TraceUtils.VCS_REF_HEAD_REVISION));
            assertEquals("main", attributes.get(TraceUtils.VCS_REF_HEAD_NAME));

            // Never checks out its own repository: no commit to report, so nothing is
            // sent, rather than the library's commit.
            WorkflowRun noCheckoutRun = jenkinsRule.assertBuildStatusSuccess(
                    project.getItem("no-checkout").getLastBuild());
            jenkinsRule.assertLogContains("Loading library shared-library@v1", noCheckoutRun);
            assertNull(jobSpanByRun.get(noCheckoutRun.getExternalizableId()));
        } finally {
            GitSCM.ALLOW_LOCAL_CHECKOUT = allowLocalCheckout;
            GlobalLibraries.get().setLibraries(previousLibraries);
        }
    }

    @Test
    void testGitHubPullRequestJobRemote() throws Exception {
        WorkflowMultiBranchProject project = jenkinsRule.createProject(WorkflowMultiBranchProject.class, "github-app");
        try {
            GitHubSCMSource source = new GitHubSCMSource("mergifyio", "my-app");
            source.setId("github");
            source.setTraits(List.of(new SSHCheckoutTrait("ssh-key")));
            project.getSourcesList().add(new BranchSource(source));
            // From a fork: the job still builds the project's repository.
            PullRequestSCMHead pullRequest = new PullRequestSCMHead(
                    "PR-1",
                    "contributor",
                    "my-app",
                    "feature",
                    1,
                    new BranchSCMHead("main"),
                    new SCMHeadOrigin.Fork("contributor"),
                    ChangeRequestCheckoutStrategy.MERGE);
            WorkflowJob job = project.getProjectFactory()
                    .newInstance(new Branch("github", pullRequest, new NullSCM(), List.of()));

            assertEquals("https://github.com/mergifyio/my-app.git", TraceUtils.getMultiBranchRemote(job));
            // Its checkout, over SSH here, is of the same repository, so it describes the job.
            GitSCM checkout = (GitSCM) source.build(pullRequest, null);
            String checkoutUrl = checkout.getUserRemoteConfigs().get(0).getUrl();
            assertTrue(checkoutUrl.startsWith("git@"), checkoutUrl);
            assertEquals("mergifyio/my-app", JobMetadata.getRepositoryName(checkoutUrl));
        } finally {
            project.delete();
        }
    }
}
