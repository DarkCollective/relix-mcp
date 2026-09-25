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
     * One page a search found.
     *
     * @param title   the page's title, or the function's name
     * @param where   how to ask {@code learn} for it: a page path, or a function name
     * @param summary the page's one-line summary
     * @param excerpt the line of the page that best matches what was asked, or empty
     */
    record Hit(String title, String where, String summary, String excerpt) {
    }

    /**
     * Searches every page's text for the words of a request, for a lookup that named no
     * page exactly.
     *
     * <p>A model asks for what it wants to do, not for what an operator is called:
     * "pairwise test combinations", not {@code COVER}. The words it uses are usually in
     * the page, just not in its title, so the whole text is searched. A page ranks by how
     * of the request's words it contains, each weighted by how few pages contain it, and
     * counted higher in its title, summary or keys than in its body. A word matches the
     * start of a word, so {@code pair} finds {@code pairwise}, and a trailing {@code s} is
     * dropped so singular and plural meet.
     *
     * @param request what was asked for, in any words
     * @return the best matches, most relevant first; empty when no page has any of them
     */
    List<Hit> search(String request) {
        List<String> words = words(request);
        if (words.isEmpty()) {
            return List.of();
        }
        // A word in few pages says more about which page is wanted than one in many:
        // "path" picks out a handful of pages, "between" almost all of them.
        Map<String, Double> weight = new java.util.HashMap<>();
        for (String word : words) {
            long with = corpus.stream().filter(p -> occurrences(p.text(), word) > 0).count();
            weight.put(word, Math.log((corpus.size() + 1.0) / (with + 1.0)));
        }
        record Scored(Page page, double score, String rarest) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Page page : corpus) {
            double score = 0;
            String rarest = null;
            for (String word : words) {
                int inBody = occurrences(page.text(), word);
                if (inBody == 0) {
                    continue;
                }
                boolean inHeading = occurrences(page.heading(), word) > 0;
                score += weight.get(word) * (1 + Math.min(inBody, 5) / 5.0 + (inHeading ? 2 : 0));
                if (rarest == null || weight.get(word) > weight.get(rarest)) {
                    rarest = word;
                }
            }
            if (score > 0) {
                scored.add(new Scored(page, score, rarest));
            }
        }
        return scored.stream()
                .sorted(java.util.Comparator.comparingDouble(Scored::score).reversed())
                .limit(8)
                .map(s -> new Hit(s.page().title(), s.page().where(), s.page().summary(),
                        excerpt(s.page().markdown(), s.rarest())))
                .toList();
    }

    /**
     * {@return the reference's pages in one category, as title and summary, in the order
     * the reference lists them}
     *
     * @param category a category, such as {@code advanced}
     */
    List<ReferencePage> category(String category) {
        return pages.stream().filter(p -> p.category().equals(category)).toList();
    }

    /** A page as search sees it: what to show, and the text to look in. */
    private record Page(String title, String where, String summary, String markdown,
                        String heading, String text) {
    }

    private final List<Page> corpus = corpus();

    private List<Page> corpus() {
        List<Page> corpus = new ArrayList<>();
        for (ReferencePage page : pages) {
            String markdown = Relix.referencePage(page.path()).orElse("");
            corpus.add(new Page(page.title(), page.path(), page.summary(), markdown,
                    fold(page.title() + " " + page.symbol() + " " + page.summary() + " "
                            + String.join(" ", page.keys())),
                    fold(markdown)));
        }
        for (FunctionPage function : functions) {
            String markdown = function.library().documentation(function.docKey()).orElse("");
            String summary = markdown.lines().findFirst().orElse(function.name())
                    .replaceFirst("^#\\s*Name:\\s*", "");
            corpus.add(new Page(function.name(), function.name(), summary, markdown,
                    fold(function.name() + " " + function.category()), fold(markdown)));
        }
        return List.copyOf(corpus);
    }

    /** The words worth searching for: lower case, no punctuation, no filler. */
    private static List<String> words(String request) {
        List<String> words = new ArrayList<>();
        for (String word : fold(request).split("[^\\p{L}\\p{N}_]+")) {
            if (word.length() < 2 || FILLER.contains(word)) {
                continue;
            }
            String stem = word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
            if (!words.contains(stem)) {
                words.add(stem);
            }
        }
        return words;
    }

    private static final java.util.Set<String> FILLER = java.util.Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "by", "with", "is",
            "are", "be", "it", "that", "this", "how", "do", "i", "me", "my", "what", "which",
            "every", "each", "all", "from", "into", "relix", "query", "want", "can", "find");

    /** How often a word starts a word in the text: {@code pair} counts in {@code pairwise}, not in {@code repair}. */
    private static int occurrences(String text, String word) {
        int count = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            if (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1))) {
                count++;
            }
        }
        return count;
    }

    /** The first line of prose that holds the word, trimmed to a readable length. */
    private static String excerpt(String markdown, String word) {
        if (word == null) {
            return "";
        }
        return markdown.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && occurrences(fold(line), word) > 0)
                .findFirst()
                .map(line -> line.length() > 200 ? line.substring(0, 197) + "..." : line)
                .orElse("");
    }

    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT);
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
