package io.jenkins.plugins.mergify;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ParentSpanActionTest {

    @Test
    public void testParentSpanAction() {
        String traceId = "trace-123";
        String spanId = "span-456";

        ParentSpanAction action = new ParentSpanAction(traceId, spanId);

        assertEquals("trace-123", action.getTraceId());
        assertEquals("span-456", action.getSpanId());
    }
}
