package io.jenkins.plugins.mergify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MergifySpanExporterTest {

    private MergifyConfigurationProvider mockConfig;
    private OtlpHttpSpanExporter mockOtlpExporter;
    private MergifySpanExporter exporter;
    private SpanData mockSpanData;
    private ArgumentCaptor<Collection<SpanData>> exporterCaptor;

    @BeforeEach
    void beforeEach() {
        mockSpanData = mock(SpanData.class);

        mockConfig = mock(MergifyConfigurationProvider.class);
        when(mockConfig.getUrl()).thenReturn("https://api.mergify.com");
        when(mockConfig.getApiKeyForOrg("org")).thenReturn("secret");

        exporter = spy(new MergifySpanExporter(mockConfig));
        doReturn(false).when(exporter).shouldLogSpan();
        exporterCaptor = ArgumentCaptor.forClass(Collection.class);

        mockOtlpExporter = mock(OtlpHttpSpanExporter.class);
        when(mockOtlpExporter.flush()).thenReturn(CompletableResultCode.ofSuccess());
        when(mockOtlpExporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());

        doReturn(mockOtlpExporter)
                .when(exporter)
                .createExporter("https://api.mergify.com/v1/repos/org/repo/ci/traces", "secret");
    }

    @Test
    void testExport_Success() {
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
    void testExport_NoToken() {
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
    void testExport_ExceptionHandling() {
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
    void testFlush_Success() {
        CompletableResultCode result = exporter.flush();
        assertTrue(result.isSuccess());
    }

    @Test
    void testShutdown_Success() {
        CompletableResultCode result = exporter.shutdown();
        assertTrue(result.isSuccess());
    }

    @Test
    void testToString() {
        String output = exporter.toString();
        assertNotNull(output);
        assertTrue(output.contains("MergifySpanExporter"));
    }
}
