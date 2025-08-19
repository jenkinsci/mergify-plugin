package io.jenkins.plugins.mergify;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.tasks.BuildStep;
import hudson.tasks.Builder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.logging.Logger;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;

public class TraceUtils {
    public static final AttributeKey<String> CICD_PROVIDER_NAME = AttributeKey.stringKey("cicd.provider.name");

    // PIPELINE ATTRIBUTES
    public static final AttributeKey<String> CICD_PIPELINE_NAME = AttributeKey.stringKey("cicd.pipeline.name");
    public static final AttributeKey<String> CICD_PIPELINE_URL = AttributeKey.stringKey("cicd.pipeline.url");
    public static final AttributeKey<Long> CICD_PIPELINE_CREATED_AT = AttributeKey.longKey("cicd.pipeline.created_at");
    public static final AttributeKey<String> CICD_PIPELINE_RESULT = AttributeKey.stringKey("cicd.pipeline.result");
    public static final AttributeKey<List<String>> CICD_PIPELINE_LABELS =
            AttributeKey.stringArrayKey("cicd.pipeline.labels");
    public static final AttributeKey<String> CICD_PIPELINE_RUN_ID = AttributeKey.stringKey("cicd.pipeline.run.id");

    // PIPELINE RUNNER ATTRIBUTES
    public static final AttributeKey<Long> CICD_PIPELINE_RUNNER_ID = AttributeKey.longKey("cicd.pipeline.runner.id");
    public static final AttributeKey<String> CICD_PIPELINE_RUNNER_NAME =
            AttributeKey.stringKey("cicd.pipeline.runner.name");

    // PIPELINE TASK ATTRIBUTES
    public static final AttributeKey<String> CICD_PIPELINE_TASK_RUN_ID =
            AttributeKey.stringKey("cicd.pipeline.task.run.id");
    public static final AttributeKey<String> CICD_PIPELINE_TASK_NAME =
            AttributeKey.stringKey("cicd.pipeline.task.name");
    public static final AttributeKey<String> CICD_PIPELINE_TASK_SCOPE =
            AttributeKey.stringKey("cicd.pipeline.task.scope");
    public static final AttributeKey<String> CICD_PIPELINE_TASK_RUN_RESULT =
            AttributeKey.stringKey("cicd.pipeline.task.run.result");

    public static final AttributeKey<String> VCS_REF_BASE_NAME = AttributeKey.stringKey("vcs.ref.base.name");
    public static final AttributeKey<String> VCS_REF_HEAD_NAME = AttributeKey.stringKey("vcs.ref.head.name");
    public static final AttributeKey<String> VCS_REF_HEAD_REVISION = AttributeKey.stringKey("vcs.ref.head.revision");
    public static final AttributeKey<String> VCS_REPOSITORY_NAME = AttributeKey.stringKey("vcs.repository.name");
    public static final AttributeKey<String> VCS_REPOSITORY_URL_FULL =
            AttributeKey.stringKey("vcs.repository.url.full");
    public static final AttributeKey<String> VCS_REPOSITORY_URL_SOURCE =
            AttributeKey.stringKey("vcs.repository.url.source");

    // FIXME: remove me we use CICD_PIPELINE_TASK_SCOPE instead
    public static final AttributeKey<String> CICD_PIPELINE_SCOPE = AttributeKey.stringKey("cicd.pipeline.scope");
    public static final AttributeKey<String> CICD_PIPELINE_ID = AttributeKey.stringKey("cicd.pipeline.id");

    private static final Logger LOGGER = Logger.getLogger(TraceUtils.class.getName());

    public static void endJobSpan(Span span, Run<?, ?> run) {
        if (span == null) {
            LOGGER.warning("Got completed job without span");
            return;
        }

        JobMetadata jobSpanMetadata = getJobMetadata(run);
        if (jobSpanMetadata == null) {
            LOGGER.warning("Got completed stage/step no job metadata");
        } else {
            jobSpanMetadata.setSCMCheckoutInfoFromBuildData(run);
            jobSpanMetadata.setCommonSpanAttributes(span);
        }
        Result result = run.getResult();
        if (result == null) {
            span.setStatus(StatusCode.UNSET);
            span.setAttribute(CICD_PIPELINE_RESULT, "unknown");
            span.setAttribute(CICD_PIPELINE_TASK_RUN_RESULT, "unknown");
        } else if (result.equals(Result.SUCCESS)) {
            span.setStatus(StatusCode.OK);
            span.setAttribute(CICD_PIPELINE_RESULT, "success");
            span.setAttribute(CICD_PIPELINE_TASK_RUN_RESULT, "success");
        } else if (result.equals(Result.NOT_BUILT)) {
            span.setStatus(StatusCode.OK);
            span.setAttribute(CICD_PIPELINE_RESULT, "skipped");
            span.setAttribute(CICD_PIPELINE_TASK_RUN_RESULT, "skipped");
        } else if (result.equals(Result.ABORTED)) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(CICD_PIPELINE_RESULT, "cancelled");
            span.setAttribute(CICD_PIPELINE_TASK_RUN_RESULT, "cancelled");
        } else if (result.equals(Result.FAILURE) || result.equals(Result.UNSTABLE)) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(CICD_PIPELINE_RESULT, "failure");
            span.setAttribute(CICD_PIPELINE_TASK_RUN_RESULT, "failure");
        } else {
            throw new RuntimeException("unexpected result: " + result);
        }
        span.end();
    }

    public static void endJobStepSpan(Span span, Run<?, ?> run, boolean isError) {
        if (span == null) {
            LOGGER.fine("Got completed stage/step without span");
            return;
        }
        if (run == null) {
            LOGGER.fine("Got completed stage/step without RunSpanAction");
            span.end();
            return;
        }

        JobMetadata jobSpanMetadata = getJobMetadata(run);
        if (jobSpanMetadata == null) {
            LOGGER.fine("Got completed stage/step no job metadata");
            span.end();
            return;
        }

        jobSpanMetadata.setCommonSpanAttributes(span);

        if (isError) {
            span.setAttribute(CICD_PIPELINE_TASK_RUN_RESULT, "failure");
            span.setStatus(StatusCode.ERROR);
        } else {
            span.setAttribute(CICD_PIPELINE_TASK_RUN_RESULT, "success");
            span.setStatus(StatusCode.OK);
        }
        span.end();
    }

    private static String getStepName(BuildStep step) {
        if (step instanceof Builder) {
            return ((Builder) step).getDescriptor().getDisplayName();
        }
        return step.getClass().getSimpleName();
    }

    public static Span startJobStepSpan(Run<?, ?> run, Span parentSpan, String stepName, String stepId) {
        if (parentSpan == null) {
            LOGGER.fine("Got completed step without parent span");
            return null;
        }
        Context parentContext = Context.current().with(parentSpan);
        Tracer tracer = TracerService.getTracer();
        return tracer.spanBuilder(stepName)
                .setParent(Context.current().with(parentSpan))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(CICD_PIPELINE_SCOPE, "step")
                .setAttribute(CICD_PIPELINE_TASK_SCOPE, "step")
                .setAttribute(CICD_PIPELINE_TASK_NAME, stepName)
                .setAttribute(CICD_PIPELINE_TASK_RUN_ID, stepId)
                .setParent(parentContext)
                .startSpan();
    }

    public static Span startJobSpan(Run<?, ?> run) {
        if (run == null) {
            LOGGER.fine("Got start RootSpan without Run");
            return null;
        }
        Job<?, ?> job = run.getParent();
        Tracer tracer = TracerService.getTracer();
        Span span = tracer.spanBuilder(job.getFullDisplayName())
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(CICD_PIPELINE_SCOPE, "job")
                .setAttribute(CICD_PIPELINE_TASK_SCOPE, "job")
                .setAttribute(CICD_PIPELINE_TASK_NAME, job.getFullDisplayName())
                .setAttribute(CICD_PIPELINE_TASK_RUN_ID, job.getFullDisplayName())
                .startSpan();

        JobMetadata jobSpanMetadata = getJobMetadata(run);
        if (jobSpanMetadata == null) {
            LOGGER.warning("Got start RootSpan  without job metadata");
            return null;
        }
        jobSpanMetadata.addRepositoryURL("GitHubProjectProperty", getGitHubProjectRepositoryUrl(run));
        jobSpanMetadata.addRepositoryURL("SCMRemoteURL", getSCMRepositoryUrl(run));
        return span;
    }

    public static JobMetadata getJobMetadata(@Nonnull Run<?, ?> run) {
        JobMetadata jobMetadata = run.getAction(JobMetadata.class);
        if (jobMetadata != null) {
            return jobMetadata;
        }
        JobMetadata newJobMetadata = new JobMetadata(run);
        run.addAction(newJobMetadata);
        return newJobMetadata;
    }

    public static String getGitHubProjectRepositoryUrl(Run<?, ?> run) {
        // Check GitHub Project Property (GitHub Project Plugin)
        Job<?, ?> job = run.getParent();
        GithubProjectProperty githubProjectProperty = job.getProperty(GithubProjectProperty.class);
        if (githubProjectProperty != null) {
            return githubProjectProperty.getProjectUrlStr();
        }
        return null;
    }

    public static String getSCMRepositoryUrl(Run<?, ?> run) {
        Job<?, ?> job = run.getParent();

        // Check if it's a multibranch pipeline with GitHub source
        if (job instanceof SCMSourceOwner scmOwner) {
            for (SCMSource source : scmOwner.getSCMSources()) {
                if (source instanceof GitHubSCMSource) {
                    return ((GitHubSCMSource) source).getRemote();
                }
            }
        }

        // Check if it's a Freestyle job with Git SCM
        if (job instanceof hudson.model.AbstractProject) {
            SCM scm = ((hudson.model.AbstractProject<?, ?>) job).getScm();
            if (scm instanceof GitSCM gitSCM) {
                return gitSCM.getRepositories().get(0).getURIs().get(0).toString();
            }
        }

        return null; // No GitHub source found
    }
}
