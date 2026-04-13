package io.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.model.*;
import hudson.plugins.git.*;
import hudson.plugins.git.util.BuildData;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

public class JobMetadata implements Action {
    private static final Logger LOGGER = Logger.getLogger(JobMetadata.class.getName());
    private final String pipelineName;
    private final String pipelineId;
    private final String pipelineUrl;
    private final Long pipelineCreatedAt;
    private volatile RunnerInfo runnerInfo;
    private volatile boolean runnerInfoUpgraded;
    private volatile String SCMCheckoutBranch;
    private volatile String SCMCheckoutCommit;
    private Map<String, String> repositoryURLs;
    private volatile String jobTraceId;
    private volatile String jobSpanId;

    public JobMetadata(Run<?, ?> run) {
        Job<?, ?> job = run.getParent();
        this.pipelineName = job.getFullDisplayName();
        this.pipelineId = run.getExternalizableId();
        this.pipelineUrl = run.getUrl();
        this.repositoryURLs = new LinkedHashMap<>();
        // Nanoseconds: matches the unit of OTEL span start/end times so the
        // backend can apply the same /1e9 conversion uniformly.
        this.pipelineCreatedAt = TimeUnit.MILLISECONDS.toNanos(run.getTimeInMillis());

        // For freestyle jobs, the executor is the final answer at onStarted time.
        // For pipeline jobs, it's the flyweight executor on built-in; the real
        // agent is discovered later when a node { } block runs and
        // upgradeRunnerInfo is called with the agent from the flow graph.
        if (run instanceof WorkflowRun) {
            this.runnerInfo = null;
        } else {
            this.runnerInfo = RunnerInfo.fromExecutor(run.getExecutor());
        }
    }

    public synchronized void upgradeRunnerInfo(RunnerInfo info) {
        if (info == null || runnerInfoUpgraded) {
            return;
        }
        this.runnerInfo = info;
        this.runnerInfoUpgraded = true;
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

    public void setSpanContext(SpanContext spanContext) {
        this.jobTraceId = spanContext.getTraceId();
        this.jobSpanId = spanContext.getSpanId();
    }

    @Override
    public String getIconFileName() {
        return "/plugin/mergify/images/logo.png";
    }

    @Override
    public String getDisplayName() {
        return "Mergify CI Insights";
    }

    @Override
    public String getUrlName() {
        String login = null;
        String repository = null;
        for (Map.Entry<String, String> entry : repositoryURLs.entrySet()) {
            String url = entry.getValue();
            String repositoryName = getRepositoryName(url);
            if (repositoryName != null) {
                String[] parts = repositoryName.split("/", 2);
                login = parts[0];
                repository = parts[1];
            }
            break;
        }
        String url = MergifyConfiguration.get().getDashboardUrl();
        String path;
        try {
            path = DashboardUrlBuilder.buildUrl(
                    login, repository, this.pipelineName, this.pipelineName, this.jobTraceId, this.jobSpanId);
        } catch (Exception e) {
            LOGGER.warning("Failed to build dashboard URL: " + e.getMessage());
            path = "/ci-insights/jobs";
        }
        return url + path;
    }

    // Ensure safe deserialization
    private Object readResolve() {
        if (repositoryURLs == null) {
            repositoryURLs = new ConcurrentHashMap<>();
        }
        return this;
    }

    public void setCommonSpanAttributes(Span span) {
        setCommonSpanAttributes(span, null);
    }

    public void setCommonSpanAttributes(Span span, RunnerInfo runnerOverride) {
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
        span.setAttribute(TraceUtils.CICD_PIPELINE_RUN_ID, pipelineId);
        // deprecated, but kept for compatibility
        span.setAttribute(TraceUtils.CICD_PIPELINE_ID, pipelineId);
        span.setAttribute(TraceUtils.CICD_PIPELINE_CREATED_AT, pipelineCreatedAt);
        span.setAttribute(TraceUtils.CICD_PIPELINE_URL, Jenkins.get().getRootUrl() + pipelineUrl);
        span.setAttribute(TraceUtils.CICD_PIPELINE_RUNNER_GROUP_NAME, RunnerInfo.DEFAULT_GROUP_NAME);
        if (SCMCheckoutBranch != null) {
            span.setAttribute(TraceUtils.VCS_REF_HEAD_NAME, SCMCheckoutBranch.replaceFirst("^[^/]+/", ""));
        } else {
            span.setAttribute(TraceUtils.VCS_REF_HEAD_NAME, "<unknown>");
        }
        span.setAttribute(TraceUtils.VCS_REF_HEAD_REVISION, SCMCheckoutCommit);

        RunnerInfo effectiveRunner = runnerOverride != null ? runnerOverride : runnerInfo;
        if (effectiveRunner != null) {
            span.setAttribute(TraceUtils.CICD_PIPELINE_LABELS, effectiveRunner.getLabels());
            span.setAttribute(TraceUtils.CICD_PIPELINE_RUNNER_NAME, effectiveRunner.getName());
            if (effectiveRunner.getId() != null) {
                span.setAttribute(TraceUtils.CICD_PIPELINE_RUNNER_ID, effectiveRunner.getId());
            }
        }

        for (Map.Entry<String, String> entry : repositoryURLs.entrySet()) {
            String name = entry.getKey();
            String url = entry.getValue();
            LOGGER.fine("Using Repository URL from " + name);
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
        if (SCMCheckoutBranch != null && SCMCheckoutCommit != null) {
            return;
        }
        SCMCheckoutCommit = envVars.get("GIT_COMMIT");
        SCMCheckoutBranch = envVars.get("GIT_BRANCH");
    }

    public void setSCMCheckoutInfoFromBuildData(Run<?, ?> run) {
        if (SCMCheckoutBranch != null && SCMCheckoutCommit != null) return;

        BuildData data = run.getAction(BuildData.class);
        if (data == null) return;

        Collection<String> urls = data.getRemoteUrls();
        for (String url : urls) {
            addRepositoryURL("SCMCheckoutURL", url);
            break;
        }

        Revision revision = data.getLastBuiltRevision();
        if (revision != null) {
            SCMCheckoutCommit = revision.getSha1String();

            Collection<Branch> branches = revision.getBranches();
            for (Branch branch : branches) {
                SCMCheckoutBranch = branch.getName();
                break;
            }
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
        SCMCheckoutCommit = client.revParse("HEAD").name();
    }
}
