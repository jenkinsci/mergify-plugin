package io.jenkins.plugins.mergify;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.TaskListener;
import java.io.IOException;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepEnvironmentContributor;

@Extension
public class MergifySpanIdEnvsContributor extends StepEnvironmentContributor {

    public void buildEnvironmentFor(StepContext stepContext, EnvVars envs, TaskListener listener) {
        FlowNode node;
        try {
            node = stepContext.get(FlowNode.class);
        } catch (IOException | InterruptedException e) {
            return;
        }

        ParentSpanAction action = node.getAction(ParentSpanAction.class);
        if (action != null) {
            envs.put("MERGIFY_TRACEPARENT", action.getTraceParent());
        }
    }
}
