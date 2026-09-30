package com.scanops.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates actual Joern subgraphs and legacy ordered paths; never synthesizes topology. */
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
    /** Version 2 retains Joern's node IDs and edge types, including branches and cycles. */
    public static String from(String source, Map<String, Object> graph,
                              List<Map<String, Object>> path, String defaultFile,
                              Map<String, String> files) {
        if (graph == null) return from(source, path, defaultFile, files);
        if (!"cpg".equals(source)) return null;
        var root = JSON.valueToTree(graph);
        if (!root.path("version").isInt() || root.path("version").asInt() != 2 || !"cpg".equals(root.path("kind").asText())
                || !"finding-neighborhood".equals(root.path("scope").asText())
                || !root.path("truncated").isBoolean()
                || !root.path("nodes").isArray() || root.path("nodes").isEmpty()
                || root.path("nodes").size() > 64 || !root.path("edges").isArray()
                || root.path("edges").size() > 256) return null;
        var ids = new java.util.HashSet<String>();
        var nodes = JSON.createArrayNode();
        for (var n : root.path("nodes")) {
            if (!n.path("id").isTextual() || n.path("id").asText().isBlank()
                    || !ids.add(n.path("id").asText())
                    || !List.of("source", "sink", "intermediate").contains(n.path("role").asText())
                    || !n.path("file").isTextual() || n.path("file").asText().isBlank()
                    || !n.path("line").isIntegralNumber() || !n.path("line").canConvertToInt()
                    || n.path("line").asInt() <= 0 || !n.path("code").isTextual()
                    || n.path("code").asText().isBlank() || n.path("code").asText().length() > 2000
                    || !n.path("label").isTextual() || !n.path("onPath").isBoolean()) return null;
            var out = JSON.createObjectNode();
            for (String key : List.of("id", "role", "file", "line", "code", "label", "onPath")) out.set(key, n.get(key));
            out.set("excerpt", JSON.valueToTree(SourceExcerpt.around(
                    files.get(n.path("file").asText()), n.path("line").asInt())));
            nodes.add(out);
        }
        var edges = JSON.createArrayNode();
        var seenEdges = new java.util.HashSet<String>();
        for (var e : root.path("edges")) {
            String src = e.path("source").asText(), dst = e.path("target").asText(), kind = e.path("kind").asText();
            if (!e.path("source").isTextual() || !e.path("target").isTextual()
                    || !ids.contains(src) || !ids.contains(dst)
                    || !List.of("AST", "CFG", "REACHING_DEF").contains(kind)
                    || !seenEdges.add(src + "\u0000" + dst + "\u0000" + kind)) return null;
            edges.add(JSON.createObjectNode().put("source", src).put("target", dst).put("kind", kind));
        }
        var out = JSON.createObjectNode().put("version", 2).put("kind", "cpg")
                .put("scope", "finding-neighborhood").put("truncated", root.path("truncated").asBoolean());
        out.set("nodes", nodes);
        out.set("edges", edges);
        return out.toString();
    }
    private CpgGraph() {}
}
