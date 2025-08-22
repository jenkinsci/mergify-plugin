package io.jenkins.plugins.mergify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DashboardUrlBuilder {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String buildUrl(
            String login, String repository, String jobName, String pipelineName, String jobTraceId, String jobSpanId)
            throws Exception {

        // Build filters as JSON structure
        List<Map<String, Object>> filters = new ArrayList<>();

        filters.add(makeFilter("job_name", "equals", Collections.singletonList(jobName)));
        filters.add(makeFilter("pipeline_name", "equals", Collections.singletonList(pipelineName)));
        if (repository != null) {
            filters.add(makeFilter("repository", "equals", Collections.singletonList(repository)));
        }

        // Serialize JSON
        String json = mapper.writeValueAsString(filters);

        // Encode twice as dashboard decode it and then forward them to API
        String encoded = URLEncoder.encode(json, StandardCharsets.UTF_8);
        encoded = URLEncoder.encode(encoded, StandardCharsets.UTF_8);

        // Build final URL
        String url = String.format(
                "/ci-insights/jobs?filters=%s&job_trace_id=%s&job_span_id=%s",
                encoded,
                URLEncoder.encode(jobTraceId, StandardCharsets.UTF_8),
                URLEncoder.encode(jobSpanId, StandardCharsets.UTF_8));
        return String.format("%s&login=%s", url, URLEncoder.encode(login, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> makeFilter(String field, String operator, List<String> value) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("field", field);
        filter.put("operator", operator);
        filter.put("value", value);
        return filter;
    }
}
