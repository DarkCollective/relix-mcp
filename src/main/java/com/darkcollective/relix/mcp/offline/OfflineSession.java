package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.embed.Relix;

/**
 * A Relix session that analyses and plans a script without reaching anything it names.
 *
 * <p>The script may declare the caller's own files, databases and HTTP endpoints. The
 * session is an ordinary {@link Relix} session built with three choices, each of which
 * closes one way analysis or planning could reach outside the process:
 *
 * <ol>
 *   <li>{@code catalog(…)}: the caller's description of its connections, which
 *       <em>replaces</em> the default catalog. The default describes a table by asking the
 *       connection, which for a {@code jdbc} URL means connecting to it.</li>
 *   <li>{@code remoteFiles(false)}: a file connection naming an {@code https} URL is an
 *       error rather than a download.</li>
 *   <li>{@code scriptLoader(…)}: an {@code import} is served from what the caller sent,
 *       never from this machine's disk.</li>
 * </ol>
 *
 * <p>What is safe to call on it is what reads no rows: {@link Relix#validate(String)},
 * and on a relation {@code render()}, {@code optimized()} and {@code explain()}, which
 * ask the catalog and the cost model rather than the data. Nothing here calls an
 * execution terminal, and {@code NothingIsOpenedTest} is the check that none of the rest
 * connects, requests or reads.
 */
final class OfflineSession {

    private OfflineSession() {
    }

    /**
     * Opens a session over what the caller sent.
     *
     * @param imports the files the script imports
     * @param catalog the tables of its connections
     * @return the session, which the caller closes
     */
    static Relix open(ScriptFiles imports, CallerCatalog catalog) {
        return Relix.builder()
                .catalog(catalog)
                .remoteFiles(false)
                .scriptLoader(imports)
                .build();
    }
}
