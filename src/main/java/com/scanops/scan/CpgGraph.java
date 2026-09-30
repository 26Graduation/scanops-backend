package com.scanops.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A finding's ordered Joern path, not a reconstructed or LLM-generated graph. */
public final class CpgGraph {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Node(String id, String role, String file, int line, String code,
                       SourceExcerpt excerpt) {}
    public record Edge(String source, String target) {}
    public record Graph(int version, String kind, List<Node> nodes, List<Edge> edges) {}

    public static String from(String source, List<Map<String, Object>> path,
                              String defaultFile, Map<String, String> files) {
        // Mixed/semantic evidence cannot establish that each step came from Joern.
        if (!"cpg".equals(source) || path == null || path.isEmpty() || path.size() > 256) return null;
        List<Node> nodes = new ArrayList<>();
        for (Map<String, Object> step : path) {
            if (step == null || !(step.get("role") instanceof String role)
                    || !List.of("source", "intermediate", "sink").contains(role)
                    || !(step.get("line") instanceof Number line) || line.intValue() <= 0
                    || !(step.get("code") instanceof String code) || code.isBlank()) return null;
            String file = step.get("file") instanceof String value && !value.isBlank() ? value : defaultFile;
            if (file == null) return null;
            nodes.add(new Node("n" + nodes.size(), role, file, line.intValue(), code,
                    SourceExcerpt.around(files.get(file), line.intValue())));
        }
        boolean complete = nodes.size() > 1 && nodes.get(0).role().equals("source")
                && nodes.get(nodes.size() - 1).role().equals("sink");
        String kind = complete ? "data-flow" : nodes.size() == 1 && nodes.get(0).role().equals("sink")
                ? "call-site" : "partial-flow";
        List<Edge> edges = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) edges.add(new Edge(nodes.get(i - 1).id(), nodes.get(i).id()));
        try { return JSON.writeValueAsString(new Graph(1, kind, nodes, edges)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize CPG path", e); }
    }
    private CpgGraph() {}
}
