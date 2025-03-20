package com.mergify.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;

@Extension
public class MergifySpanIdEnvsContributor extends EnvironmentContributor {

    public void buildEnvironmentFor(Run run, EnvVars envs, TaskListener listener) {
        ParentSpanAction action = run.getAction(ParentSpanAction.class);
        if (action != null) {
            envs.put("MERGIFY_SPAN_ID", action.getSpanId());
            envs.put("MERGIFY_TRACE_ID", action.getTraceId());
        }
    }
}
