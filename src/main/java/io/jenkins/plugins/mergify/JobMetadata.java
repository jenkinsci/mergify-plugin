package io.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.*;
import hudson.model.labels.LabelAtom;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import io.opentelemetry.api.trace.Span;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JobMetadata<T extends Job<?, ?>> extends JobProperty<T> {
    private static final Logger LOGGER = Logger.getLogger(JobMetadata.class.getName());
    private final String pipelineName;
    private final String pipelineId;
    private final String pipelineUrl;
    private final String pipelineRunnerName;
    private final List<String> pipelineLabels;
    private final Integer pipelineRunnerId;
    private final Long pipelineCreatedAt;
    private volatile String SCMCheckoutBranch;
    private volatile String SCMCheckoutCommit;
    private Map<String, String> repositoryURLs;

    public JobMetadata(Run<?, ?> run) {
        Job<?, ?> job = run.getParent();
        this.pipelineName = job.getFullDisplayName();
        this.pipelineId = job.getFullDisplayName();
        this.pipelineUrl = Jenkins.get().getRootUrl() + run.getUrl();
        this.repositoryURLs = new LinkedHashMap<>();

        this.pipelineCreatedAt = run.getTimeInMillis();

        Executor executor = run.getExecutor();
        if (executor == null) {
            LOGGER.warning("Run executor is null, cannot set pipeline runner info");
            this.pipelineRunnerId = null;
            this.pipelineRunnerName = null;
            this.pipelineLabels = List.of();
            return;
        }

        Computer computer = executor.getOwner();
        String nodeName = computer.getName();
        this.pipelineRunnerId = executor.getNumber();
        this.pipelineRunnerName = nodeName.isEmpty() ? "master" : nodeName;

        Node node = executor.getOwner().getNode();
        if (node == null) {
            this.pipelineLabels = List.of();
        } else {
            Set<LabelAtom> labels = node.getAssignedLabels();
            this.pipelineLabels = labels.stream().map(LabelAtom::getName).collect(Collectors.toList());
        }
    }

    static String getRepositoryName(String url) {
        if (url == null || url.isEmpty()) {
            return null; // Handle invalid cases
        }

        // Remove .git suffix if present
        url = url.replaceAll("/$", "").replaceAll("\\.git$", "");

        // Match GitHub organization and repository name
        Pattern pattern = Pattern.compile("[:/]([^:/]+)/([^:/]+?)(?:\\.git)?$");
        Matcher matcher = pattern.matcher(url);

        if (matcher.find()) {
            return matcher.group(1) + "/" + matcher.group(2);
        }

        return null; // No valid match found
    }

    // Ensure safe deserialization
    private Object readResolve() {
        if (repositoryURLs == null) {
            repositoryURLs = new ConcurrentHashMap<>();
        }
        return this;
    }

    public void setCommonSpanAttributes(Span span) {
        if (repositoryURLs.isEmpty()) {
            LOGGER.warning("repositoryURLs is empty, skipping span");
            return;
        }

        if (SCMCheckoutBranch == null) {
            LOGGER.warning("SCMCheckoutBranch is null, skipping span");
            return;
        }
        if (SCMCheckoutCommit == null) {
            LOGGER.warning("SCMCheckoutCommit is null, skipping span");
            return;
        }

        span.setAttribute(TraceUtils.CICD_PROVIDER_NAME, "jenkins");
        span.setAttribute(TraceUtils.CICD_PIPELINE_NAME, pipelineName);
        span.setAttribute(TraceUtils.CICD_PIPELINE_ID, pipelineId);
        span.setAttribute(TraceUtils.CICD_PIPELINE_CREATED_AT, pipelineCreatedAt);
        span.setAttribute(TraceUtils.CICD_PIPELINE_URL, pipelineUrl);
        span.setAttribute(TraceUtils.CICD_PIPELINE_LABELS, pipelineLabels);
        span.setAttribute(TraceUtils.VCS_REF_BASE_NAME, SCMCheckoutBranch);
        span.setAttribute(TraceUtils.VCS_REF_HEAD_REVISION, SCMCheckoutCommit);
        if (pipelineRunnerId != null) {
            span.setAttribute(TraceUtils.CICD_PIPELINE_RUNNER_ID, pipelineRunnerId);
        }
        if (pipelineRunnerName != null) {
            span.setAttribute(TraceUtils.CICD_PIPELINE_RUNNER_NAME, pipelineRunnerName);
        }

        for (Map.Entry<String, String> entry : repositoryURLs.entrySet()) {
            String name = entry.getKey();
            String url = entry.getValue();
            LOGGER.info("Using Repository URL from " + name);
            span.setAttribute(TraceUtils.VCS_REPOSITORY_URL_FULL, url);
            span.setAttribute(TraceUtils.VCS_REPOSITORY_URL_SOURCE, name);
            String repositoryName = getRepositoryName(url);
            if (repositoryName != null) {
                span.setAttribute(TraceUtils.VCS_REPOSITORY_NAME, repositoryName);
            }
            break;
        }
    }

    public void addRepositoryURL(String name, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        repositoryURLs.put(name, url);
    }

    public void setSCMCheckoutInfoFromEnvs(EnvVars envVars) {
        addRepositoryURL("SCMCheckoutURL", envVars.get("GIT_URL"));
        SCMCheckoutCommit = envVars.get("GIT_COMMIT");
        SCMCheckoutBranch = envVars.get("GIT_BRANCH");
        if (SCMCheckoutBranch != null) {
            // Removes "origin/" or any "remote/"
            SCMCheckoutBranch = SCMCheckoutBranch.replaceFirst("^[^/]+/", "");
        }
    }

    public void setSCMCheckoutInfoFromGitSCM(GitSCM gitSCM, GitClient client) throws InterruptedException {
        if (SCMCheckoutBranch != null && SCMCheckoutCommit != null) {
            return;
        }
        // Retrieve repository URL
        List<UserRemoteConfig> remoteConfigs = gitSCM.getUserRemoteConfigs();
        addRepositoryURL(
                "GitSCM", remoteConfigs.isEmpty() ? null : remoteConfigs.get(0).getUrl());

        // Retrieve branch
        List<BranchSpec> branches = gitSCM.getBranches();
        SCMCheckoutBranch = branches.isEmpty() ? null : branches.get(0).getName();
        if (SCMCheckoutBranch != null) {
            // Removes "origin/" or any "remote/"
            SCMCheckoutBranch = SCMCheckoutBranch.replaceFirst("^[^/]+/", "");
        }

        SCMCheckoutCommit = client.revParse("HEAD").name();
    }

    @Extension
    public static final class RunSpanPropertyDescriptor extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Mergify Job Span Attributes";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }
    }
}
