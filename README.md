# relix-mcp

An [MCP](https://modelcontextprotocol.io) server through which a language model checks
[Relix](https://relix.darkcollective.com) scripts before anyone runs them.

A model with nothing to read invents syntax. Given a checker it can close the loop:
write a script, `validate` names the problem with its line and column, fix it, and
validate again.

## Tools

| Tool | What it does |
|---|---|
| `validate(script, files?, catalog?)` | Checks syntax, every relation and column name, types, and each operator's rules. Nothing the script names is opened: not its files, not its databases, not its HTTP endpoints. |
| `learn(topic?)` | The language reference that ships inside the engine: the page for an operator (`σ`, `select`), a statement, or a function (`Round`). With no topic, a list of every page. |

### What `validate` needs from the caller

It never reaches anything a script names, so what the engine would otherwise learn by
looking, the caller says:

- **Sources declare their columns.** A `source … from csv(…) { schema: { … } }` or an
  HTTP source with a `schema:` block is checked against that schema. A JSON file source,
  or an HTTP source without one, accepts any column name; `validate` warns about each
  such source rather than implying a check it did not make.
- **Database tables are described in `catalog`.** A script that reads `wh.orders`
  through `connection wh from jdbc { … }` is checked against the columns the caller
  supplies:

  ```json
  { "wh": { "orders": { "order_id": "NUMBER", "placed_at": "TIMESTAMP" } } }
  ```

  A table not described there is reported as unknown. The types are the ones a
  `schema:` block takes: `NUMBER`, `STRING`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`,
  `DURATION`, `ANY`.
- **Imported files are sent in `files`**, keyed by the path the `import` statement
  writes: `{ "lib/common.relix": "…" }`.

## Running it

Java 21 is required.

```bash
./gradlew installDist
```

That builds `build/install/relix-mcp/bin/relix-mcp`, which speaks MCP over standard
input and output. To add it to Claude Code:

```bash
claude mcp add relix -- "$PWD/build/install/relix-mcp/bin/relix-mcp"
```

Or to Claude Desktop's `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "relix": { "command": "/absolute/path/to/relix-mcp/build/install/relix-mcp/bin/relix-mcp" }
  }
}
```

## Built on the published engine, and nothing else

This server is also an example of embedding Relix, so it uses nothing a program outside
the Relix repositories could not. It depends on one coordinate from Maven Central,
`com.darkcollective.relix:relix`, and reaches it only through the packages that jar's
module descriptor exports. `EngineBoundaryTest` holds it to that: `jdeps` lists every
engine package the compiled classes use, and each must be exported.

The parts worth reading as examples:

- [`ScriptValidator`](src/main/java/com/darkcollective/relix/mcp/validate/ScriptValidator.java):
  a `Relix` session that analyses a script without reaching anything it names. It uses
  a caller-supplied `catalog`, `remoteFiles(false)` and an in-memory `scriptLoader`,
  and then calls `validate`.
- [`CallerCatalog`](src/main/java/com/darkcollective/relix/mcp/validate/CallerCatalog.java):
  a `CatalogProvider` built from a description rather than a database.
- [`ScriptFiles`](src/main/java/com/darkcollective/relix/mcp/validate/ScriptFiles.java):
  a `ScriptLoader` that serves imports from memory.
- [`Reference`](src/main/java/com/darkcollective/relix/mcp/Reference.java): the
  reference pages from `Relix.referencePages()`, and function pages from each
  `FunctionLibrary` found through `ServiceLoader`.

## Building

```bash
./gradlew build
```

runs the tests and Javadoc. The engine version is pinned in `build.gradle`
(`relixVersion`) and can be overridden with `-PrelixVersion=…`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
