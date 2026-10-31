# relix-mcp

An [MCP](https://modelcontextprotocol.io) server through which a language model checks
[Relix](https://relix.darkcollective.com) scripts before anyone runs them.

A model with nothing to read invents syntax. Given a checker it can close the loop:
write a script, `relix_validate` names the problem with its line and column, fix it, and
validate again.

## Tools

| Tool | What it does |
|---|---|
| `relix_validate(script, files?, catalog?, data?)` | Checks syntax, every relation and column name, types, and each operator's rules. Nothing the script names is opened: not its files, not its databases, not its HTTP endpoints. |
| `relix_explain(script, files?, catalog?, data?)` | Shows how the engine would run a script: each rewrite the optimiser applies, the rewritten query, and the physical plan. For a database connection, that includes the exact SQL that would be sent, in the connection's dialect. Like `relix_validate`, it contacts nothing. |
| `relix_run(script, data?)` | Runs a script whose data is its own (inline tables, views, generators) and returns each query's rows. A model can show real results instead of predicted ones. Each column heading carries its type (`ts STRING`), and each result says whether its order is guaranteed (`sorted by spend DESC`, or `in no guaranteed order`), from the order the physical plan delivers. It validates first, so it needs no `relix_validate` call before it. Files, databases, HTTP sources, connections and imports are refused. `query { relix.columns };` shows the columns and types the engine gave the script's own tables. |
| `relix_submit(script, data?)` | The model's final answer: runs the script as `relix_run` does and returns the script and its rows together, in a fixed form the model is told to show unchanged. The rows shown are then the engine's, for exactly that script, and a client or an eval can read the answer from the tool call (`structuredContent` has `submitted`, `script` and `results`) whatever the model writes afterwards. |
| `relix_learn(topic?, detail?)` | The language reference that ships inside the engine: the page for an operator (`σ`, `select`), a statement, or a function (`Round`). With no topic, a list of every page. The whole grammar, as EBNF, is `relix_learn("language/grammar.md")`. With `detail: "brief"`, a page is cut to its syntax and one example, so a conversation does not carry whole pages on every later turn. Given a description instead (`"the three biggest orders of each customer"`), it searches every page and returns the best matches, with the brief pages of the first three. See [Operators by task](#operators-by-task). |
| `relix_load(tables?, key?, read_only?)` | Loads inline tables once and returns a data key, with each table's columns, types and row count. Pass the key as `data` to the four script tools, and their scripts hold only the query. See [Loaded data](#loaded-data). |
| `relix_unload(key)` | Releases a data key before it expires. |

### Operators by task

A model that does not know an operator exists writes a workaround instead of asking for
one: a self-join for the top rows of each group, a recursive query for reachability. So
the server tells it up front. Its instructions, which a client such as Claude Code puts
in the system prompt, and `relix_learn`'s description both carry the reference's page
`operators-by-task.md` whole: each task SQL has no operator for, in a question's words,
with the path of the page of the operator that does it. The page ships with the engine's
reference, so it lists exactly the operators the server runs.

Both also tell the model to start by describing the user's question to `relix_learn`, in
the user's own words. A search returns the list of what it found and, after it, the brief
pages (syntax and one example) of the first three hits, so one call gives the model the
operator's form rather than only its name. `RELIX_MCP_LEARN_PAGES` sets how many (0 to 8;
`0` returns the list alone, as an evaluation measuring the catalogue alone does).

### What `relix_validate` and `relix_explain` need from the caller

It never reaches anything a script names, so what the engine would otherwise learn by
looking, the caller says:

- **Sources declare their columns.** A `source … from csv(…) { schema: { … } }` or an
  HTTP source with a `schema:` block is checked against that schema. A JSON file source,
  or an HTTP source without one, accepts any column name; `relix_validate` warns about each
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

### What `relix_run` allows

`relix_run` executes in a closed `Sandbox` that permits no external declarations. A sandbox
always accepts what reads nothing outside the session, so a script can compute over the
data it writes out and can reach nothing else. The sandbox's limits bound each call: at
most 200 rows per query (a longer result is cut, and says so; `RELIX_MCP_MAX_OUTPUT_ROWS`
sets another cap, as an evaluation's grading server does), 10 seconds per query, and
caps on buffered rows, recursion rounds and rows passed between operators. On Lambda the
per-query time is configurable, and a deadline bounds the whole call (below).

### Loaded data

`relix_load` takes inline-table declarations, and nothing else, and holds them under a
random 128-bit data key, so a model (or the client in front of it) states its data once
instead of retyping it into every call. A script given `data` reads those tables without
declaring them:

- The tables go into the session as parsed statements, never pasted in front of the
  script, so a finding in the script is on the script's own line.
- A declaration in the script of a loaded table is ignored, with a warning: it is most
  likely a copy, and perhaps a wrong one.
- The data's warnings are listed in full only by `relix_load`. Later results carry one
  line counting them, in their own field, `dataWarnings`.
- With `key`, `relix_load` adds tables to that key, replacing any of the same name. With
  `key` alone it describes what the key holds. A key loaded with `read_only` can never be
  replaced or added to, by any caller holding it; a client that loads data for a model
  to read should load it that way.

A key lasts an hour from its last use, holds at most 400,000 bytes, and is found only by
the caller that loaded it: over stdio or loopback HTTP that is this machine; on Lambda,
the API key the request carried (stored as its SHA-256, never the key). A caller may hold
20 keys at once, or what `RELIX_MCP_MAX_DATA_KEYS` says; at the limit a load is refused
rather than evicting a key another conversation may be using. A server bound to any other
address cannot tell its callers apart, and offers no `relix_load`.

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
java -jar build/libs/relix-mcp-1.0.0-rc1-all.jar --http
```

The server, the engine and the SDK in one jar, which is what a function platform
deploys. The engine finds its function library and connectors through `ServiceLoader`,
so the jar concatenates every `META-INF/services` file rather than keeping the first,
and `FatJarTest` (run by `./gradlew fatJarTest`, part of `check`) starts it and calls
each tool, since a provider lost in merging fails nothing earlier.

### On AWS Lambda, as a private endpoint

The same jar is a Lambda function. Its handler,
[`LambdaHandler`](src/main/java/com/darkcollective/relix/mcp/aws/LambdaHandler.java),
takes payload format 2.0 events, which is what a Function URL sends, and answers at
`/mcp`, or at the path `RELIX_MCP_PATH` gives (behind a CDN that forwards its own path,
such as `/mcp/private`, that path):

| Setting | Value |
|---|---|
| Runtime | `java21` |
| Handler | `com.darkcollective.relix.mcp.aws.LambdaHandler::handleRequest` |
| Code | `build/libs/relix-mcp-<version>-all.jar` |
| Layer | AWS Parameters and Secrets Lambda Extension |
| Front door | a Function URL with auth type `NONE`: the handler checks the key |

Every request must carry the API key in an `X-Api-Key` header, or it is refused with
401 before any MCP processing. The key is read once, when the function starts, through
the Parameters and Secrets extension, from whichever of these the environment names
(the function's role needs read access to it, and `kms:Decrypt` for its key):

- `RELIX_MCP_API_KEY_PARAMETER`: a Parameter Store `SecureString`, or
- `RELIX_MCP_API_KEY_SECRET`: a Secrets Manager secret.

With neither, the function does not start; it never serves without a key. Give it a
reserved-concurrency cap too: the key limits who can call it, and the cap limits what a
leaked key can cost.

**Loaded data.** `relix_load` needs a store every instance shares, so on Lambda it is
offered only when `RELIX_MCP_DATA_TABLE` names a DynamoDB table in the function's region,
shaped as follows:

| Attribute | Type | Role |
|---|---|---|
| `owner_id` | string | partition key: the SHA-256 of the API key that loaded the data |
| `data_key` | string | sort key: the data key |
| `expires_at` | number | epoch seconds; the table's TTL attribute |

The function's role needs `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `DeleteItem` and
`Query` on the table, and nothing else. DynamoDB deletes an expired item late, so the
server compares `expires_at` with the clock on every read and count; the TTL is
housekeeping. The requests are signed by the server itself (`SigV4`), so the jar carries
no AWS SDK. `./gradlew test -PdynamodbLocal=http://localhost:8000` also runs the store's
tests against DynamoDB Local.

A client connects with the key as a header:

```bash
claude mcp add --transport http relix https://<url-id>.lambda-url.<region>.on.aws/mcp --header "X-Api-Key: <key>"
```

**Time.** The function's own timeout is the limit, and raising it is a configuration
change only. The handler stops a call two seconds inside it, so a script that runs too
long is answered with an error saying it was stopped at the limit, followed by the
queries that finished first, rather than cut off by the platform with a bare 5xx. The
one exception is work that never passes a row between two operators, such as a solver's
search in `OPTIMIZE` or `COVER EXACT`: it does not notice being stopped, so the call
answers without what it was doing. One query may also run for
`RELIX_MCP_QUERY_TIMEOUT_SECONDS` (10 by default; `0` leaves only the limit on the whole
call). Prefer a Function URL to an API Gateway HTTP API, which caps an integration at
about 30 seconds whatever the function allows.

**SnapStart.** With SnapStart on, Lambda starts the function once, when a version is
published, and every instance from a snapshot of it, so a cold start skips the JVM's
start and the engine's first use. Under SnapStart the handler calls each tool once before
the snapshot is taken, and a version whose warm-up fails is not published. To use it:

- set SnapStart to apply on published versions; it never applies to `$LATEST`;
- deploy by updating the code, publishing a version, and pointing an alias at it;
- put the Function URL on the alias, not on the function. That URL has a new host, so a
  CDN in front of it needs its origin changed.

The API key is read when the snapshot is taken and travels in it, so a new key takes
effect when a new version is published. SnapStart sets no `AWS_SESSION_TOKEN`; the
handler takes the session token the Parameters and Secrets extension wants from the
container credentials endpoint instead.

## Built on the published engine, and nothing else

This server is also an example of embedding Relix, so it uses nothing a program outside
the Relix repositories could not. It depends on one coordinate from Maven Central,
`com.darkcollective.relix:relix`, and reaches it only through the packages that jar's
module descriptor exports. A release pins a released engine; the `develop` branch builds
against the engine's snapshot, so an engine change is tried here before it ships. `EngineBoundaryTest` holds it to that: `jdeps` lists every
engine package the compiled classes use, and each must be exported.

The parts worth reading as examples:

- [`OfflineSession`](src/main/java/com/darkcollective/relix/mcp/offline/OfflineSession.java):
  a `Relix` session that analyses and plans a script without reaching anything it names:
  a caller-supplied `catalog`, `remoteFiles(false)` and an in-memory `scriptLoader`.
- [`ScriptValidator`](src/main/java/com/darkcollective/relix/mcp/offline/ScriptValidator.java):
  `Relix.validate`, with its diagnostics turned into placed findings.
- [`ScriptExplainer`](src/main/java/com/darkcollective/relix/mcp/offline/ScriptExplainer.java):
  `render()`, `optimized()`, `rewrites()` and `relix_explain()`, the inspection terminals, none
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
