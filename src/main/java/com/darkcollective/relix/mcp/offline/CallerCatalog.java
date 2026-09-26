package com.darkcollective.relix.mcp.offline;

import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The tables of the caller's own connections, as the caller describes them.
 *
 * <p>A script that reads {@code wh.orders} from a database is checked against columns
 * only the database knows. A live session would connect and ask it; this server must
 * not, so the caller says instead, in the shape a model can write from what it already
 * has in front of it:
 *
 * {@snippet lang = "json":
 * {
 *   "wh": {
 *     "orders":    { "order_id": "NUMBER", "customer_id": "NUMBER", "placed_at": "TIMESTAMP" },
 *     "customers": { "customer_id": "NUMBER", "name": "STRING" }
 *   }
 * }
 * }
 *
 * <p>This is the <em>only</em> catalog the session sees. It is handed to
 * {@code Relix.Builder.catalog}, which replaces live introspection rather than sitting in
 * front of it, so a table the caller did not describe is unknown rather than looked up.
 * That is what keeps validation from opening the URL a script names.
 *
 * <p>Connection and table names match case-insensitively, as every name in Relix does.
 */
public final class CallerCatalog implements CatalogProvider {

    /** The column types a schema may name: the same words a {@code schema:} block takes. */
    static final String TYPE_NAMES = Arrays.stream(ScalarType.values())
            .map(Enum::name).collect(Collectors.joining(", "));

    private static final CallerCatalog EMPTY = new CallerCatalog(Map.of());

    /** Connection, then table, both lower-cased, to the table's heading. */
    private final Map<String, Map<String, Schema>> tables;

    private CallerCatalog(Map<String, Map<String, Schema>> tables) {
        this.tables = tables;
    }

    /**
     * {@return a catalog describing no tables at all}
     */
    public static CallerCatalog empty() {
        return EMPTY;
    }

    /**
     * Reads a catalog from its JSON shape, already decoded to maps.
     *
     * @param description connection name to table name to column name to type name;
     *                    column order is kept, so pass an ordered map
     * @return the catalog
     * @throws IllegalArgumentException if a level is not an object, or a type is not one
     *                                  Relix has
     */
    public static CallerCatalog of(Map<String, ?> description) {
        Objects.requireNonNull(description, "description");
        Map<String, Map<String, Schema>> tables = new LinkedHashMap<>();
        for (var connection : description.entrySet()) {
            Map<String, Schema> byTable = new LinkedHashMap<>();
            for (var table : object(connection.getValue(),
                    "connection '" + connection.getKey() + "'").entrySet()) {
                String where = connection.getKey() + "." + table.getKey();
                byTable.put(fold(table.getKey()), schema(object(table.getValue(), where), where));
            }
            tables.put(fold(connection.getKey()), byTable);
        }
        return new CallerCatalog(tables);
    }

    @Override
    public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
        return Optional.ofNullable(tables.getOrDefault(fold(connection.name()), Map.of())
                .get(fold(table)));
    }

    /**
     * {@return whether the caller described this connection at all}
     *
     * @param connection the connection's name
     */
    boolean describes(String connection) {
        return tables.containsKey(fold(connection));
    }

    private static Schema schema(Map<String, ?> columns, String where) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("catalog: table " + where + " has no columns");
        }
        List<ColumnDefinition> definitions = new ArrayList<>();
        for (var column : columns.entrySet()) {
            if (!(column.getValue() instanceof String type)) {
                throw new IllegalArgumentException("catalog: column " + where + "."
                        + column.getKey() + " must name a type as a string, one of "
                        + TYPE_NAMES);
            }
            definitions.add(new ColumnDefinition(column.getKey(), type(type, where, column.getKey())));
        }
        return new Schema(definitions);
    }

    private static ScalarType type(String name, String where, String column) {
        try {
            return ScalarType.fromString(name.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("catalog: column " + where + "." + column
                    + " has type '" + name + "', which is not one of " + TYPE_NAMES, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> object(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, ?>) map;
        }
        throw new IllegalArgumentException("catalog: " + where + " must be a JSON object");
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
