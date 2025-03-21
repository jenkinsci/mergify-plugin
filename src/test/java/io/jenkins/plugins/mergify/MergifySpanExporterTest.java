package io.jenkins.plugins.mergify;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Collection;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class MergifySpanExporterTest {
    MergifyConfigurationProvider mockConfig;
    OtlpHttpSpanExporter mockOtlpExporter;
    private MergifySpanExporter exporter;
    private SpanData mockSpanData;
    private ArgumentCaptor<Collection<SpanData>> exporterCaptor;

    @Before
    public void setUp() {
        mockSpanData = mock(SpanData.class);

        mockConfig = mock(MergifyConfigurationProvider.class);
        when(mockConfig.getUrl()).thenReturn("https://api.mergify.com");
        when(mockConfig.getApiKeyForOrg("org")).thenReturn("secret");

        exporter = spy(new MergifySpanExporter(mockConfig));
        exporterCaptor = ArgumentCaptor.forClass(Collection.class);

        mockOtlpExporter = mock(OtlpHttpSpanExporter.class);
        when(mockOtlpExporter.flush()).thenReturn(CompletableResultCode.ofSuccess());
        when(mockOtlpExporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());

        doReturn(mockOtlpExporter)
                .when(exporter)
                .createExporter("https://api.mergify.com/v1/repos/org/repo/ci/traces", "secret");
    }

    @Test
    public void testExport_Success() {
        Attributes attributes = Attributes.builder()
                .put(TraceUtils.VCS_REPOSITORY_NAME, "org/repo")
                .build();
        when(mockSpanData.getAttributes()).thenReturn(attributes);
        when(mockOtlpExporter.export(exporterCaptor.capture())).thenReturn(CompletableResultCode.ofSuccess());

        Collection<SpanData> spans = List.of(mockSpanData);
        CompletableResultCode result = exporter.export(spans);

        Collection captured = exporterCaptor.getValue();
        assertNotNull(captured);
        assertEquals(1, captured.size());
        assertTrue(captured.contains(mockSpanData));
        assertTrue(result.isSuccess());
    }

    @Test
    public void testExport_NoToken() {
        Attributes attributes = Attributes.builder()
                .put(TraceUtils.VCS_REPOSITORY_NAME, "unknown/repo")
                .build();
        when(mockSpanData.getAttributes()).thenReturn(attributes);

        Collection<SpanData> spans = List.of(mockSpanData);
        CompletableResultCode result = exporter.export(spans);

        verify(mockOtlpExporter, never()).export(any());
        assertTrue(result.isSuccess());
    }

    @Test
    public void testExport_ExceptionHandling() {
        Attributes attributes = Attributes.builder()
                .put(TraceUtils.VCS_REPOSITORY_NAME, "org/repo")
                .build();
        when(mockSpanData.getAttributes()).thenReturn(attributes);
        when(mockOtlpExporter.export(exporterCaptor.capture())).thenThrow(new RuntimeException("Test exception"));

        exporter.export(List.of(mockSpanData));
        assertNotNull(exporter.flush());
        assertNotNull(exporter.shutdown());

        Collection<SpanData> spans = List.of(mockSpanData);
        CompletableResultCode result = exporter.export(spans);

        Collection captured = exporterCaptor.getValue();
        assertNotNull(captured);
        assertEquals(1, captured.size());
        assertTrue(captured.contains(mockSpanData));

        assertFalse(result.isSuccess());
    }

    @Test
    public void testFlush_Success() {
        CompletableResultCode result = exporter.flush();
        assertTrue(result.isSuccess());
    }

    @Test
    public void testShutdown_Success() {
        CompletableResultCode result = exporter.shutdown();
        assertTrue(result.isSuccess());
    }

    @Test
    public void testToString() {
        String output = exporter.toString();
        assertNotNull(output);
        assertTrue(output.contains("MergifySpanExporter"));
    }
}
