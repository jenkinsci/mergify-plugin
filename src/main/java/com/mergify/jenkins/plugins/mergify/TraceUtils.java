package com.mergify.jenkins.plugins.mergify;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jenkins.scm.api.SCMRevisionAction;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.logging.Logger;


public class TraceUtils {
    final public static AttributeKey<String> CICD_PIPELINE_NAME = AttributeKey.stringKey("cicd.pipeline.name");
    final public static AttributeKey<String> CICD_PIPELINE_URL = AttributeKey.stringKey("cicd.pipeline.url");
    final public static AttributeKey<String> CICD_PIPELINE_TASK_ID = AttributeKey.stringKey("cicd.pipeline.task.id");
    final public static AttributeKey<String> CICD_PIPELINE_TASK_NAME = AttributeKey.stringKey("cicd.pipeline.task.name");
    final public static AttributeKey<String> CICD_PIPELINE_ID = AttributeKey.stringKey("cicd.pipeline.id");
    final public static AttributeKey<String> CICD_PROVIDER_NAME = AttributeKey.stringKey("cicd.provider.name");
    final public static AttributeKey<String> VCS_REF_BASE_NAME = AttributeKey.stringKey("vcs.ref.base.name");
    final public static AttributeKey<String> VCS_REF_HEAD_REVISION = AttributeKey.stringKey("vcs.ref.head.revision");
    final public static AttributeKey<String> VCS_REPOSITORY_NAME = AttributeKey.stringKey("vcs.repository.name");
    final public static AttributeKey<String> VCS_REPOSITORY_URL_FULL = AttributeKey.stringKey("vcs.repository.url.full");
    final public static AttributeKey<String> VCS_REPOSITORY_URL_SOURCE = AttributeKey.stringKey("vcs.repository.url.source");
    private static final Logger LOGGER = Logger.getLogger(TraceUtils.class.getName());


    public static void setSpanStatusFromResult(Span span, Run<?, ?> run) {
        Result result = run.getResult();
        if (result == null) {
            span.setAttribute("cicd.pipeline.result", "unknown");
            LOGGER.warning("got null result");
            return;
        }
        if (result.equals(Result.SUCCESS)) {
            span.setStatus(StatusCode.OK);
            span.setAttribute("cicd.pipeline.result", "success");
        } else if (result.equals(Result.NOT_BUILT)) {
            span.setStatus(StatusCode.OK);
            span.setAttribute("cicd.pipeline.result", "skipped");
        } else if (result.equals(Result.ABORTED) || result.equals(Result.FAILURE) || result.equals(Result.UNSTABLE)) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("cicd.pipeline.result", "failure");
        } else {
            span.setAttribute("cicd.pipeline.result", result.toString());
            LOGGER.warning("unexpected result: " + result);
        }
    }


    public static String getCheckoutSHA(WorkflowRun run, TaskListener listener) {
        // 1️⃣ First, try getting SHA from SCMRevisionAction (Best for Multibranch Pipelines)
        SCMRevisionAction scmAction = run.getAction(SCMRevisionAction.class);
        if (scmAction != null) {
            return scmAction.getRevision().toString();
        }

        // 2️⃣ Next, try getting SHA from BuildData (Works with Git Plugin)
        BuildData buildData = run.getAction(BuildData.class);
        if (buildData != null && buildData.lastBuild != null) {
            return buildData.getLastBuiltRevision().getSha1String();
        }

        // 3️⃣ Then, try SCMCheckoutAction (Works if checkout scm was used)
        /*SCMCheckoutAction checkoutAction = run.getAction(SCMCheckoutAction.class);
        if (checkoutAction != null) {
            return checkoutAction.getCommitId();
        }

        // 4️⃣ Finally, fallback to GIT_COMMIT environment variable (Only works if checkout scm was used)
        try {
            EnvVars envVars = run.getEnvironment(listener);
            return envVars.get("GIT_COMMIT");
        } catch (Exception e) {
            e.printStackTrace();
        }*/

        // ❌ No SHA found
        return null;
    }

    private String getGitCommitSHA(Run<?, ?> run) {
        BuildData buildData = run.getAction(BuildData.class);
        if (buildData != null) {
            for (Build build : buildData.buildsByBranchName.values()) {
                if (build.getBuildNumber() == run.getNumber()) {
                    return build.getRevision().getSha1String();
                }
            }
        }
        return null;
    }
}
