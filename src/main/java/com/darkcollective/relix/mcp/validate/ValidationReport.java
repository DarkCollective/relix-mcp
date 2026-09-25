package com.darkcollective.relix.mcp.validate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What validating one script found.
 *
 * @param valid    whether the script would be accepted: no finding is an error
 * @param findings every error and warning, errors first, each in the order the engine
 *                 reported it
 */
public record ValidationReport(boolean valid, List<Finding> findings) {

    /**
     * Copies the findings.
     */
    public ValidationReport {
        findings = List.copyOf(findings);
    }

    /**
     * {@return the report as text a model reads: a verdict line, then one line per finding}
     */
    public String render() {
        long errors = findings.stream().filter(Finding::isError).count();
        long warnings = findings.size() - errors;
        String verdict = valid
                ? "Valid." + (warnings == 0 ? "" : " " + count(warnings, "warning") + ":")
                : "Invalid: " + count(errors, "error")
                        + (warnings == 0 ? "" : " and " + count(warnings, "warning")) + ".";
        if (findings.isEmpty()) {
            return verdict;
        }
        return verdict + "\n" + findings.stream().map(Finding::render)
                .collect(Collectors.joining("\n"));
    }

    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
