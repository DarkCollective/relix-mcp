package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.CsvFileSourceConfig;

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
            if (statement instanceof SourceDeclaration source
                    && source.config() instanceof CsvFileSourceConfig csv
                    && csv.infersSchema()) {
                errors.add(new Finding(Finding.ERROR, "source '" + source.name()
                        + "' reads a CSV file without a declared schema, and this server "
                        + "opens no file: declare the columns, as in 'schema: { id: NUMBER, "
                        + "name: STRING }', and nothing is read.", file,
                        source.location().line(), source.location().column()));
            }
        }
        return errors;
    }
}
