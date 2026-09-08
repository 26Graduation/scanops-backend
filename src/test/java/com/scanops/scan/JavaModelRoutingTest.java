package com.scanops.scan;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JavaModelRoutingTest {
    private final List<String> calls = new ArrayList<>();
    private ScanopsModelClient client(String javaResponse, String legacyResponse) {
        var c = new ScanopsModelClient(WebClient.builder().exchangeFunction(req -> {
            boolean javaRoute = req.url().getHost().equals("java.invalid");
            calls.add(req.url() + " key=" + req.headers().getFirst("X-API-Key"));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body(javaRoute ? javaResponse : legacyResponse).build());
        }));
        ReflectionTestUtils.setField(c, "modelUrl", "http://legacy.invalid");
        ReflectionTestUtils.setField(c, "apiKey", "legacy-test");
        ReflectionTestUtils.setField(c, "javaModelUrl", "http://java.invalid");
        ReflectionTestUtils.setField(c, "javaApiKey", "java-test");
        return c;
    }
    private String batch(String path, String status) {
        return "{\"total\":1,\"results\":[{\"file_path\":\"" + path + "\",\"status\":\"" + status + "\"}]}";
    }
    @Test void partitionsMixedBatchAndPreservesInputOrderAndDistinctKeys() {
        var c = client(batch("A.java", "DONE"), batch("b.py", "DONE"));
        var r = c.analyzeBatch(List.of(new ScanopsModelClient.AnalyzeRequest("Java", "class A {}", "A.java", true),
                new ScanopsModelClient.AnalyzeRequest("Python", "pass", "b.py", true)));
        assertEquals(List.of("A.java", "b.py"), r.results().stream().map(ScanopsModelClient.AnalyzeResult::file_path).toList());
        assertTrue(calls.contains("http://java.invalid/analyze/batch key=java-test"));
        assertTrue(calls.contains("http://legacy.invalid/analyze/batch key=legacy-test"));
    }
    @Test void routesRepositoryJavaLabelToJavaEngine() {
        var c = client(batch("A.java", "DONE"), batch("A.java", "DONE"));
        String repositoryLabel = GithubScanService.EXT_TO_LANG.get(".java");
        assertEquals("Java Spring Boot", repositoryLabel);
        assertTrue(c.routesToJava(repositoryLabel));
        assertFalse(c.routesToJava("JavaScript"));
    }
    @Test void failurePartialAndMissingResponsesCannotBecomeCleanScan() {
        for (String body : List.of(batch("A.java", "FAILED"), batch("A.java", "PARTIAL"), batch("other.java", "DONE"), "{\"total\":0,\"results\":[]}")) {
            var c = client(body, batch("b.py", "DONE"));
            assertEquals(0, c.analyzeBatch(List.of(new ScanopsModelClient.AnalyzeRequest("Java", "class A {}", "A.java", true))).total());
        }
    }
    @Test void blankJavaUrlKeepsLegacyRouting() {
        var c = client("{}", "{\"file_path\":\"A.java\"}");
        ReflectionTestUtils.setField(c, "javaModelUrl", "");
        assertNotNull(c.analyze("Java", "class A {}", "A.java"));
        assertEquals(List.of("http://legacy.invalid/analyze key=legacy-test"), calls);
    }
    @Test void javaKeyIsMandatoryAndFailedSingleDoesNotReturnClean() {
        var c = client("{\"file_path\":\"A.java\",\"status\":\"FAILED\"}", "{}");
        assertNull(c.analyze("Java", "class A {}", "A.java"));
        calls.clear();
        ReflectionTestUtils.setField(c, "javaApiKey", "");
        assertNull(c.analyze("Java", "class A {}", "A.java"));
        assertTrue(calls.isEmpty());
    }
    @Test void mixedPrPreservesLegacyEndpointAndJavaMultipleFindingsAndLines() {
        var c = client("""
                {"total":1,"results":[{"file_path":"A.java","status":"DONE","detected":true,
                "vulnerability":"CWE-78","line":10,"attack":"attack","fix":"fix",
                "findings":[{"cwe":"CWE-78","line":10,"source":"cpg"},
                {"cwe":"CWE-798","line":90,"source":"qwen-semantic","reason":"hardcoded"}]}]}
                """, "{\"total_files\":1,\"findings\":[]}");
        var r = c.analyzePr("example/repo", 1, List.of(Map.of("filename", "A.java", "content", "class A {}"),
                Map.of("filename", "b.py", "content", "pass")));
        assertEquals(2, r.path("findings").size());
        assertEquals(90, r.path("findings").get(1).path("diff_line").asInt());
        assertEquals("qwen-semantic", r.path("findings").get(1).path("source").asText());
        assertEquals("fix", r.path("findings").get(0).path("fix").asText());
        assertTrue(calls.contains("http://legacy.invalid/analyze/pr key=legacy-test"));
        assertTrue(calls.contains("http://java.invalid/analyze/batch key=java-test"));
    }
    @Test void transportFailureAndTimeoutAreFailures() {
        for (var response : List.<Mono<ClientResponse>>of(Mono.error(new IllegalStateException("offline")), Mono.never())) {
            var c = new ScanopsModelClient(WebClient.builder().exchangeFunction(req -> response));
            ReflectionTestUtils.setField(c, "javaModelUrl", "http://java.invalid");
            ReflectionTestUtils.setField(c, "javaApiKey", "java-test");
            ReflectionTestUtils.setField(c, "javaTimeoutSeconds", 1L);
            assertNull(c.analyze("Java", "class A {}", "A.java"));
        }
    }

}
