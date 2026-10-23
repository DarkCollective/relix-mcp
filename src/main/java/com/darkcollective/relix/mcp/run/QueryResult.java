package com.darkcollective.relix.mcp.run;

import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.embed.Relation;
import com.darkcollective.relix.embed.Rows;
import com.darkcollective.relix.embed.Tuple;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one {@code query} statement produced: its rows, or what stopped it.
 *
 * @param name      the view the query names, or null for an expression in braces
 * @param columns   the column names, in order; empty when the query failed. For a
 *                  query whose schema is open, such as a {@code PIVOT}, the names its
 *                  rows carry, in the order first seen
 * @param types     each column's type, in the same order: a scalar as the script spells
 *                  it ({@code NUMBER}, {@code STRING}, ...), anything else as the engine
 *                  displays it. For an open schema, the type the column's values share,
 *                  or {@code ANY}
 * @param ordering  the order the rows are guaranteed to come in, one sort key each
 *                  ({@code spend DESC}); empty when no order is guaranteed
 * @param rows      each row's values, in column order: a number, a boolean, null, or
 *                  the engine's display text for anything else. A number a double cannot
 *                  hold exactly is its exact decimal text instead, typed NUMBER in
 *                  {@code types}
 * @param truncated whether the result was cut at the row limit
 * @param failure   what stopped the query, or null when it ran
 */
public record QueryResult(String name, List<String> columns, List<String> types,
                          List<String> ordering, List<List<Object>> rows,
                          boolean truncated, String failure) {

    /**
     * Copies the lists.
     */
    public QueryResult {
        columns = List.copyOf(columns);
        types = List.copyOf(types);
        ordering = List.copyOf(ordering);
        rows = rows.stream().map(r -> Collections.unmodifiableList(new ArrayList<>(r))).toList();
    }

    /**
     * What a query returned.
     *
     * <p>The order is the one the query's physical plan says it
     * {@linkplain com.darkcollective.relix.plan.PhysicalNode#deliveredOrdering() delivers}:
     * established by a sort and carried by the operators that keep it, and none otherwise.
     * Rows that merely happen to arrive sorted are not reported as sorted.
     *
     * @param name     the view the query names, or null
     * @param query    the query that ran
     * @param result   its rows
     */
    static QueryResult of(String name, Relation query, Rows result) {
        if (result.schema().isOpen()) {
            return ofOpen(name, query, result);
        }
        List<String> columns = result.schema().columns().stream()
                .map(ColumnDefinition::name).toList();
        List<String> types = result.schema().columns().stream()
                .map(c -> spelling(c.type())).toList();
        List<List<Object>> rows = new ArrayList<>();
        for (Tuple tuple : result) {
            List<Object> row = new ArrayList<>(columns.size());
            for (int i = 0; i < tuple.width(); i++) {
                row.add(plain(tuple.get(i)));
            }
            rows.add(row);
        }
        return new QueryResult(name, columns, types, ordering(query), rows, result.truncated(), null);
    }

    /**
     * What a query whose schema is open returned, such as a {@code PIVOT}: its columns
     * are known only once its rows exist. They are the names its rows carry, in the order
     * first seen, and a row without one of them has NULL there. A column's type is the one
     * its values share, or {@code ANY} when they share none or are all NULL.
     */
    private static QueryResult ofOpen(String name, Relation query, Rows result) {
        Map<String, ScalarType> seen = new LinkedHashMap<>();
        for (Tuple tuple : result) {
            for (String column : tuple.columnNames()) {
                Value value = tuple.get(column);
                ScalarType type = value.isNull() ? null : value.type();
                if (!seen.containsKey(column)) {
                    seen.put(column, type);
                } else if (type != null && seen.get(column) != type) {
                    seen.put(column, seen.get(column) == null ? type : ScalarType.ANY);
                }
            }
        }
        List<String> columns = List.copyOf(seen.keySet());
        List<String> types = seen.values().stream()
                .map(t -> spelling(t == null ? ScalarType.ANY : t)).toList();
        List<List<Object>> rows = new ArrayList<>();
        for (Tuple tuple : result) {
            List<String> has = tuple.columnNames();
            List<Object> row = new ArrayList<>(columns.size());
            for (String column : columns) {
                row.add(has.contains(column) ? plain(tuple.get(column)) : null);
            }
            rows.add(row);
        }
        return new QueryResult(name, columns, types, ordering(query), rows, result.truncated(), null);
    }

    private static List<String> ordering(Relation query) {
        return query.plan().plan().deliveredOrdering().keys().stream()
                .map(QueryResult::spelling).toList();
    }

    static QueryResult failed(String name, String failure) {
        return new QueryResult(name, List.of(), List.of(), List.of(), List.of(), false, failure);
    }

    /**
     * {@return a type as a script declares it: {@code STRING}, not the engine's {@code string}}
     *
     * @param type a column's type
     */
    public static String spelling(Type type) {
        return type instanceof ScalarType scalar ? scalar.name() : type.display();
    }

    /**
     * A sort key as a script writes it, when it is a column. The engine offers no public
     * way to print an expression key, so one is named for what it is.
     */
    private static String spelling(SortSpecification key) {
        return key.columnName().orElse("an expression") + " " + key.direction().name();
    }

    /**
     * {@return whether the query ran}
     */
    public boolean ran() {
        return failure == null;
    }

    /**
     * A value as JSON has it: numbers and booleans as themselves, the rest as text. A
     * number is written as its exact decimal text when a client that reads JSON numbers
     * as doubles, as JavaScript does, would read a different number.
     */
    private static Object plain(Value value) {
        if (value.isNull()) {
            return null;
        }
        return switch (value) {
            case NumberValue number -> readsExactly(number.value())
                    ? number.value() : number.value().toPlainString();
            case BooleanValue bool -> bool.value();
            default -> value.asDisplayString();
        };
    }

    /** {@return whether {@code n}, read as the nearest double, prints back as {@code n}} */
    private static boolean readsExactly(BigDecimal n) {
        double d = n.doubleValue();
        return Double.isFinite(d) && BigDecimal.valueOf(d).compareTo(n) == 0;
    }
}
