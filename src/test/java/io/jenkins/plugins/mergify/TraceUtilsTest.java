package io.jenkins.plugins.mergify;

import static org.mockito.Mockito.*;

import hudson.model.Result;
import hudson.model.Run;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.Test;

public class TraceUtilsTest {

    @Test
    public void testSetSpanStatusFromResult_Success() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.SUCCESS);

        TraceUtils.setSpanStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.OK);
        verify(span).setAttribute("cicd.pipeline.result", "success");
    }

    @Test
    public void testSetSpanStatusFromResult_NotBuilt() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.NOT_BUILT);

        TraceUtils.setSpanStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.OK);
        verify(span).setAttribute("cicd.pipeline.result", "skipped");
    }

    @Test
    public void testSetSpanStatusFromResult_Failure() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.FAILURE);

        TraceUtils.setSpanStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute("cicd.pipeline.result", "failure");
    }

    @Test
    public void testSetSpanStatusFromResult_Aborted() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.ABORTED);

        TraceUtils.setSpanStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute("cicd.pipeline.result", "failure");
    }

    @Test
    public void testSetSpanStatusFromResult_Unstable() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.UNSTABLE);

        TraceUtils.setSpanStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute("cicd.pipeline.result", "failure");
    }

    @Test
    public void testSetSpanStatusFromResult_Unknown() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(null);

        TraceUtils.setSpanStatusFromResult(span, run);

        verify(span).setAttribute("cicd.pipeline.result", "unknown");
    }
}
