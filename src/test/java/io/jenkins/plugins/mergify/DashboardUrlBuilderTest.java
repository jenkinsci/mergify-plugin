package io.jenkins.plugins.mergify;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DashboardUrlBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testBuildUrl_AllParameters() throws Exception {
        String login = "testorg";
        String repository = "test-repo";
        String jobName = "test-job";
        String pipelineName = "test-pipeline";
        String jobTraceId = "trace123";
        String jobSpanId = "span456";

        String result = DashboardUrlBuilder.buildUrl(login, repository, jobName, pipelineName, jobTraceId, jobSpanId);

        assertNotNull(result);
        assertTrue(result.startsWith("/ci-insights/jobs?filters="));
        assertTrue(result.contains("&job_trace_id=dHJhY2UxMjM"));
        assertTrue(result.contains("&job_span_id=c3BhbjQ1Ng"));
        assertTrue(result.contains("&login=testorg"));
    }

    @Test
    void testBuildUrl_NullRepository() throws Exception {
        String login = "testorg";
        String repository = null;
        String jobName = "test-job";
        String pipelineName = "test-pipeline";
        String jobTraceId = "trace123";
        String jobSpanId = "span456";

        String result = DashboardUrlBuilder.buildUrl(login, repository, jobName, pipelineName, jobTraceId, jobSpanId);

        assertNotNull(result);
        assertTrue(result.startsWith("/ci-insights/jobs?filters="));
        assertTrue(result.contains("&job_trace_id=dHJhY2UxMjM"));
        assertTrue(result.contains("&job_span_id=c3BhbjQ1Ng"));
        assertTrue(result.contains("&login=testorg"));

        // Verify that repository filter is not included when null
        String encodedFilters = extractFiltersFromUrl(result);
        String decodedFilters =
                URLDecoder.decode(URLDecoder.decode(encodedFilters, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        List<Map<String, Object>> filters = MAPPER.readValue(decodedFilters, new TypeReference<>() {});

        assertEquals(2, filters.size()); // Only job_name and pipeline_name filters
        assertTrue(filters.stream().anyMatch(f -> "job_name".equals(f.get("field"))));
        assertTrue(filters.stream().anyMatch(f -> "pipeline_name".equals(f.get("field"))));
        assertFalse(filters.stream().anyMatch(f -> "repository".equals(f.get("field"))));
    }

    @Test
    void testBuildUrl_SpecialCharacters() throws Exception {
        String login = "test@org+space";
        String repository = "test-repo/with-slash";
        String jobName = "test job with spaces";
        String pipelineName = "pipeline&name=special";
        String jobTraceId = "trace+123&test";
        String jobSpanId = "span/456?test";

        String result = DashboardUrlBuilder.buildUrl(login, repository, jobName, pipelineName, jobTraceId, jobSpanId);

        assertNotNull(result);
        assertTrue(result.startsWith("/ci-insights/jobs?filters="));

        // Verify URL encoding works correctly
        assertTrue(result.contains("&login=test%40org%2Bspace"));
        assertTrue(result.contains("&job_trace_id=dHJhY2UrMTIzJnRlc3Q"));
        assertTrue(result.contains("&job_span_id=c3Bhbi80NTY_dGVzdA"));
    }

    @Test
    void testBuildUrl_EmptyStrings() throws Exception {
        String login = "";
        String repository = "";
        String jobName = "";
        String pipelineName = "";
        String jobTraceId = "";
        String jobSpanId = "";

        String result = DashboardUrlBuilder.buildUrl(login, repository, jobName, pipelineName, jobTraceId, jobSpanId);

        assertNotNull(result);
        assertTrue(result.startsWith("/ci-insights/jobs?filters="));
        assertTrue(result.contains("&job_trace_id="));
        assertTrue(result.contains("&job_span_id="));
        assertTrue(result.contains("&login="));
    }

    @Test
    void testFilterGeneration() throws Exception {
        String login = "testorg";
        String repository = "test-repo";
        String jobName = "test-job";
        String pipelineName = "test-pipeline";
        String jobTraceId = "trace123";
        String jobSpanId = "span456";

        String result = DashboardUrlBuilder.buildUrl(login, repository, jobName, pipelineName, jobTraceId, jobSpanId);

        // Extract and decode the filters
        String encodedFilters = extractFiltersFromUrl(result);
        String decodedFilters =
                URLDecoder.decode(URLDecoder.decode(encodedFilters, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        List<Map<String, Object>> filters = MAPPER.readValue(decodedFilters, new TypeReference<>() {});

        // Verify we have exactly 3 filters
        assertEquals(3, filters.size());

        // Verify job_name filter
        Map<String, Object> jobNameFilter = filters.stream()
                .filter(f -> "job_name".equals(f.get("field")))
                .findFirst()
                .orElse(null);
        assertNotNull(jobNameFilter);
        assertEquals("equals", jobNameFilter.get("operator"));
        assertEquals(List.of("test-job"), jobNameFilter.get("value"));

        // Verify pipeline_name filter
        Map<String, Object> pipelineNameFilter = filters.stream()
                .filter(f -> "pipeline_name".equals(f.get("field")))
                .findFirst()
                .orElse(null);
        assertNotNull(pipelineNameFilter);
        assertEquals("equals", pipelineNameFilter.get("operator"));
        assertEquals(List.of("test-pipeline"), pipelineNameFilter.get("value"));

        // Verify repository filter
        Map<String, Object> repositoryFilter = filters.stream()
                .filter(f -> "repository".equals(f.get("field")))
                .findFirst()
                .orElse(null);
        assertNotNull(repositoryFilter);
        assertEquals("equals", repositoryFilter.get("operator"));
        assertEquals(List.of("test-repo"), repositoryFilter.get("value"));
    }

    @Test
    void testDoubleUrlEncoding() throws Exception {
        String login = "testorg";
        String repository = "test-repo";
        String jobName = "test job";
        String pipelineName = "test pipeline";
        String jobTraceId = "trace123";
        String jobSpanId = "span456";

        String result = DashboardUrlBuilder.buildUrl(login, repository, jobName, pipelineName, jobTraceId, jobSpanId);

        // Extract the filters parameter
        String encodedFilters = extractFiltersFromUrl(result);

        // Verify it's double-encoded by decoding twice
        String singleDecoded = URLDecoder.decode(encodedFilters, StandardCharsets.UTF_8);
        String doubleDecoded = URLDecoder.decode(singleDecoded, StandardCharsets.UTF_8);

        // Should be able to parse as JSON after double decoding
        List<Map<String, Object>> filters = MAPPER.readValue(doubleDecoded, new TypeReference<>() {});
        assertNotNull(filters);
        assertEquals(3, filters.size());
    }

    @Test
    void testUrlStructure() throws Exception {
        String login = "testorg";
        String repository = "test-repo";
        String jobName = "test-job";
        String pipelineName = "test-pipeline";
        String jobTraceId = "trace123";
        String jobSpanId = "span456";

        String result = DashboardUrlBuilder.buildUrl(login, repository, jobName, pipelineName, jobTraceId, jobSpanId);

        // Verify URL starts with expected path
        assertTrue(result.startsWith("/ci-insights/jobs?"));

        // Verify all expected query parameters are present
        assertTrue(result.contains("filters="));
        assertTrue(result.contains("job_trace_id="));
        assertTrue(result.contains("job_span_id="));
        assertTrue(result.contains("login="));

        // Verify parameter order (login should be last)
        int filtersIndex = result.indexOf("filters=");
        int traceIdIndex = result.indexOf("job_trace_id=");
        int spanIdIndex = result.indexOf("job_span_id=");
        int loginIndex = result.indexOf("login=");

        assertTrue(filtersIndex < traceIdIndex);
        assertTrue(traceIdIndex < spanIdIndex);
        assertTrue(spanIdIndex < loginIndex);
    }

    private String extractFiltersFromUrl(String url) {
        String filtersParam = "filters=";
        int startIndex = url.indexOf(filtersParam) + filtersParam.length();
        int endIndex = url.indexOf("&", startIndex);
        if (endIndex == -1) {
            endIndex = url.length();
        }
        return url.substring(startIndex, endIndex);
    }
}
