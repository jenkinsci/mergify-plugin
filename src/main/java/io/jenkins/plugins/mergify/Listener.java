package io.jenkins.plugins.mergify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import hudson.tasks.BuildStep;
import hudson.tasks.Builder;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.support.steps.StageStep;

@Extension
public class Listener extends RunListener<Run<?, ?>> implements GraphListener.Synchronous {
    private static final Logger LOGGER = Logger.getLogger(Listener.class.getName());
    private static final Map<FlowNode, Span> stageSpans = new ConcurrentHashMap<>();
    private static final Map<BuildStep, Span> stepSpans = new ConcurrentHashMap<>();
    private static final Map<Run<?, ?>, Span> buildSpans = new ConcurrentHashMap<>();

    @CheckForNull
    private static WorkflowRun getWorkflowRun(@NonNull FlowNode flowNode) {
        Queue.Executable executable;
        try {
            executable = flowNode.getExecution().getOwner().getExecutable();
        } catch (IOException e) {
            // Ignore exception. Likely to be a `new IOException("not implemented")` thrown by
            // org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner.DummyOwner.getExecutable
            return null;
        }

        if (executable instanceof WorkflowRun) {
            return (WorkflowRun) executable;
        }
        return null;
    }

    private static boolean isStageStartNode(FlowNode node) {
        if (!(node instanceof StepStartNode stepStartNode)) {
            return false;
        }
        Descriptor<Step> nodeDescriptor = stepStartNode.getDescriptor();
        return nodeDescriptor instanceof StageStep.DescriptorImpl && node.getAction(LabelAction.class) != null;
    }

    private static boolean isStageEndNode(FlowNode node) {
        if (!(node instanceof StepEndNode)) {
            return false;
        }
        StepStartNode stepStartNode = ((StepEndNode) node).getStartNode();
        return isStageStartNode(stepStartNode);
    }

    static boolean isValidBuildStep(BuildStep step) {
        return step instanceof Builder // Normal build steps
                || Jenkins.get().getExtensionList(BuildStep.class).contains(step);
    }

    private static String getStageName(StepStartNode stepStartNode) {
        return "Stage(" + stepStartNode.getDisplayFunctionName() + ")";
    }

    // Pipeline stage Listener
    @Override
    public void onNewHead(FlowNode node) {
        if (isStageStartNode(node)) {
            startStageSpan(node);
        } else if (isStageEndNode(node)) {
            endStageSpan((StepEndNode) node);
        }
    }

    // Pipeline and Freestyle Job listener
    public void onStarted(Run<?, ?> run, @NonNull TaskListener listener) {
        LOGGER.fine("build " + run.getFullDisplayName() + " started");
        Span span = TraceUtils.startJobSpan(run);
        if (span != null) {
            buildSpans.put(run, span);
        }
    }

    // Pipeline and Freestyle Job listener
    public void onCompleted(Run<?, ?> run, @Nonnull TaskListener listener) {
        LOGGER.fine("build " + run.getFullDisplayName() + " completed");
        Span span = buildSpans.remove(run);
        TraceUtils.endJobSpan(span, run);
    }

    private void startStageSpan(FlowNode node) {
        WorkflowRun run = getWorkflowRun(node);

        StepStartNode stepStartNode = (StepStartNode) node;
        String stageName = getStageName(stepStartNode);
        Span parentSpan = buildSpans.get(run);
        Span span = TraceUtils.startJobStepSpan(run, parentSpan, stageName, stepStartNode.getId());
        if (span != null) {
            stageSpans.put(node, span);
        }

        LOGGER.fine("Stage started: " + stageName);
    }

    private void endStageSpan(StepEndNode stepEndNode) {
        StepStartNode stepStartNode = stepEndNode.getStartNode();
        Span span = stageSpans.get(stepStartNode);

        WorkflowRun run = getWorkflowRun(stepStartNode);
        ErrorAction error = stepEndNode.getError();
        TraceUtils.endJobStepSpan(span, run, error != null);

        String stageName = getStageName(stepStartNode);
        LOGGER.fine("Stage stopped: " + stageName);
    }

    // Freestyle Job step Listener
    @Extension
    public static class BuildStepListener extends hudson.model.BuildStepListener {

        private static String getStepName(BuildStep step) {
            if (step instanceof Builder) {
                return ((Builder) step).getDescriptor().getDisplayName();
            }
            return step.getClass().getSimpleName();
        }

        @Override
        public void started(AbstractBuild build, BuildStep step, BuildListener listener) {
            if (!isValidBuildStep(step)) {
                LOGGER.fine("Step ignored: " + step);
                return;
            }

            String stepName = getStepName(step);
            Span parentSpan = buildSpans.get(build);
            Span span = TraceUtils.startJobStepSpan(build, parentSpan, stepName, step.toString());
            if (span != null) {
                stepSpans.put(step, span);
            }

            LOGGER.fine("Step started: " + stepName);
        }

        @Override
        public void finished(AbstractBuild build, BuildStep step, BuildListener listener, boolean canContinue) {
            if (!isValidBuildStep(step)) {
                LOGGER.fine("Step ignored: " + step);
                return;
            }
            Span span = stepSpans.get(step);
            TraceUtils.endJobStepSpan(span, build, !canContinue);
            String stepName = getStepName(step);
            LOGGER.fine("Step stopped: " + stepName);
        }
    }

    @Extension
    public static class SCMListener extends hudson.model.listeners.SCMListener {
        private static EnvVars getEnvironment(Run<?, ?> run, TaskListener listener) {
            try {
                return run.getEnvironment(listener);
            } catch (IOException | InterruptedException e) {
                return null;
            }
        }

        // Not called if a checkout fails.
        @Override
        public void onCheckout(
                Run<?, ?> run,
                SCM scm,
                FilePath workspace,
                TaskListener listener,
                File changelogFile,
                SCMRevisionState pollingBaseline)
                throws IOException, InterruptedException {
            LOGGER.fine("SCM checkout hooks!");

            JobMetadata jobSpanMetadata = TraceUtils.getJobMetadata(run);
            if (jobSpanMetadata == null) {
                LOGGER.fine("Got SCM checkout without job metadata");
                return;
            }

            EnvVars envVars = getEnvironment(run, listener);
            if (envVars != null) {
                LOGGER.fine("Got SCM checkout data: " + envVars);
                jobSpanMetadata.setSCMCheckoutInfoFromEnvs(envVars);
            }
            if (scm instanceof GitSCM gitSCM) {
                GitClient client = gitSCM.createClient(listener, envVars, run, workspace);
                jobSpanMetadata.setSCMCheckoutInfoFromGitSCM(gitSCM, client);
            } else {
                LOGGER.fine("SCM is not GitSCM, skipping checkout info");
            }
        }
    }
}
