package io.jenkins.plugins.mergify;

import static org.mockito.Mockito.*;

import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class TraceUtilsTest {

    @Test
    public void testEndJobSpan_Success() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        Job<?, ?> job = mock(Job.class);
        when(run.getResult()).thenReturn(Result.SUCCESS);
        when(run.getParent()).thenReturn((Job) job);
        when(run.getAction(JobMetadata.class)).thenReturn(null);

        TraceUtils.endJobSpan(span, run);

        verify(span).setStatus(StatusCode.OK);
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RESULT, "success");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_TASK_RUN_RESULT, "success");
        verify(span).end();
    }

    @Test
    public void testEndJobSpan_NotBuilt() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        Job<?, ?> job = mock(Job.class);
        when(run.getResult()).thenReturn(Result.NOT_BUILT);
        when(run.getParent()).thenReturn((Job) job);
        when(run.getAction(JobMetadata.class)).thenReturn(null);

        TraceUtils.endJobSpan(span, run);

        verify(span).setStatus(StatusCode.OK);
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RESULT, "skipped");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_TASK_RUN_RESULT, "skipped");
        verify(span).end();
    }

    @Test
    public void testEndJobSpan_Failure() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        Job<?, ?> job = mock(Job.class);
        when(run.getResult()).thenReturn(Result.FAILURE);
        when(run.getParent()).thenReturn((Job) job);
        when(run.getAction(JobMetadata.class)).thenReturn(null);

        TraceUtils.endJobSpan(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RESULT, "failure");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_TASK_RUN_RESULT, "failure");
        verify(span).end();
    }

    @Test
    public void testEndJobSpan_Aborted() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        Job<?, ?> job = mock(Job.class);
        when(run.getResult()).thenReturn(Result.ABORTED);
        when(run.getParent()).thenReturn((Job) job);
        when(run.getAction(JobMetadata.class)).thenReturn(null);

        TraceUtils.endJobSpan(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RESULT, "cancelled");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_TASK_RUN_RESULT, "cancelled");
        verify(span).end();
    }

    @Test
    public void testEndJobSpan_Unstable() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        Job<?, ?> job = mock(Job.class);
        when(run.getResult()).thenReturn(Result.UNSTABLE);
        when(run.getParent()).thenReturn((Job) job);
        when(run.getAction(JobMetadata.class)).thenReturn(null);

        TraceUtils.endJobSpan(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RESULT, "failure");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_TASK_RUN_RESULT, "failure");
    }

    @Test
    public void testEndJobSpan_Unknown() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        Job<?, ?> job = mock(Job.class);
        when(run.getResult()).thenReturn(null);
        when(run.getParent()).thenReturn((Job) job);
        when(run.getAction(JobMetadata.class)).thenReturn(null);

        TraceUtils.endJobSpan(span, run);

        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_RESULT, "unknown");
        verify(span).setAttribute(TraceUtils.CICD_PIPELINE_TASK_RUN_RESULT, "unknown");
        verify(span).end();
    }
}
