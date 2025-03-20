package io.jenkins.plugins.mergify;

import hudson.model.InvisibleAction;

public class ParentSpanAction extends InvisibleAction {
    private final String traceId;
    private final String spanId;

    public ParentSpanAction(String traceId, String spanId) {
        this.spanId = spanId;
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getTraceId() {
        return traceId;
    }
}
