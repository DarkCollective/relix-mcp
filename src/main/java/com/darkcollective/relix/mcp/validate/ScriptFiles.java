package com.darkcollective.relix.mcp.validate;

import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.semantic.ScriptLoader;

import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The files a script imports, sent along with it rather than read from a disk.
 *
 * <p>A script on the caller's machine may {@code import "lib/common.relix"}. This server
 * cannot read that file, and must not read one of its own by the same name, so the
 * caller sends each file's text keyed by the path the {@code import} statement writes.
 * Served through {@code Relix.Builder.scriptLoader}, the engine's seam for where an import
 * comes from.
 *
 * <p>Paths are matched as written, after normalising {@code \} to {@code /} and dropping a
 * leading {@code ./}, so {@code import "./lib.relix"} finds a file sent as
 * {@code lib.relix}.
 */
public final class ScriptFiles implements ScriptLoader {

    private final Map<String, String> files;

    private ScriptFiles(Map<String, String> files) {
        this.files = files;
    }

    /**
     * Serves the given files.
     *
     * @param files path to text; must not be null
     * @return the loader
     */
    public static ScriptFiles of(Map<String, String> files) {
        Map<String, String> normalised = new LinkedHashMap<>();
        Objects.requireNonNull(files, "files").forEach((path, text) ->
                normalised.put(normalise(path), Objects.requireNonNull(text, path)));
        return new ScriptFiles(Map.copyOf(normalised));
    }

    /**
     * {@return the files, keyed by normalised path}
     */
    Map<String, String> files() {
        return files;
    }

    /**
     * Parses the named file.
     *
     * <p>{@link Relix#parse(String, String)} is given the path as the text's origin, so a
     * syntax error inside an imported file is reported against that file.
     */
    @Override
    public Script load(String path) throws NoSuchFileException {
        String text = files.get(normalise(path));
        if (text == null) {
            throw new NoSuchFileException(path, null,
                    "imported but not sent; pass its text in 'files' under this path");
        }
        return Relix.parse(text, path);
    }

    static String normalise(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }
}
