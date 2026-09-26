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
| `explain(script, files?, catalog?)` | Shows how the engine would run a script: each rewrite the optimiser applies, the rewritten query, and the physical plan. For a database connection, that includes the exact SQL that would be sent, in the connection's dialect. Like `validate`, it contacts nothing. |
| `run(script)` | Runs a script whose data is its own (inline tables, views, generators) and returns each query's rows. A model can show real results instead of predicted ones. Files, databases, HTTP sources, connections and imports are refused. `query { relix.columns };` shows the columns and types the engine gave the script's own tables. |
| `learn(topic?)` | The language reference that ships inside the engine: the page for an operator (`σ`, `select`), a statement, or a function (`Round`). With no topic, a list of every page. |

### What `validate` and `explain` need from the caller

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

### What `run` allows

`run` executes in a closed `Sandbox` that permits no external declarations. A sandbox
always accepts what reads nothing outside the session, so a script can compute over the
data it writes out and can reach nothing else. The sandbox's limits bound each call: at
most 200 rows per query (a longer result is cut, and says so), 10 seconds per query, and
caps on buffered rows, recursion rounds and rows passed between operators.

## Running it

Java 21 is required.

```bash
./gradlew installDist
```

That builds `build/install/relix-mcp/bin/relix-mcp`, which by default speaks MCP over
standard input and output. To add it to Claude Code:

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

### Over HTTP

The same server speaks MCP's Streamable HTTP transport, statelessly: every request is
one POST carrying one JSON-RPC message, answered in the response body as
`application/json`, with no session and no event stream.

```bash
build/install/relix-mcp/bin/relix-mcp --http              # http://127.0.0.1:8080/mcp
build/install/relix-mcp/bin/relix-mcp --http --port 9000
claude mcp add --transport http relix http://127.0.0.1:8080/mcp
```

On a loopback address, which is the default, a request whose `Origin` or `Host` names
anything but this machine is refused, which stops a web page from reaching the server
through DNS rebinding. `--host 0.0.0.0` listens on every interface and checks neither
header, so put it behind something that does. A request body may be at most 1 MiB.

[`StatelessHttpTransport`](src/main/java/com/darkcollective/relix/mcp/http/StatelessHttpTransport.java)
is a plain function from a request to a response and knows no HTTP server;
[`LocalHttpServer`](src/main/java/com/darkcollective/relix/mcp/http/LocalHttpServer.java)
serves it with the JDK's own, and a serverless handler calls the same function.

### As one jar

```bash
./gradlew shadowJar
java -jar build/libs/relix-mcp-0.1.0-SNAPSHOT-all.jar --http
```

The server, the engine and the SDK in one jar, which is what a function platform
deploys. The engine finds its function library and connectors through `ServiceLoader`,
so the jar concatenates every `META-INF/services` file rather than keeping the first,
and `FatJarTest` (run by `./gradlew fatJarTest`, part of `check`) starts it and calls
each tool, since a provider lost in merging fails nothing earlier.

## Built on the published engine, and nothing else

This server is also an example of embedding Relix, so it uses nothing a program outside
the Relix repositories could not. It depends on one coordinate from Maven Central,
`com.darkcollective.relix:relix`, and reaches it only through the packages that jar's
module descriptor exports. `EngineBoundaryTest` holds it to that: `jdeps` lists every
engine package the compiled classes use, and each must be exported.

The parts worth reading as examples:

- [`OfflineSession`](src/main/java/com/darkcollective/relix/mcp/offline/OfflineSession.java):
  a `Relix` session that analyses and plans a script without reaching anything it names:
  a caller-supplied `catalog`, `remoteFiles(false)` and an in-memory `scriptLoader`.
- [`ScriptValidator`](src/main/java/com/darkcollective/relix/mcp/offline/ScriptValidator.java):
  `Relix.validate`, with its diagnostics turned into placed findings.
- [`ScriptExplainer`](src/main/java/com/darkcollective/relix/mcp/offline/ScriptExplainer.java):
  `render()`, `optimized()`, `rewrites()` and `explain()`, the inspection terminals, none
  of which reads a row.
- [`CallerCatalog`](src/main/java/com/darkcollective/relix/mcp/offline/CallerCatalog.java):
  a `CatalogProvider` built from a description rather than a database.
- [`ScriptFiles`](src/main/java/com/darkcollective/relix/mcp/offline/ScriptFiles.java):
  a `ScriptLoader` that serves imports from memory.
- [`ScriptRunner`](src/main/java/com/darkcollective/relix/mcp/run/ScriptRunner.java):
  running untrusted text safely with `Sandbox.builder()`, and reading the rows back
  through `Relation.run()`.
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
