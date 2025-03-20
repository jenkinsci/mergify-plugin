package io.jenkins.plugins.mergify;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Extension
public class TracerService {

    private static final Logger LOGGER = Logger.getLogger(TracerService.class.getName());
    private static final String SERVICE_NAME = "MergifyJenkinsPlugin";
    public static SpanExporterBackend SPAN_EXPORTER_BACKEND = SpanExporterBackend.MERGIFY;
    private static Tracer tracer;
    private static SpanExporter spanExporter;
    private static SdkTracerProvider sdkTracerProvider;

    public static Tracer getTracer() {
        return tracer;
    }

    public static InMemorySpanExporter getInMemorySpanExpoter() {
        if (spanExporter instanceof InMemorySpanExporter) {
            return (InMemorySpanExporter) spanExporter;
        }
        throw new RuntimeException("SpanExporter is not an instance of InMemorySpanExpoter");
    }

    public static void forceFlush() {
        CompletableResultCode completableResultCode = sdkTracerProvider.forceFlush();
        completableResultCode.join(1, TimeUnit.SECONDS);
    }

    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED, before = InitMilestone.JOB_LOADED)
    public void init() {
        LOGGER.info("Initializing Mergify Tracer");
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), SERVICE_NAME)));


        switch (SPAN_EXPORTER_BACKEND) {
            case MEMORY:
                spanExporter = InMemorySpanExporter.create();
                break;
            case LOG:
                spanExporter = OtlpJsonLoggingSpanExporter.create();
                break;
            case MERGIFY:
                spanExporter = new MergifySpanExporter();
                break;
        }

        sdkTracerProvider = SdkTracerProvider.builder().setResource(resource).addSpanProcessor(
                SimpleSpanProcessor.builder(spanExporter).build()
        ).build();

        OpenTelemetrySdk sdk =
                OpenTelemetrySdk.builder().setTracerProvider(sdkTracerProvider).build();

        Runtime.getRuntime().addShutdownHook(new Thread(sdkTracerProvider::close));
        tracer = sdk.getTracer(SERVICE_NAME);
        LOGGER.info("Mergify Tracer initialized");
    }

    public enum SpanExporterBackend {
        MERGIFY,
        LOG,
        MEMORY
    }
}
