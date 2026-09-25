package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * What each {@code query} statement in a script is called.
 */
public final class QueryNames {

    private QueryNames() {
    }

    /**
     * The view each {@code query} statement names, or null for one written as an
     * expression in braces, in source order: the order {@link Relix#script} returns its
     * relations in.
     *
     * <p>Read from the parsed statements rather than from a relation's label, which for an
     * expression is text the engine makes up.
     *
     * @param script a script that parses
     * @return one entry per query statement
     */
    public static List<String> of(String script) {
        List<String> names = new ArrayList<>();
        for (Statement statement : Relix.parse(script).statements()) {
            if (statement instanceof QueryStatement query) {
                names.add(query.target() instanceof NamedQueryTarget named ? named.name() : null);
            }
        }
        return names;
    }
}
