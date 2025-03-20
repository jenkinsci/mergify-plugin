package io.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Run;
import io.opentelemetry.api.trace.Span;
import jenkins.model.Jenkins;

import java.util.LinkedHashMap;
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

    public void setSCMCheckoutInfoFromEnvs(EnvVars envVars) {
        this.addRepositoryURL("SCMCheckoutURL", envVars.get("GIT_URL"));
        this.SCMCheckoutCommit = envVars.get("GIT_COMMIT");
        this.SCMCheckoutBranch = envVars.get("GIT_BRANCH");
        if (this.SCMCheckoutBranch != null) {
            // Removes "origin/" or any "remote/"
            this.SCMCheckoutBranch = this.SCMCheckoutBranch.replaceFirst("^[^/]+/", "");
        }
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
