package com.mergify.jenkins.plugins.mergify;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

final class MergifySpanExporter implements SpanExporter {
    private static final Logger LOGGER = Logger.getLogger(MergifySpanExporter.class.getName());
    private final Map<String, OtlpHttpSpanExporter> spanExporters = new ConcurrentHashMap<>();

    public MergifySpanExporter() {}

    private static Map<String, List<SpanData>> groupByRepositoryName(Collection<SpanData> collection) {
        return collection.stream()
                .filter(span -> span.getAttributes().get(TraceUtils.VCS_REPOSITORY_NAME) != null)
                .collect(Collectors.groupingBy(
                        span -> span.getAttributes().get(TraceUtils.VCS_REPOSITORY_NAME), Collectors.toList()));
    }

    private OtlpHttpSpanExporter getSpanExporter(String repositoryName) {
        OtlpHttpSpanExporter exporter = spanExporters.get(repositoryName);
        if (exporter != null) {
            return exporter;
        }
        MergifyConfiguration config = MergifyConfiguration.get();

        if (config == null) {
            return null;
        }

        String url = config.getUrl();
        String token = config.getApiKeyForOrg(repositoryName.split("/")[0]);
        if (token == null) {
            LOGGER.warning("No token found for repository: " + repositoryName);
            return null;
        }

        OtlpHttpSpanExporter newExporter = OtlpHttpSpanExporter.builder()
                .addHeader("Authorization", "Bearer " + token)
                .setEndpoint(url + "/v1/repos/" + repositoryName + "/ci/traces")
                .build();
        spanExporters.put(repositoryName, newExporter);
        return newExporter;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> collection) {

        Map<String, List<SpanData>> groupedByRepositoryName = groupByRepositoryName(collection);
        LOGGER.info(
                "Exporting " + collection.size() + " spans across " + groupedByRepositoryName.size() + " repositories");

        List<CompletableResultCode> results = new ArrayList<>(groupedByRepositoryName.size());

        groupedByRepositoryName.forEach((repositoryName, spans) -> {
            LOGGER.info("Exporting " + spans.size() + " spans to repository `" + repositoryName + "`");

            CompletableResultCode exportResult;
            SpanExporter exporter = getSpanExporter(repositoryName);
            try {
                exportResult = exporter.export(spans);
                exporter.flush();
                exporter.close();
                exporter.shutdown();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Exception thrown by the export.", e);
                results.add(CompletableResultCode.ofFailure());
                return;
            }
            results.add(exportResult);
        });

        return CompletableResultCode.ofAll(results);
    }

    public CompletableResultCode flush() {
        List<CompletableResultCode> results = new ArrayList<>(this.spanExporters.size());

        for (SpanExporter spanExporter : this.spanExporters.values()) {
            CompletableResultCode flushResult;
            try {
                flushResult = spanExporter.flush();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Exception thrown by the flush.", e);
                results.add(CompletableResultCode.ofFailure());
                continue;
            }

            results.add(flushResult);
        }

        return CompletableResultCode.ofAll(results);
    }

    public CompletableResultCode shutdown() {
        List<CompletableResultCode> results = new ArrayList<>(spanExporters.size());

        for (SpanExporter spanExporter : spanExporters.values()) {
            CompletableResultCode shutdownResult;
            try {
                shutdownResult = spanExporter.shutdown();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Exception thrown by the shutdown.", e);
                results.add(CompletableResultCode.ofFailure());
                continue;
            }

            results.add(shutdownResult);
        }

        return CompletableResultCode.ofAll(results);
    }

    @Override
    public void close() {
        SpanExporter.super.close();
    }

    public String toString() {
        return "MergifySpanExporter{spanExporters=" + this.spanExporters + '}';
    }
}
