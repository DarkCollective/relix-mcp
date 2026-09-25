package com.darkcollective.relix.mcp.validate;

import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks a Relix script without running it, and without reaching anything it names.
 *
 * <p>The script may declare the caller's own files, databases and HTTP endpoints. None of
 * them is opened. What the engine would otherwise learn by looking, the caller supplies:
 *
 * <ul>
 *   <li>the <strong>files it imports</strong>, as text ({@link ScriptFiles});</li>
 *   <li>the <strong>tables of its connections</strong>, as a column list per table
 *       ({@link CallerCatalog}). A script that declares a source's {@code schema:} needs
 *       none, because the declaration already says.</li>
 * </ul>
 *
 * <p>Everything here is the engine's public API. The session is an ordinary
 * {@link Relix} session built with three choices, each of which closes one way analysis
 * could reach outside the process:
 *
 * <ol>
 *   <li>{@code catalog(…)} — the caller's description of its connections, which
 *       <em>replaces</em> the default catalog. The default describes a table by asking the
 *       connection, which for a {@code jdbc} URL means connecting to it.</li>
 *   <li>{@code remoteFiles(false)} — a file connection naming an {@code https} URL is an
 *       error rather than a download.</li>
 *   <li>{@code scriptLoader(…)} — an {@code import} is served from what the caller sent,
 *       never from this machine's disk.</li>
 * </ol>
 *
 * <p>And then only {@link Relix#validate(String)} is called. Analysis reads no rows, so
 * nothing past the catalog is ever asked for; the session is closed without anything
 * having been executed.
 */
public final class ScriptValidator {

    /**
     * Validates a script.
     *
     * @param script  the script's text; must not be null
     * @param files   the text of every file it imports, keyed by the path its
     *                {@code import} statement writes; must not be null, may be empty
     * @param catalog the tables of the connections it reads; must not be null
     * @return what was found
     */
    public ValidationReport validate(String script, Map<String, String> files,
                                     CallerCatalog catalog) {
        Objects.requireNonNull(script, "script");
        ScriptFiles imports = ScriptFiles.of(files);
        List<Finding> findings = new ArrayList<>(syntaxErrors(imports));
        if (!findings.isEmpty()) {
            return new ValidationReport(false, findings);
        }
        try (Relix relix = Relix.builder()
                .catalog(catalog)
                .remoteFiles(false)
                .scriptLoader(imports)
                .build()) {
            for (Diagnostic diagnostic : relix.validate(script)) {
                findings.add(finding(diagnostic, imports));
            }
        } catch (RelixException e) {
            // validate reports rather than throws; this is the backstop for a failure it
            // could not turn into a diagnostic, which is still an answer about the script.
            findings.add(new Finding(Finding.ERROR, e.getMessage(), null, 0, 0));
        }
        if (findings.stream().noneMatch(Finding::isError)) {
            findings.addAll(openSchemaWarnings(script, null));
            imports.files().forEach((path, text) -> findings.addAll(openSchemaWarnings(text, path)));
        }
        findings.sort(Comparator.comparing(Finding::isError).reversed());
        return new ValidationReport(findings.stream().noneMatch(Finding::isError), findings);
    }

    /**
     * Parses each file sent, so a syntax error in one is reported against that file.
     *
     * <p>{@code Relix.validate} reports a syntax error in the script it is given as a
     * diagnostic, but one inside an <em>imported</em> file escapes it as an exception
     * that does not say which file. Parsing each file first, with its path as the text's
     * origin, places the error where the caller can find it. Remove once
     * relix-core#26 ships in a release this server pins.
     */
    private static List<Finding> syntaxErrors(ScriptFiles imports) {
        List<Finding> errors = new ArrayList<>();
        imports.files().forEach((path, text) -> {
            try {
                Relix.parse(text, path);
            } catch (ScriptParseException e) {
                errors.add(new Finding(Finding.ERROR, e.getMessage(), path, e.line(), e.column()));
            }
        });
        return errors;
    }

    private static Finding finding(Diagnostic diagnostic, ScriptFiles imports) {
        String severity = diagnostic.isError() ? Finding.ERROR : Finding.WARNING;
        return diagnostic.location()
                .map(at -> new Finding(severity, diagnostic.message(),
                        // A location in a file the caller sent names that file; anything else
                        // is the script itself, however the engine chose to label it.
                        imports.files().containsKey(ScriptFiles.normalise(at.filePath()))
                                ? at.filePath() : null,
                        at.line(), at.column()))
                .orElseGet(() -> new Finding(severity, diagnostic.message(), null, 0, 0));
    }

    /**
     * A warning for each source that will accept any column name.
     *
     * <p>A JSON file, or an HTTP source with no {@code schema:}, is <em>open</em>: its
     * columns are whatever each record holds, found at run time. So a misspelt field
     * validates, and reads as NULL on every row, which is a plausible wrong answer rather
     * than an error. Validation cannot see that, and says so rather than implying a check
     * it did not make.
     */
    private static List<Finding> openSchemaWarnings(String text, String file) {
        Script parsed;
        try {
            parsed = Relix.parse(text, file == null ? "script" : file);
        } catch (ScriptParseException e) {
            return List.of();
        }
        List<Finding> warnings = new ArrayList<>();
        for (Statement statement : parsed.statements()) {
            if (statement instanceof SourceDeclaration source) {
                openSchemaAdvice(source).ifPresent(advice -> warnings.add(new Finding(
                        Finding.WARNING, "source '" + source.name() + "' has no declared "
                        + "schema, so the column names read from it were not checked: a "
                        + "misspelt one reads as NULL on every row rather than failing. "
                        + advice, file, source.location().line(), source.location().column())));
            }
        }
        return warnings;
    }

    /** What to do about an open source, or empty when the source is not open. */
    private static Optional<String> openSchemaAdvice(SourceDeclaration source) {
        return switch (source.config()) {
            case JsonFileSourceConfig json -> Optional.of("A JSON file source cannot declare "
                    + "one, so check each field name against the file itself.");
            case HttpSourceConfig http when http.columns().isEmpty() -> Optional.of(
                    "Declare a schema: block to have them checked.");
            default -> Optional.empty();
        };
    }
}
