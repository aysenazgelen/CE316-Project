package com.iae.logic;

import com.iae.model.ComparisonResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class OutputComparator {

    public ComparisonResult compare(String actualOutput, String expectedOutputFilePath) {
        String expected;
        try {
            expected = Files.readString(Path.of(expectedOutputFilePath));
        } catch (IOException e) {
            return new ComparisonResult(false, "Cannot read expected output file: " + e.getMessage());
        }

        String normalizedActual   = normalize(actualOutput);
        String normalizedExpected = normalize(expected);

        if (normalizedActual.equals(normalizedExpected)) {
            return new ComparisonResult(true, "");
        }

        return new ComparisonResult(false, buildDiff(normalizedExpected, normalizedActual));
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.lines()                     // split by any line ending
                .map(String::strip)             // .map(s -> s.strip())    // trim leading AND trailing spaces per line
                .collect(Collectors.joining("\n"))
                .stripTrailing();
    }

    private String buildDiff(String expected, String actual) {
        String[] expectedLines = expected.split("\n", -1);
        String[] actualLines   = actual.split("\n", -1);

        StringBuilder diff = new StringBuilder();
        int max = Math.max(expectedLines.length, actualLines.length);

        for (int i = 0; i < max; i++) {
            String exp = i < expectedLines.length ? expectedLines[i] : "<missing>";
            String act = i < actualLines.length   ? actualLines[i]   : "<missing>";
            if (!exp.equals(act)) {
                diff.append(String.format("Line %d — expected: [%s]  actual: [%s]%n", i + 1, exp, act));
            }
        }

        return diff.toString();
    }
}
