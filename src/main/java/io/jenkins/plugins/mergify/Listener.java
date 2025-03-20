package io.jenkins.plugins.mergify;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
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
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import jakarta.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.support.steps.StageStep;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Extension
public class Listener extends RunListener<Run<?, ?>> implements GraphListener.Synchronous {
    private static final Logger LOGGER = Logger.getLogger(Listener.class.getName());
    private static final Map<FlowNode, Span> stageSpans = new ConcurrentHashMap<>();
    private static final Map<BuildStep, Span> stepSpans = new ConcurrentHashMap<>();
    private static final Map<Run<?, ?>, Span> buildSpans = new ConcurrentHashMap<>();

    public static JobMetadata getJobMetadata(@Nonnull Run<?, ?> run) {
        JobMetadata jobMetadata = run.getParent().getProperty(JobMetadata.class);
        if (jobMetadata != null) {
            return jobMetadata;
        }
        JobMetadata newJobMetadata = new JobMetadata(run);
        try {
            run.getParent().addProperty(newJobMetadata);
        } catch (IOException e) {
            return null;
        }
        return newJobMetadata;
    }

    public static Span getStageSpan(FlowNode node) {
        return stageSpans.get(node);
    }

    public static Span getBuildSpan(Run<?, ?> run) {
        return buildSpans.get(run);
    }

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
        if (!(node instanceof StepStartNode)) {
            return false;
        }
        StepStartNode stepStartNode = (StepStartNode) node;
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

    public static String getGitHubProjectRepositoryUrl(Run<?, ?> run) {
        // Check GitHub Project Property (GitHub Project Plugin)
        Job<?, ?> job = run.getParent();
        GithubProjectProperty githubProjectProperty = job.getProperty(GithubProjectProperty.class);
        if (githubProjectProperty != null) {
            return githubProjectProperty.getProjectUrlStr();
        }
        return null;
    }

    public static String getSCMRepositoryUrl(Run<?, ?> run) {
        Job<?, ?> job = run.getParent();

        // Check if it's a multibranch pipeline with GitHub source
        if (job instanceof SCMSourceOwner) {
            SCMSourceOwner scmOwner = (SCMSourceOwner) job;
            for (SCMSource source : scmOwner.getSCMSources()) {
                if (source instanceof GitHubSCMSource) {
                    return ((GitHubSCMSource) source).getRemote();
                }
            }
        }

        // Check if it's a Freestyle job with Git SCM
        if (job instanceof hudson.model.AbstractProject) {
            SCM scm = ((hudson.model.AbstractProject<?, ?>) job).getScm();
            if (scm instanceof GitSCM) {
                GitSCM gitSCM = (GitSCM) scm;
                return gitSCM.getRepositories().get(0).getURIs().get(0).toString();
            }
        }

        return null; // No GitHub source found
    }

    static boolean isValidBuildStep(BuildStep step) {
        return step instanceof Builder // Normal build steps
                || Jenkins.get().getExtensionList(BuildStep.class).contains(step);
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
        LOGGER.info("build " + run.getDisplayName() + " started");
        startRootSpan(run);
    }

    // Pipeline and Freestyle Job listener
    public void onCompleted(Run<?, ?> run, @Nonnull TaskListener listener) {
        LOGGER.info("build " + run.getDisplayName() + " completed");
        endRootSpan(run);
    }

    private void startRootSpan(Run<?, ?> run) {
        if (run == null) {
            return;
        }
        Tracer tracer = TracerService.getTracer();
        Span span = tracer.spanBuilder(run.getDisplayName())
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        buildSpans.put(run, span);

        JobMetadata<?> jobSpanMetadata = getJobMetadata(run);
        if (jobSpanMetadata == null) {
            LOGGER.warning("Got start RootSpan  without job metadata");
            return;
        }
        jobSpanMetadata.addRepositoryURL("GitHubProjectProperty", getGitHubProjectRepositoryUrl(run));
        jobSpanMetadata.addRepositoryURL("SCMRemoteURL", getSCMRepositoryUrl(run));
    }

    private void endRootSpan(Run<?, ?> run) {
        Span span = buildSpans.remove(run);
        if (span == null) {
            LOGGER.warning("Got completed step without span");
            return;
        }
        JobMetadata<?> jobSpanMetadata = getJobMetadata(run);
        if (jobSpanMetadata == null) {
            LOGGER.warning("Got completed step no job metadata ");
            return;
        }
        jobSpanMetadata.setCommonSpanAttributes(span);
        TraceUtils.setSpanStatusFromResult(span, run);
        span.end();
    }

    private void startStageSpan(FlowNode node) {
        WorkflowRun run = getWorkflowRun(node);
        Span parentSpan = buildSpans.get(run);
        if (parentSpan == null) {
            LOGGER.warning("Got completed step without parent span");
            return;
        }
        Context parentContext = Context.current().with(parentSpan);
        Tracer tracer = TracerService.getTracer();
        SpanBuilder builder = tracer.spanBuilder(node.getDisplayName())
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(parentContext);
        Span span = builder.startSpan();
        stageSpans.put(node, span);

        StepStartNode stepStartNode = (StepStartNode) node;

        String taskName = "Stage(" + stepStartNode.getDisplayName() + ")";
        span.setAttribute(TraceUtils.CICD_PIPELINE_TASK_NAME, taskName);

        SpanContext spanContext = span.getSpanContext();
        run.addOrReplaceAction(new ParentSpanAction(spanContext.getTraceId(), spanContext.getSpanId()));

        LOGGER.info("Start stage: " + taskName);
    }

    private void endStageSpan(StepEndNode stepEndNode) {
        StepStartNode stepStartNode = stepEndNode.getStartNode();
        Span span = stageSpans.get(stepStartNode);
        if (span == null) {
            LOGGER.warning("Got completed stage without span");
            return;
        }
        WorkflowRun run = getWorkflowRun(stepStartNode);
        if (run == null) {
            LOGGER.warning("Got completed stage without RunSpanAction");
            return;
        }

        JobMetadata<?> jobSpanMetadata = getJobMetadata(run);
        if (jobSpanMetadata == null) {
            LOGGER.warning("Got completed stage no job metadata ");
            return;
        }

        jobSpanMetadata.setCommonSpanAttributes(span);

        ErrorAction error = stepEndNode.getError();
        if (error != null) {
            span.setStatus(StatusCode.ERROR);
        }
        span.setStatus(StatusCode.OK);
        span.end();

        String taskName = "Stage(" + stepStartNode.getDisplayName() + ")";
        LOGGER.info("Stop stage: " + taskName);
    }

    // Freestyle Job step Listener
    @Extension
    public static class BuildStepListener extends hudson.model.BuildStepListener {


        @Override
        public void started(AbstractBuild build, BuildStep step, BuildListener listener) {
            if (!isValidBuildStep(step)) {
                LOGGER.info("Step ignored " + step);
                return;
            }
            Span parentSpan = buildSpans.get(build);
            if (parentSpan == null) {
                LOGGER.warning("Got started step without parent span");
                return;
            }
            Tracer tracer = TracerService.getTracer();
            Span stepSpan = tracer.spanBuilder("Jenkins Step: " + step)
                    .setParent(io.opentelemetry.context.Context.current().with(parentSpan))
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();

            stepSpans.put(step, stepSpan);

            SpanContext spanContext = stepSpan.getSpanContext();
            build.addOrReplaceAction(new ParentSpanAction(spanContext.getTraceId(), spanContext.getSpanId()));
        }

        @Override
        public void finished(AbstractBuild build, BuildStep step, BuildListener listener, boolean canContinue) {
            if (!isValidBuildStep(step)) {
                LOGGER.info("Step ignored " + step);
                return;
            }
            Span span = stepSpans.get(step);
            if (span == null) {
                LOGGER.warning("Got completed step without span");
                return;
            }

            JobMetadata<?> jobSpanMetadata = getJobMetadata(build);
            if (jobSpanMetadata == null) {
                LOGGER.warning("Got completed stage no job metadata ");
                return;
            }
            jobSpanMetadata.setCommonSpanAttributes(span);

            span.setAttribute(
                    TraceUtils.CICD_PIPELINE_TASK_NAME, step.getClass().getSimpleName());

            if (canContinue) {
                span.setStatus(StatusCode.OK);
            } else {
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
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
                SCMRevisionState pollingBaseline) throws IOException, InterruptedException {

            JobMetadata<?> jobSpanMetadata = getJobMetadata(run);
            if (jobSpanMetadata == null) {
                LOGGER.warning("Got SCM checkout without job metadata");
                return;
            }

            EnvVars envVars = getEnvironment(run, listener);
            if (envVars != null) {
                LOGGER.info("Got SCM checkout data: " + envVars);
                jobSpanMetadata.setSCMCheckoutInfoFromEnvs(envVars);
            }
            if (scm instanceof GitSCM) {
                GitSCM gitSCM = (GitSCM) scm;
                GitClient client = gitSCM.createClient(listener, envVars, run, workspace);
                jobSpanMetadata.setSCMCheckoutInfoFromGitSCM(gitSCM, client);
            }

        }
    }
}
