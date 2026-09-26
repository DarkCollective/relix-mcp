package com.darkcollective.relix.mcp.run;

import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.embed.Tuple;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one {@code query} statement produced: its rows, or what stopped it.
 *
 * @param name      the view the query names, or null for an expression in braces
 * @param columns   the column names, in order; empty when the query failed
 * @param rows      each row's values, in column order: a number, a boolean, null, or
 *                  the engine's display text for anything else
 * @param truncated whether the result was cut at the row limit
 * @param failure   what stopped the query, or null when it ran
 */
public record QueryResult(String name, List<String> columns, List<List<Object>> rows,
                          boolean truncated, String failure) {

    /**
     * Copies the lists.
     */
    public QueryResult {
        columns = List.copyOf(columns);
        rows = rows.stream().map(r -> Collections.unmodifiableList(new ArrayList<>(r))).toList();
    }

    static QueryResult of(String name, Rows result) {
        List<String> columns = result.schema().columns().stream()
                .map(ColumnDefinition::name).toList();
        List<List<Object>> rows = new ArrayList<>();
        for (Tuple tuple : result) {
            List<Object> row = new ArrayList<>(columns.size());
            for (int i = 0; i < tuple.width(); i++) {
                row.add(plain(tuple.get(i)));
            }
            rows.add(row);
        }
        return new QueryResult(name, columns, rows, result.truncated(), null);
    }

    static QueryResult failed(String name, String failure) {
        return new QueryResult(name, List.of(), List.of(), false, failure);
    }

    /**
     * {@return whether the query ran}
     */
    public boolean ran() {
        return failure == null;
    }

    /** A value as JSON has it: numbers and booleans as themselves, the rest as text. */
    private static Object plain(Value value) {
        if (value.isNull()) {
            return null;
        }
        return switch (value) {
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            default -> value.asDisplayString();
        };
    }
}
