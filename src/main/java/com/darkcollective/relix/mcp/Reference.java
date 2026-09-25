package com.darkcollective.relix.mcp;

import com.darkcollective.relix.embed.ReferencePage;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.ScalarFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The language reference, as the engine jar ships it.
 *
 * <p>Nothing here is written for the server. It comes from two public sources, the same
 * two the REPL's {@code :doc} reads:
 *
 * <ul>
 *   <li>the <strong>language</strong> pages — operators, joins, statements — from
 *       {@link Relix#referencePages()} and {@link Relix#referencePage(String)};</li>
 *   <li>the <strong>function</strong> pages from each installed {@link FunctionLibrary},
 *       found the way the engine finds them, through {@link ServiceLoader}. A function's
 *       page belongs to the library that offers it, so a library added to the class path
 *       is documented here with nothing else to change.</li>
 * </ul>
 *
 * <p>So what a model learns here is what the engine it is checked against says about
 * itself, for the version it is checked against.
 */
final class Reference {

    /** One function with a documentation page, and the library that holds the page. */
    private record FunctionPage(String name, String category, String docKey,
                                FunctionLibrary library) {
    }

    private final List<ReferencePage> pages = Relix.referencePages();
    private final List<FunctionPage> functions = functionPages();

    /**
     * {@return every page, one line each, grouped by category, then every function}
     */
    String contents() {
        StringBuilder out = new StringBuilder("Relix language reference (engine ")
                .append(Relix.version()).append("). Ask for a page by its symbol, name or ")
                .append("path, or for a function by its name.\n");
        Map<String, List<ReferencePage>> byCategory = new TreeMap<>(pages.stream()
                .collect(Collectors.groupingBy(ReferencePage::category)));
        byCategory.forEach((category, inCategory) -> {
            out.append("\n## ").append(category).append('\n');
            for (ReferencePage page : inCategory) {
                out.append("- ").append(page.title());
                if (!page.symbol().equalsIgnoreCase(page.title())) {
                    out.append(" (").append(page.symbol()).append(')');
                }
                out.append(" — ").append(page.summary())
                        .append(" [").append(page.path()).append("]\n");
            }
        });
        Map<String, List<FunctionPage>> byFunctionCategory = new TreeMap<>(functions.stream()
                .collect(Collectors.groupingBy(FunctionPage::category)));
        out.append("\n## functions\n");
        byFunctionCategory.forEach((category, inCategory) -> out.append("- ")
                .append(category).append(": ")
                .append(inCategory.stream().map(FunctionPage::name).sorted()
                        .collect(Collectors.joining(", ")))
                .append('\n'));
        return out.toString();
    }

    /**
     * Finds the page a topic names: a language page by path, lookup key or title, then a
     * function by name.
     *
     * @param topic a glyph, keyword, name, function name or path, in any case
     * @return the page's markdown, when one matches
     */
    Optional<String> page(String topic) {
        String wanted = topic.strip();
        String folded = wanted.toLowerCase(Locale.ROOT);
        Optional<String> language = pages.stream()
                .filter(p -> p.path().equalsIgnoreCase(wanted)
                        || p.keys().contains(wanted) || p.keys().contains(folded)
                        || p.title().equalsIgnoreCase(wanted))
                .findFirst()
                .flatMap(p -> Relix.referencePage(p.path()));
        if (language.isPresent()) {
            return language;
        }
        return functions.stream()
                .filter(f -> f.name().equalsIgnoreCase(wanted))
                .findFirst()
                .flatMap(f -> f.library().documentation(f.docKey()));
    }

    /**
     * {@return what mentions the topic, for a lookup that matched nothing exactly}
     *
     * @param topic what was asked for
     */
    List<String> near(String topic) {
        String folded = topic.strip().toLowerCase(Locale.ROOT);
        List<String> near = new ArrayList<>();
        pages.stream()
                .filter(p -> p.title().toLowerCase(Locale.ROOT).contains(folded)
                        || p.symbol().toLowerCase(Locale.ROOT).contains(folded)
                        || p.keys().stream().anyMatch(k -> k.contains(folded)))
                .forEach(p -> near.add(p.title() + " [" + p.path() + "]"));
        functions.stream()
                .filter(f -> f.name().toLowerCase(Locale.ROOT).contains(folded))
                .forEach(f -> near.add(f.name() + " [function]"));
        return near.stream().limit(10).toList();
    }

    /**
     * {@return the path of the page that pairs each glyph with its ASCII keyword, if the
     * reference has one}
     *
     * <p>A lookup by ASCII keyword misses when the keyword differs from the page's name
     * ({@code ANTI} for the anti join), because pages are not keyed by it
     * (relix-core#27). Until they are, a miss points here.
     */
    Optional<String> spellingsPage() {
        return pages.stream().filter(p -> p.keys().contains("spellings"))
                .map(ReferencePage::path).findFirst();
    }

    private static List<FunctionPage> functionPages() {
        List<FunctionPage> found = new ArrayList<>();
        for (FunctionLibrary library : ServiceLoader.load(FunctionLibrary.class)) {
            for (ScalarFunction f : library.scalarFunctions()) {
                f.signature().docKey().ifPresent(key -> found.add(
                        new FunctionPage(f.name(), f.signature().category(), key, library)));
            }
            for (AggregateFunction f : library.aggregateFunctions()) {
                f.signature().docKey().ifPresent(key -> found.add(
                        new FunctionPage(f.name(), f.signature().category(), key, library)));
            }
        }
        return List.copyOf(found);
    }
}
