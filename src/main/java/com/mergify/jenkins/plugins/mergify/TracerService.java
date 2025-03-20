package com.mergify.jenkins.plugins.mergify;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.logging.Logger;

@Extension
public class TracerService {
    private static final Logger LOGGER = Logger.getLogger(TracerService.class.getName());
    private static final String SERVICE_NAME = "MergifyJenkinsPlugin";
    private static Tracer tracer;

    @Initializer(after = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void init() {
        LOGGER.info("Initializing Mergify Tracer");
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), SERVICE_NAME)));

        SpanExporter spanExporterMergify = new MergifySpanExporter();
        SpanExporter spanExporterLog = OtlpJsonLoggingSpanExporter.create();

        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.builder(spanExporterLog).build())
                .addSpanProcessor(
                        SimpleSpanProcessor.builder(spanExporterMergify).build())
                .setResource(resource)
                .build();

        OpenTelemetrySdk sdk =
                OpenTelemetrySdk.builder().setTracerProvider(sdkTracerProvider).build();

        Runtime.getRuntime().addShutdownHook(new Thread(sdkTracerProvider::close));
        tracer = sdk.getTracer(SERVICE_NAME);
        LOGGER.info("Mergify Tracer initialized");
    }

    public static Tracer getTracer() {
        return tracer;
    }
}
