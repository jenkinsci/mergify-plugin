package io.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;

@Extension
public class MergifyTraceparentEnvsContributor extends EnvironmentContributor {
    public void buildEnvironmentFor(Run run, EnvVars envs, TaskListener listener)
            throws IOException, InterruptedException {
        TraceparentAction action = run.getAction(TraceparentAction.class);
        if (action != null) {
            envs.put("MERGIFY_TRACEPARENT", action.getTraceParent());
        }
    }
}
