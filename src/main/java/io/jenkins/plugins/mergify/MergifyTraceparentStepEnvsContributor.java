package io.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepEnvironmentContributor;

@Extension
public class MergifyTraceparentStepEnvsContributor extends StepEnvironmentContributor {
    @Override
    public void buildEnvironmentFor(StepContext stepContext, EnvVars envs, TaskListener listener)
            throws IOException, InterruptedException {
        super.buildEnvironmentFor(stepContext, envs, listener);
        FlowNode node = stepContext.get(FlowNode.class);
        if (node == null) {
            return;
        }

        TraceparentAction action = node.getAction(TraceparentAction.class);
        if (action != null) {
            envs.put("MERGIFY_TRACEPARENT", action.getTraceParent());
        }
    }
}
