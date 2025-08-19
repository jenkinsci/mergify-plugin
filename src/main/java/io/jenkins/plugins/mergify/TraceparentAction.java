package io.jenkins.plugins.mergify;

import hudson.model.InvisibleAction;
import io.opentelemetry.api.trace.SpanContext;

public class TraceparentAction extends InvisibleAction {
    private final String traceparent;

    public TraceparentAction(SpanContext spanContext) {
        this.traceparent = "00-" + spanContext.getTraceId() + "-" + spanContext.getSpanId() + "-01";
    }

    public String getTraceParent() {
        return this.traceparent;
    }
}
