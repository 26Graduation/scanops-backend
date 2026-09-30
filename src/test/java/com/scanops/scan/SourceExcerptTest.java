package com.scanops.scan;

import org.junit.jupiter.api.Test;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SourceExcerptTest {
    private final String source = IntStream.rangeClosed(1, 20).mapToObj(i -> "line " + i).collect(Collectors.joining("\r\n"));
    @Test void includesFiveLinesOnEachSide() {
        var excerpt = SourceExcerpt.around(source, 12);
        assertEquals(7, excerpt.startLine());
        assertEquals(12, excerpt.targetLine());
        assertEquals(11, excerpt.code().split("\n").length);
        assertTrue(excerpt.code().startsWith("line 7\n"));
        assertTrue(excerpt.code().endsWith("line 17"));
    }
    @Test void clipsAtFileBoundaries() {
        assertEquals(1, SourceExcerpt.around(source, 1).startLine());
        assertEquals(6, SourceExcerpt.around(source, 1).code().split("\n").length);
        assertEquals(6, SourceExcerpt.around(source, 20).code().split("\n").length);
    }
    @Test void neverInventsMissingCodeOrLineNumbers() {
        assertNull(SourceExcerpt.around(null, 1));
        assertNull(SourceExcerpt.around(source, 0));
        assertNull(SourceExcerpt.around(source, 21));
    }
}
