package io.jenkins.plugins.mergify;

import hudson.model.InvisibleAction;
import io.opentelemetry.api.trace.SpanContext;

public class ParentSpanAction extends InvisibleAction {
    private final String traceparent;

    public ParentSpanAction(SpanContext spanContext) {
        this.traceparent = "00-" + spanContext.getTraceId() + "-" + spanContext.getSpanId() + "-01";
    }

    public String getTraceParent() {
        return this.traceparent;
    }
}
