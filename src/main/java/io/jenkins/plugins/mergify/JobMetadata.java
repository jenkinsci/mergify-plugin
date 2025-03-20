package io.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Run;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import io.opentelemetry.api.trace.Span;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JobMetadata<T extends Job<?, ?>> extends JobProperty<T> {
    private static final Logger LOGGER = Logger.getLogger(JobMetadata.class.getName());
    private final String pipelineName;
    private final String pipelineId;
    private final String pipelineUrl;
    private volatile String SCMCheckoutBranch;
    private volatile String SCMCheckoutCommit;
    private Map<String, String> repositoryURLs;

    public JobMetadata(Run<?, ?> run) {
        this.pipelineName = run.getFullDisplayName();
        this.pipelineId = run.getExternalizableId();
        this.pipelineUrl = Jenkins.get().getRootUrl() + run.getUrl();
        this.repositoryURLs = new LinkedHashMap<>();
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
        span.setAttribute(TraceUtils.CICD_PIPELINE_URL, pipelineUrl);
        span.setAttribute(TraceUtils.VCS_REF_BASE_NAME, SCMCheckoutBranch);
        span.setAttribute(TraceUtils.VCS_REF_HEAD_REVISION, SCMCheckoutCommit);

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
        addRepositoryURL("GitSCM", remoteConfigs.isEmpty() ? null : remoteConfigs.get(0).getUrl());

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
