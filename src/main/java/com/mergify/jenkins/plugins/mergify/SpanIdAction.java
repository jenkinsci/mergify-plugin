package com.mergify.jenkins.plugins.mergify;

import hudson.model.InvisibleAction;

public class SpanIdAction extends InvisibleAction {
    private final String spanId;

    public SpanIdAction(String spanId) {
        this.spanId = spanId;
    }

    public String getSpanId() {
        return spanId;
    }
}
