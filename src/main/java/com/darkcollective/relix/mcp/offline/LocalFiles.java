package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.DatabaseSourceConfig;
import com.darkcollective.relix.lang.ast.source.GeneratorSourceConfig;
import com.darkcollective.relix.lang.ast.source.HttpSourceConfig;
import com.darkcollective.relix.lang.ast.source.JsonFileSourceConfig;
import com.darkcollective.relix.lang.ast.source.SourceConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses the one way analysis reads a file on this machine.
 *
 * <p>A {@code csv} source with no {@code schema:} has its columns inferred from the
 * file's first line while the script is analysed, and the engine's builder has no switch
 * that forbids a local read (only {@code remoteFiles(false)} for URLs). The read would
 * answer the caller with the file's first line, in an error that names a column, and a
 * missing file with a path on this server. So such a source is refused before a session is
 * opened, and the caller is told to declare the schema, which is also all a model needs to
 * do: the declaration says the columns, so no file is consulted.
 *
 * <p>The check is on the parsed script, and runs over the script and over every file sent
 * for it to import, the only other text the engine analyses. Text that does not parse is
 * left to the engine to report, which reads nothing before it has parsed.
 */
final class LocalFiles {

    private LocalFiles() {
    }

    /**
     * The errors for each source in the text that would read a local file to find its
     * columns.
     *
     * @param text the script or imported file
     * @param file the path the text was sent under, or null for the script itself
     * @return an error per such source; empty when there is none
     */
    static List<Finding> refusals(String text, String file) {
        Script parsed;
        try {
            parsed = Relix.parse(text, file == null ? "script" : file);
        } catch (ScriptParseException e) {
            return List.of();
        }
        List<Finding> errors = new ArrayList<>();
        for (Statement statement : parsed.statements()) {
            if (statement instanceof SourceDeclaration source && readsLocalFile(source.config())) {
                errors.add(new Finding(Finding.ERROR, "source '" + source.name()
                        + "' reads a CSV file without a declared schema, and this server "
                        + "opens no file: declare the columns, as in 'schema: { id: NUMBER, "
                        + "name: STRING }', and nothing is read.", file,
                        source.location().line(), source.location().column()));
            }
        }
        return errors;
    }

    /**
     * Whether analysing a source would open a file on this machine.
     *
     * <p>Exhaustive over the sealed hierarchy with no {@code default}, so a new kind of
     * source fails to compile here until someone decides, and proves with a test in
     * {@code NothingIsOpenedTest}, which side of the line it is on. Every kind that is
     * allowed was shown to open nothing while analysing, with a named pipe standing in for
     * the file, which blocks the reader that opens it.
     */
    private static boolean readsLocalFile(SourceConfig config) {
        return switch (config) {
            // Types, and for a headerless file the columns, are read from the file.
            case CsvFileSourceConfig csv -> csv.infersSchema();
            // Open by design: its columns are found per record at run time, never in analysis.
            case JsonFileSourceConfig json -> false;
            // A url is fetched at run time; analysis reads the declared schema or none.
            case HttpSourceConfig http -> false;
            // Needs a schema: of its own (the analyser refuses one without), and the
            // caller's catalog replaces the connection's own description of its tables.
            case ConnectionTableSourceConfig table -> false;
            case DatabaseSourceConfig database -> false;
            // Rows are made, not read; its schema is the generator's.
            case GeneratorSourceConfig generator -> false;
        };
    }
}
