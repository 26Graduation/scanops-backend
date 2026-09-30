package com.scanops.scan;

import java.util.Arrays;

/** Immutable excerpt from the exact source sent to the scanner; one-based lines. */
public record SourceExcerpt(String code, int startLine, int targetLine) {
    public static SourceExcerpt around(String source, int targetLine) {
        if (source == null || source.isEmpty()) return null;
        String[] lines = source.replaceFirst("\\r?\\n$", "").split("\\r?\\n", -1);
        if (targetLine < 1 || targetLine > lines.length) return null;
        int start = Math.max(1, targetLine - 5);
        int end = Math.min(lines.length, targetLine + 5);
        return new SourceExcerpt(String.join("\n", Arrays.copyOfRange(lines, start - 1, end)), start, targetLine);
    }
}
