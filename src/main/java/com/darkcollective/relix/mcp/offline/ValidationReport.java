package com.darkcollective.relix.mcp.offline;

import java.util.List;
import java.util.function.Function;
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
        return render(file -> null);
    }

    /**
     * {@return the report as {@link #render()} gives it, each placed finding followed by
     * the source line it points at}
     *
     * @param sources the text of a finding's file, or of the script for a null file, as
     *                for {@link Finding#render(Function)}
     */
    public String render(Function<String, String> sources) {
        long errors = findings.stream().filter(Finding::isError).count();
        long warnings = findings.size() - errors;
        String verdict = valid
                ? "Valid." + (warnings == 0 ? "" : " " + count(warnings, "warning") + ":")
                : "Invalid: " + count(errors, "error")
                        + (warnings == 0 ? "" : " and " + count(warnings, "warning")) + ".";
        if (findings.isEmpty()) {
            return verdict;
        }
        return verdict + "\n" + findings.stream().map(f -> f.render(sources))
                .collect(Collectors.joining("\n"));
    }

    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
