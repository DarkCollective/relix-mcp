package com.darkcollective.relix.mcp.data;

import com.darkcollective.relix.embed.Diagnostic;
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.RelixException;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.embed.Sandbox;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.mcp.offline.Finding;
import com.darkcollective.relix.mcp.run.QueryResult;
import com.darkcollective.relix.symbol.ColumnDefinition;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads data under a key, and finds it again: what {@code relix_load} and
 * {@code relix_unload} do, and how a script tool reads the data a call names.
 *
 * <p>A data key is a random 128-bit value, held under an <em>owner</em>, the caller's
 * identity as its authenticator gives it, never its credential. A key is found only by
 * the owner that loaded it. Each owner may hold at most {@link #maxKeys()} keys at once;
 * at the limit a load is refused, rather than evicting a key another conversation may
 * still be using.
 *
 * <p>Only inline tables are loaded, and they are checked in a closed sandbox when they
 * are, so a script run over them still reaches nothing outside its session.
 */
public final class DataKeys {

    /** How many keys an owner may hold at once, unless {@value #MAX_KEYS_VARIABLE} says. */
    public static final int DEFAULT_MAX_KEYS = 20;

    /** The variable giving how many keys an owner may hold at once. */
    public static final String MAX_KEYS_VARIABLE = "RELIX_MCP_MAX_DATA_KEYS";

    /**
     * The most a key may hold, in bytes of its declarations: within DynamoDB's 400 KB
     * item, with room for the rest of the item. About 130,000 tokens, which is more than
     * a model's whole context; only a client loading data itself comes near it.
     */
    public static final int MAX_BYTES = 400_000;

    /** Checks the tables: they are inline, so the sandbox needs no declarations. */
    private static final Sandbox SANDBOX = Sandbox.builder().build();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataStore store;
    private final int maxKeys;

    /**
     * Keys held in {@code store}, at most {@code maxKeys} per owner.
     *
     * @param store   where the data is kept
     * @param maxKeys how many keys an owner may hold at once; positive
     */
    public DataKeys(DataStore store, int maxKeys) {
        this.store = Objects.requireNonNull(store, "store");
        if (maxKeys < 1) {
            throw new IllegalArgumentException("an owner must be allowed at least one key");
        }
        this.maxKeys = maxKeys;
    }

    /**
     * How many keys an owner may hold, from the environment.
     *
     * @param env the server's environment
     * @return {@value #DEFAULT_MAX_KEYS}, or what {@value #MAX_KEYS_VARIABLE} says
     * @throws IllegalStateException if {@value #MAX_KEYS_VARIABLE} is set to anything but
     *                               a positive whole number
     */
    public static int maxKeys(Map<String, String> env) {
        String value = env.get(MAX_KEYS_VARIABLE);
        if (value == null) {
            return DEFAULT_MAX_KEYS;
        }
        try {
            int n = Integer.parseInt(value.strip());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below, as zero is
        }
        throw new IllegalStateException(MAX_KEYS_VARIABLE + " takes a positive whole number, "
                + "the most data keys one caller may hold at once, not '" + value + "'");
    }

    /**
     * {@return how many keys an owner may hold at once}
     */
    public int maxKeys() {
        return maxKeys;
    }

    /**
     * Checks and stores tables under a key that may be added to later.
     *
     * @param owner  whose data it is
     * @param key    a key the owner holds, or null for a new one
     * @param tables inline-table declarations, or null or blank for none
     * @return the key and its tables, or why nothing was stored
     */
    public LoadReport load(String owner, String key, String tables) {
        return load(owner, key, tables, false);
    }

    /**
     * Checks and stores tables.
     *
     * <p>With no key, the tables are held under a new one. With a key, they are added to
     * what it holds, and a table it already holds by the same name is replaced; with a key
     * and no tables, nothing changes, and the report describes what the key holds.
     *
     * <p>A <em>read-only</em> key's tables are never replaced or added to, by any caller
     * holding it: a client that loads data for a model to read loads it read-only, so the
     * model cannot change it for the next question. It can still be released, which is how
     * its owner frees its place under the limit.
     *
     * @param owner    whose data it is
     * @param key      a key the owner holds, or null for a new one
     * @param tables   inline-table declarations, or null or blank for none
     * @param readOnly whether the key is to be read-only from now on; a key that is stays so
     * @return the key and its tables, or why nothing was stored
     */
    public LoadReport load(String owner, String key, String tables, boolean readOnly) {
        Objects.requireNonNull(owner, "owner");
        boolean adding = tables != null && !tables.isBlank();
        LoadedData held = LoadedData.NONE;
        if (key != null) {
            Optional<StoredData> stored = store.get(owner, key);
            if (stored.isEmpty()) {
                return LoadReport.refused(notFound(key));
            }
            if (stored.get().readOnly()) {
                if (adding) {
                    return LoadReport.refused("Not loaded: the data key " + key + " is "
                            + "read-only, so its tables cannot be replaced or added to. Read "
                            + "them by passing it as 'data', or load other tables under a new "
                            + "key by calling 'relix_load' without 'key'.");
                }
                readOnly = true;
            }
            held = LoadedData.of(stored.get().declarations());
        } else if (!adding) {
            return LoadReport.refused("'tables' is required: the inline tables to load, each "
                    + "written Name := [ ... ];. Send 'key' with no tables to see what a key holds.");
        } else if (store.active(owner) >= maxKeys) {
            return LoadReport.refused("Not loaded: this caller already holds " + maxKeys
                    + " data keys, the most it may hold at once. Release one you no longer "
                    + "need with 'relix_unload', or add these tables to one you hold by "
                    + "passing it as 'key'.");
        }

        String text = held.text();
        List<String> replaced = new ArrayList<>();
        if (adding) {
            LoadedData added;
            try {
                Script parsed = Relix.parse(tables, LoadedData.LABEL);
                List<Finding> others = LoadedData.notTables(parsed);
                if (!others.isEmpty()) {
                    return LoadReport.invalid(others, tables);
                }
                if (parsed.statements().isEmpty()) {
                    return LoadReport.refused("'tables' declares no tables: send each one "
                            + "written Name := [ ... ];.");
                }
                added = LoadedData.of(tables);
            } catch (ScriptParseException e) {
                return LoadReport.invalid(List.of(new Finding(Finding.ERROR, e.getMessage(),
                        LoadedData.LABEL, e.line(), e.column())), tables);
            }
            // What the key held and this load does not replace, then this load as it was
            // sent: a new key holds the caller's text exactly, so its findings are on the
            // caller's own lines.
            Map<String, String> kept = new LinkedHashMap<>(held.declarations());
            for (String name : added.names()) {
                if (kept.remove(name) != null) {
                    replaced.add(name);
                }
            }
            text = kept.isEmpty() ? tables
                    : String.join("\n\n", kept.values()) + "\n\n" + tables.strip() + "\n";
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            return LoadReport.refused("Not loaded: the data would be " + bytes + " bytes, and "
                    + "a key holds at most " + MAX_BYTES + ". Load fewer rows, or split the "
                    + "tables between keys.");
        }

        LoadedData data = LoadedData.of(text);
        List<Finding> findings = new ArrayList<>();
        List<LoadReport.Table> described = new ArrayList<>();
        try (Relix relix = Relix.builder().sandbox(SANDBOX).build()) {
            for (Diagnostic diagnostic : relix.validate(Relix.parse(text, LoadedData.LABEL))) {
                findings.add(Finding.of(diagnostic, LoadedData.LABEL::equals));
            }
            if (findings.stream().anyMatch(Finding::isError)) {
                findings.sort(Comparator.comparing(Finding::isError).reversed());
                return LoadReport.invalid(findings, text);
            }
            data.define(relix);
            for (String name : data.names()) {
                Rows none = relix.relation(name).limit(0).run();
                described.add(new LoadReport.Table(name, data.rows(name),
                        none.schema().columns().stream().map(ColumnDefinition::name).toList(),
                        none.schema().columns().stream()
                                .map(c -> QueryResult.spelling(c.type())).toList()));
            }
        } catch (RelixException e) {
            return LoadReport.invalid(List.of(new Finding(Finding.ERROR, e.getMessage(),
                    null, 0, 0)), text);
        }

        List<String> warned = new ArrayList<>();
        for (Finding warning : findings) {
            if (LoadedData.LABEL.equals(warning.file())) {
                data.tableAt(warning.line()).filter(t -> !warned.contains(t)).ifPresent(warned::add);
            }
        }
        warned.sort(Comparator.comparing(data.names()::indexOf));
        String under = key != null ? key : newKey();
        store.put(owner, under, new StoredData(text, findings.size(), warned, readOnly));
        return new LoadReport(under, described, replaced, findings, null, text, readOnly);
    }

    /**
     * Finds what {@code key} holds for {@code owner}, and starts its time to live again.
     *
     * @param owner whose data it is
     * @param key   the data key
     * @return the data, or empty when the owner holds no such key
     */
    public Optional<StoredData> find(String owner, String key) {
        return store.get(Objects.requireNonNull(owner, "owner"), Objects.requireNonNull(key, "key"));
    }

    /**
     * Releases a key before it expires.
     *
     * @param owner whose data it is
     * @param key   the data key
     * @return whether the owner held it
     */
    public boolean unload(String owner, String key) {
        return store.remove(Objects.requireNonNull(owner, "owner"), Objects.requireNonNull(key, "key"));
    }

    /**
     * {@return what a call naming a key its caller does not hold is told}
     *
     * @param key the key named
     */
    public static String notFound(String key) {
        return "No data is held under the key '" + key + "' for this caller: it was never "
                + "loaded, was unloaded, or expired an hour after it was last used. Load the "
                + "tables again with 'relix_load'.";
    }

    private static String newKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
