package com.scanops.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CpgGraphTest {
    private Map<String, Object> step(String role, String file, int line) {
        return Map.of("role", role, "file", file, "line", line, "code", role + "()");
    }
    @Test void preservesOrderedCrossFileFlowAndScanSnapshot() throws Exception {
        var json = new ObjectMapper().readTree(CpgGraph.from("cpg",
                List.of(step("source", "A.java", 1), step("sink", "B.java", 2)), "B.java",
                Map.of("A.java", "input()", "B.java", "line1\nsink()\nline3")));
        assertEquals("data-flow", json.get("kind").asText());
        assertEquals("n1", json.get("edges").get(0).get("target").asText());
        assertEquals("B.java", json.get("nodes").get(1).get("file").asText());
        assertEquals(2, json.get("nodes").get(1).get("excerpt").get("targetLine").asInt());
    }
    @Test void rejectsSemanticAndMalformedPathsInsteadOfFabricatingEdges() {
        var path = List.of(step("source", "A.java", 1), step("sink", "A.java", 2));
        assertNull(CpgGraph.from("qwen-semantic", path, "A.java", Map.of()));
        assertNull(CpgGraph.from("cpg+qwen3.8-max", path, "A.java", Map.of()));
        assertNull(CpgGraph.from("cpg", List.of(step("evidence", "A.java", 1)), "A.java", Map.of()));
        assertNull(CpgGraph.from("cpg", List.of(), "A.java", Map.of()));
    }
    @Test void distinguishesCallSiteAndTruncatedFlow() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals("call-site", mapper.readTree(CpgGraph.from("cpg", List.of(step("sink", "A.java", 1)), "A.java", Map.of())).get("kind").asText());
        assertEquals("partial-flow", mapper.readTree(CpgGraph.from("cpg", List.of(step("source", "A.java", 1), step("intermediate", "A.java", 2)), "A.java", Map.of())).get("kind").asText());
    }
    @Test void preservesActualTypedEdgesAndRejectsBrokenGraph() throws Exception {
        var mapper = new ObjectMapper();
        Map<String, Object> graph = mapper.readValue("""
          {"version":2,"kind":"cpg","scope":"finding-neighborhood","truncated":false,
           "nodes":[
             {"id":"100","role":"source","label":"CALL","file":"A.java","line":1,"code":"input()","onPath":true},
             {"id":"200","role":"sink","label":"CALL","file":"B.java","line":2,"code":"print(value)","onPath":true}],
           "edges":[{"source":"100","target":"200","kind":"REACHING_DEF"},
                    {"source":"200","target":"100","kind":"AST"}]}
          """, Map.class);
        var saved = mapper.readTree(CpgGraph.from("cpg", graph, null, "A.java", Map.of("B.java", "line1\nprint(value)")));
        assertEquals(2, saved.path("version").asInt());
        assertEquals(2, saved.path("edges").size());
        assertEquals("AST", saved.path("edges").get(1).path("kind").asText());
        assertEquals(2, saved.path("nodes").get(1).path("excerpt").path("targetLine").asInt());
        assertNull(CpgGraph.from("qwen-semantic", graph, null, "A.java", Map.of()));
        graph.put("edges", List.of(Map.of("source", "100", "target", "missing", "kind", "CFG")));
        assertNull(CpgGraph.from("cpg", graph, null, "A.java", Map.of()));
        graph.put("edges", List.of(Map.of("source", "100", "target", "200", "kind", "invented")));
        assertNull(CpgGraph.from("cpg", graph, null, "A.java", Map.of()));
    }
}
