package io.jenkins.plugins.mergify;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.PluginWrapper;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.VersionNumber;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

@Extension
public class TracerService {

    private static final Logger LOGGER = Logger.getLogger(TracerService.class.getName());
    private static final String SERVICE_NAME = "MergifyJenkinsPlugin";

    @SuppressFBWarnings(
            value = "MS_SHOULD_BE_FINAL",
            justification = "Intentional non-final static for runtime override/testing")
    public static SpanExporterBackend SPAN_EXPORTER_BACKEND = SpanExporterBackend.MERGIFY;

    private static Tracer tracer;
    private static SpanExporter spanExporter;
    private static SdkTracerProvider sdkTracerProvider;

    public static Tracer getTracer() {
        return tracer;
    }

    public static InMemorySpanExporter getInMemorySpanExporter() {
        if (spanExporter instanceof InMemorySpanExporter) {
            return (InMemorySpanExporter) spanExporter;
        }
        throw new RuntimeException("SpanExporter is not an instance of InMemorySpanExporter");
    }

    public static void forceFlush() {
        CompletableResultCode completableResultCode = sdkTracerProvider.forceFlush();
        completableResultCode.join(1, TimeUnit.SECONDS);
    }

    @Initializer(after = InitMilestone.SYSTEM_CONFIG_ADAPTED, before = InitMilestone.JOB_LOADED)
    public static void init() {

        PluginWrapper plugin = Jenkins.get().getPluginManager().getPlugin("mergify");
        String version = (plugin != null && plugin.getVersion() != null) ? plugin.getVersion() : "unknown";
        VersionNumber jenkinsVersionNumber = Jenkins.getVersion();
        String jenkinsVersion = jenkinsVersionNumber != null ? jenkinsVersionNumber.toString() : "unknown";

        LOGGER.info("Initializing Mergify Tracer (" + version + ")");
        Resource jenkinsResource = Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"),
                SERVICE_NAME,
                AttributeKey.stringKey("service.version"),
                version,
                AttributeKey.stringKey("service.jenkins_version"),
                jenkinsVersion,
                TraceUtils.CICD_PROVIDER_NAME,
                "jenkins"));
        Resource resource = Resource.getDefault().merge(jenkinsResource);

        switch (SPAN_EXPORTER_BACKEND) {
            case MEMORY:
                spanExporter = InMemorySpanExporter.create();
                break;
            case LOG:
                spanExporter = OtlpJsonLoggingSpanExporter.create();
                break;
            case MERGIFY:
                spanExporter = new MergifySpanExporter(MergifyConfiguration.get());
                break;
        }
        BatchSpanProcessor spanProcessor = BatchSpanProcessor.builder(spanExporter)
                .setExporterTimeout(Duration.ofSeconds(60))
                .setMaxExportBatchSize(10000)
                .setExportUnsampledSpans(true)
                .build();
        sdkTracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(spanProcessor)
                .build();

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
