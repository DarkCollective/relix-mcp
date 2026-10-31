package com.darkcollective.relix.mcp;

import com.darkcollective.relix.docs.ReferenceLookup;
import com.darkcollective.relix.docs.ReferencePage;
import com.darkcollective.relix.docs.RelixDocs;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.FunctionCatalog;
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
 * The language reference, as the engine publishes it.
 *
 * <p>Nothing here is written for the server. It comes from two public sources, the same
 * two the REPL's {@code :doc} reads:
 *
 * <ul>
 *   <li>the <strong>language</strong> pages — operators, joins, statements — from
 *       {@link RelixDocs#referencePages()} and {@link RelixDocs#referencePage(String)};</li>
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

    private final List<ReferencePage> pages = RelixDocs.referencePages();
    private final List<FunctionPage> functions = functionPages();

    /**
     * The path of the reference's catalogue of operators by task: each task SQL has no
     * operator for, in a question's words, with the page of the operator that does it.
     * relix-docs publishes it at this path (relix-core#245), and its links are the paths
     * {@link #page(String)} reads.
     */
    static final String BY_TASK = "operators-by-task.md";

    /**
     * {@return the catalogue of operators by task, as the reference publishes it, when the
     * reference has one}
     *
     * <p>A model that does not know an operator exists writes a workaround instead of
     * asking for it, so the server's instructions and {@code relix_learn}'s description
     * carry this page whole. It ships with the engine's reference, so it cannot fall behind
     * the operators the server runs.
     */
    static Optional<String> operatorsByTask() {
        return RelixDocs.referencePage(BY_TASK).map(String::strip);
    }

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
     * relix-docs' own index of what a user types: the language pages and every installed
     * function's page, with the keys relix-docs gives them (SQL's {@code case} finds
     * {@code Switch}). Built from the libraries the engine discovers, as {@link #functions}.
     */
    private final ReferenceLookup lookup = ReferenceLookup.of(FunctionCatalog.discover());

    /**
     * Finds the page a topic names: a language page by path, lookup key or title, then any
     * page relix-docs' {@link ReferenceLookup} keys by the topic, then a function by name or
     * by its page's path.
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
                .flatMap(p -> RelixDocs.referencePage(p.path()));
        if (language.isPresent()) {
            return language;
        }
        // A function by its name, or by its page's path, which is how the reference's own
        // pages link it: the catalogue of operators by task links functions/datetime/age.md.
        return lookup.lookup(wanted).or(() -> functions.stream()
                .filter(f -> f.name().equalsIgnoreCase(wanted) || f.docKey().equalsIgnoreCase(wanted))
                .findFirst()
                .flatMap(f -> f.library().documentation(f.docKey())));
    }

    /**
     * A page cut to what writing a script from it needs first: its name, its syntax, its
     * first example, and the SQL names it answers to.
     *
     * <p>Every page, language and function alike, follows the reference's published
     * template ({@code docs/reference/.template.md} in relix-docs), whose sections are
     * top-level headings: {@code # Name:}, {@code # Syntax:}, {@code # Examples:} and so on.
     * An example is a {@code ##} sub-heading and what follows it, or else the paragraphs up
     * to and including the first that holds code, indented or fenced. A page
     * without a syntax section is returned whole.
     *
     * @param markdown a page, as {@link #page(String)} returns it
     * @return the brief page
     */
    static String brief(String markdown) {
        List<String> lines = markdown.lines().toList();
        List<String> syntax = section(lines, "# Syntax:");
        if (syntax.isEmpty()) {
            return markdown;
        }
        StringBuilder out = new StringBuilder();
        lines.stream().filter(l -> l.startsWith("# Name:")).findFirst()
                .ifPresent(name -> out.append(name).append("\n\n"));
        syntax.forEach(line -> out.append(line).append('\n'));
        List<String> example = firstExample(section(lines, "# Examples:"));
        if (!example.isEmpty()) {
            out.append("\n# Example:\n");
            example.forEach(line -> out.append(line).append('\n'));
        }
        // A page found by its SQL name is titled by its own: LOWER finds LCase. Without
        // this section a brief page reads as though the name asked for does not exist.
        List<String> sqlNames = section(lines, "# SQL names:");
        if (!sqlNames.isEmpty()) {
            out.append('\n');
            sqlNames.forEach(line -> out.append(line).append('\n'));
        }
        return out.toString().strip();
    }

    /**
     * The lines of the section a top-level heading opens, the heading included and blank
     * lines at either end left out; empty when the page has no such heading. A {@code #}
     * inside a fenced code block is not a heading.
     */
    private static List<String> section(List<String> lines, String heading) {
        List<String> section = new ArrayList<>();
        boolean inside = false;
        boolean fenced = false;
        for (String line : lines) {
            if (!fenced && line.startsWith("# ")) {
                if (inside) {
                    break;
                }
                inside = line.strip().equals(heading);
            }
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
            }
            if (inside) {
                section.add(line);
            }
        }
        return trimBlank(section);
    }

    /** The first example of an examples section, without its heading. */
    private static List<String> firstExample(List<String> section) {
        List<String> body = trimBlank(section.isEmpty() ? section : section.subList(1, section.size()));
        List<String> example = new ArrayList<>();
        boolean subheaded = !body.isEmpty() && body.getFirst().startsWith("## ");
        boolean fenced = false;
        boolean code = false;
        for (String line : body) {
            // A paragraph ends the example once the example holds code: a sentence that
            // introduces its code a blank line before it is not an example on its own.
            boolean boundary = subheaded ? line.startsWith("## ") && !example.isEmpty()
                    : line.isBlank() && code;
            if (!fenced && boundary) {
                break;
            }
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
            }
            code |= fenced || line.startsWith(" ") || line.startsWith("\t");
            example.add(line);
        }
        return trimBlank(example);
    }

    private static List<String> trimBlank(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isBlank()) {
            from++;
        }
        while (to > from && lines.get(to - 1).isBlank()) {
            to--;
        }
        return lines.subList(from, to);
    }

    /**
     * One page a search found.
     *
     * @param title   the page's title, or the function's name
     * @param where   how to ask {@code relix_learn} for it: a page path, or a function name
     * @param summary the page's one-line summary
     * @param excerpt the line of the page that best matches what was asked, or empty
     */
    record Hit(String title, String where, String summary, String excerpt) {
    }

    /**
     * What a search found.
     *
     * @param hits    the best matches, most relevant first
     * @param matched how many pages matched in all, of which {@code hits} are the first
     */
    record Found(List<Hit> hits, int matched) {
    }

    /** How many matches a search shows. */
    static final int SHOWN = 8;

    /**
     * A function category, and the name of every function in it.
     *
     * @param name      the category, as the function's signature gives it
     * @param functions its functions' names, sorted
     */
    record Category(String name, List<String> functions) {
    }

    /**
     * {@return the function categories a request's words name}
     *
     * <p>A request such as "string manipulation" names a category, not a task, and every
     * function in it matches alike, so a ranking would keep an arbitrary few. The
     * category's whole listing answers it instead. A word names a category when either
     * starts the other and the shorter has four letters or more, so {@code strings},
     * {@code date} and {@code mathematical} find {@code string}, {@code datetime} and
     * {@code math}.
     *
     * @param request what was asked for, in any words
     */
    List<Category> categories(String request) {
        List<String> words = words(request);
        Map<String, List<FunctionPage>> byCategory = new TreeMap<>(functions.stream()
                .collect(Collectors.groupingBy(FunctionPage::category)));
        List<Category> named = new ArrayList<>();
        byCategory.forEach((category, inCategory) -> {
            String folded = fold(category);
            if (words.stream().anyMatch(word -> names(word, folded))) {
                named.add(new Category(category, inCategory.stream().map(FunctionPage::name)
                        .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList()));
            }
        });
        return named;
    }

    private static boolean names(String word, String category) {
        String shorter = word.length() < category.length() ? word : category;
        return shorter.length() >= 4 && (category.startsWith(word) || word.startsWith(category));
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
     * @return the best {@value #SHOWN} matches, most relevant first, and how many pages
     *         matched; no hits when no page has any of the words
     */
    Found search(String request) {
        List<String> words = words(request);
        if (words.isEmpty()) {
            return new Found(List.of(), 0);
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
        // A long page holds most words somewhere: the optimizer and grammar pages mention
        // every operator. A word counts for less in a page the longer it is than the
        // average, as BM25 weighs it, so a page about the request outranks one that
        // merely lists its words.
        double average = corpus.stream().mapToInt(p -> p.text().length()).average().orElse(1);
        List<Scored> scored = new ArrayList<>();
        for (Page page : corpus) {
            double score = 0;
            String rarest = null;
            double length = page.text().length() / average;
            for (String word : words) {
                int inBody = occurrences(page.text(), word);
                if (inBody == 0) {
                    continue;
                }
                boolean inHeading = occurrences(page.heading(), word) > 0;
                double frequency = inBody * (K1 + 1) / (inBody + K1 * (1 - B + B * length));
                score += weight.get(word) * (frequency + (inHeading ? 3 : 0));
                if (rarest == null || weight.get(word) > weight.get(rarest)) {
                    rarest = word;
                }
            }
            if (score > 0) {
                scored.add(new Scored(page, score, rarest));
            }
        }
        List<Hit> hits = scored.stream()
                .sorted(java.util.Comparator.comparingDouble(Scored::score).reversed())
                .limit(SHOWN)
                .map(s -> new Hit(s.page().title(), s.page().where(), s.page().summary(),
                        excerpt(s.page().markdown(), s.rarest())))
                .toList();
        return new Found(hits, scored.size());
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
            String markdown = RelixDocs.referencePage(page.path()).orElse("");
            corpus.add(new Page(page.title(), page.path(), page.summary(), markdown,
                    fold(page.title() + " " + page.symbol() + " " + page.summary() + " "
                            + String.join(" ", page.keys())),
                    fold(markdown)));
        }
        for (FunctionPage function : functions) {
            String markdown = function.library().documentation(function.docKey()).orElse("");
            String summary = markdown.lines().findFirst().orElse(function.name())
                    .replaceFirst("^#\\s*Name:\\s*", "");
            // The summary is the page's own words for what it does ("inline if / conditional
            // value"), weighed as a language page's summary is.
            corpus.add(new Page(function.name(), function.name(), summary, markdown,
                    fold(function.name() + " " + function.category() + " " + summary), fold(markdown)));
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

    /** BM25's term-frequency saturation and length normalisation, at their usual values. */
    private static final double K1 = 1.2;
    private static final double B = 0.75;

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
