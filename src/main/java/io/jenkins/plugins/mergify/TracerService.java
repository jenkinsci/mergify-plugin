package io.jenkins.plugins.mergify;

import hudson.Extension;
import hudson.PluginWrapper;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.VersionNumber;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

@Extension
public class TracerService {

    private static final Logger LOGGER = Logger.getLogger(TracerService.class.getName());

    private static final String SERVICE_NAME = "MergifyJenkinsPlugin";

    private static MergifySpanExporter spanExporter;

    private static Tracer tracer;
    private static SdkTracerProvider sdkTracerProvider;

    public static Tracer getTracer() {
        return tracer;
    }

    public static void clearMergifySpanExporters() {
        spanExporter.clearSpanExporters();
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

        spanExporter = new MergifySpanExporter(MergifyConfiguration.get());

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
        LOGGER.info("Mergify Tracer initialized (\" + version + \")");
    }
}
