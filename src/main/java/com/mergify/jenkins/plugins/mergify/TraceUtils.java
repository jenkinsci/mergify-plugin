package com.mergify.jenkins.plugins.mergify;

import hudson.model.Result;
import hudson.model.Run;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.logging.Logger;

public class TraceUtils {
    public static final AttributeKey<String> CICD_PIPELINE_NAME = AttributeKey.stringKey("cicd.pipeline.name");
    public static final AttributeKey<String> CICD_PIPELINE_URL = AttributeKey.stringKey("cicd.pipeline.url");
    public static final AttributeKey<String> CICD_PIPELINE_TASK_ID = AttributeKey.stringKey("cicd.pipeline.task.id");
    public static final AttributeKey<String> CICD_PIPELINE_TASK_NAME =
            AttributeKey.stringKey("cicd.pipeline.task.name");
    public static final AttributeKey<String> CICD_PIPELINE_ID = AttributeKey.stringKey("cicd.pipeline.id");
    public static final AttributeKey<String> CICD_PROVIDER_NAME = AttributeKey.stringKey("cicd.provider.name");
    public static final AttributeKey<String> VCS_REF_BASE_NAME = AttributeKey.stringKey("vcs.ref.base.name");
    public static final AttributeKey<String> VCS_REF_HEAD_REVISION = AttributeKey.stringKey("vcs.ref.head.revision");
    public static final AttributeKey<String> VCS_REPOSITORY_NAME = AttributeKey.stringKey("vcs.repository.name");
    public static final AttributeKey<String> VCS_REPOSITORY_URL_FULL =
            AttributeKey.stringKey("vcs.repository.url.full");
    public static final AttributeKey<String> VCS_REPOSITORY_URL_SOURCE =
            AttributeKey.stringKey("vcs.repository.url.source");
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
}
