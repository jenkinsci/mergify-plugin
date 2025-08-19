package io.jenkins.plugins.mergify;

import static org.junit.Assert.assertEquals;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.Test;

public class TraceparentActionTest {

    @Test
    public void testParentSpanAction() {
        TraceState traceState = TraceState.builder().build();
        SpanContext spanContext = SpanContext.create(
                "80e1afed08e019fc1110464cfa66635c", "7a085853722dc6d2", TraceFlags.getDefault(), traceState);

        TraceparentAction action = new TraceparentAction(spanContext);

        assertEquals("00-80e1afed08e019fc1110464cfa66635c-7a085853722dc6d2-01", action.getTraceParent());
    }
}
