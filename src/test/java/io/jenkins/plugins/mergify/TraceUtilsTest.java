package io.jenkins.plugins.mergify;

import hudson.model.Result;
import hudson.model.Run;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.Test;

import static org.mockito.Mockito.*;

public class TraceUtilsTest {

    @Test
    public void testSetSpanJobStatusFromResult_Success() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.SUCCESS);

        TraceUtils.setSpanJobStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.OK);
        verify(span).setAttribute("cicd.pipeline.result", "success");
    }

    @Test
    public void testSetSpanJobStatusFromResult_NotBuilt() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.NOT_BUILT);

        TraceUtils.setSpanJobStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.OK);
        verify(span).setAttribute("cicd.pipeline.result", "skipped");
    }

    @Test
    public void testSetSpanJobStatusFromResult_Failure() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.FAILURE);

        TraceUtils.setSpanJobStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute("cicd.pipeline.result", "failure");
    }

    @Test
    public void testSetSpanJobStatusFromResult_Aborted() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.ABORTED);

        TraceUtils.setSpanJobStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute("cicd.pipeline.result", "failure");
    }

    @Test
    public void testSetSpanJobStatusFromResult_Unstable() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(Result.UNSTABLE);

        TraceUtils.setSpanJobStatusFromResult(span, run);

        verify(span).setStatus(StatusCode.ERROR);
        verify(span).setAttribute("cicd.pipeline.result", "failure");
    }

    @Test
    public void testSetSpanJobStatusFromResult_Unknown() {
        Span span = mock(Span.class);
        Run<?, ?> run = mock(Run.class);
        when(run.getResult()).thenReturn(null);

        TraceUtils.setSpanJobStatusFromResult(span, run);

        verify(span).setAttribute("cicd.pipeline.result", "unknown");
    }
}
