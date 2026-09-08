package com.scanops.scan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelEnsembleContractTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void preservesTwoFindingsAndDoesNotReuseFirstRemediation() throws Exception {
        var result = mapper.readValue("""
                {"language":"Java","file_path":"Demo.java","detected":true,
                 "vulnerability":"CWE-78","line":8,"severity":"UNKNOWN",
                 "fix":"command-specific fix","status":"DONE",
                 "findings":[
                   {"cwe":"CWE-78","line":8,"source":"cpg","evidence_level":"static-rule"},
                   {"cwe":"CWE-798","line":12,"source":"qwen-semantic",
                    "evidence_level":"semantic-review","reason":"Embedded password",
                    "accepted":true,"confidence":"high"}],
                 "analysis_details":{"policy":"java-semantic-union-v1"}}
                """, ScanopsModelClient.AnalyzeResult.class);
        var rows = result.individualResults();
        assertEquals(2, rows.size());
        assertEquals("command-specific fix", rows.get(0).fix());
        assertEquals("CWE-798", rows.get(1).vulnerability());
        assertEquals(12, rows.get(1).line());
        assertTrue(rows.get(1).reason().contains("CPG 경로 증명 아님"));
        assertFalse(rows.get(1).fix().contains("command-specific"));
        assertTrue(rows.get(1).ai_prompt().contains("CWE-798"));
    }

    @Test
    void acceptsLegacyResponseWithoutFindings() throws Exception {
        var result = mapper.readValue("""
                {"language":"Java","file_path":"Demo.java","detected":false,
                 "vulnerability":"NONE","status":"DONE"}
                """, ScanopsModelClient.AnalyzeResult.class);
        assertEquals(1, result.individualResults().size());
        assertSame(result, result.individualResults().get(0));
    }

    @Test
    void expansionDoesNotLosePartialStatus() throws Exception {
        var result = mapper.readValue("""
                {"detected":true,"status":"PARTIAL","findings":[
                  {"cwe":"CWE-798","line":1,"source":"qwen-semantic"}]}
                """, ScanopsModelClient.AnalyzeResult.class);
        assertEquals("PARTIAL", result.individualResults().get(0).status());
    }
}
