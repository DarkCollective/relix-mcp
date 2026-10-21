# relix-mcp — Project Context for Claude

An MCP server through which a language model validates and explains Relix scripts, runs
them over their own inline data, and reads the language reference. The Relix engine is **not** here: it is
`com.darkcollective.relix:relix` from Maven Central (its snapshot on `develop`), published from
[relix-core](https://github.com/DarkCollective/relix-core).

## Hard rules

- **Java 21** toolchain, as the engine. No preview features.
- **The engine through its published API only.** Depend on the published jar and nothing
  else of Relix; use only packages its module descriptor exports. `EngineBoundaryTest`
  (jdeps) enforces it, because the MCP SDK's invalid automatic module name keeps this
  build on the class path. A gap in the public API is fixed in relix-core and published,
  then used here. It is never worked around with internals, reflection or copied code.
- **develop tracks the engine snapshot; a release pins a released engine.** On `develop`,
  `relixVersion` defaults to the engine's `-SNAPSHOT`, from Central's snapshot repository,
  so a change merged to relix-core's develop is used here before it is released. Promoting
  to `main` sets the default to a released engine (a release or release candidate) in the
  same PR; CI runs `engineIsReleased` on every PR into `main` and push to it, and fails on
  a snapshot. Try a released engine on develop with `-PrelixVersion=<version>`.
- **No magic knowledge.** This server is a reference for embedders, so it must not rely
  on engine behaviour that is not documented API: no parsing of message text, no
  internal constants such as the name the engine gives a session's script.
- **The offline tools open nothing.** `relix_validate` and `relix_explain` share one
  `OfflineSession`, which always passes a catalog, so live introspection is never
  constructed, and they call only terminals that read no rows. `NothingIsOpenedTest`
  counts connections, requests and file reads for both.
- **`relix_run` executes only inside a closed sandbox with no declarations.** It accepts inline
  tables, views, functions and generators, and refuses every file, database, HTTP source,
  connection and import. Never widen it with an open session. Data from `relix_load` is
  inline tables only (`LoadedData.of` refuses anything else), defined in the session as
  parsed statements, never pasted in front of the script.
- **Loaded data lives only behind `DataStore`**, under an owner the `Authenticator` names
  (a hash, never a credential). A lookup under another owner finds nothing.
- **Tools are transport-neutral.** A tool is a `Tools.Definition` (a `Tool` and a
  function from request to result); `Main` adapts it to the SDK's stdio and stateless
  specifications. `StatelessHttpTransport` is a function from an HTTP request to a
  response and must stay free of any HTTP server, because a serverless handler calls it
  too.
- **The fat jar is tested by running it.** `fatJarTest` (in `check`) starts
  `shadowJar`'s output with `java -jar` and calls each tool; keep a call there for every
  provider or resource the engine discovers at run time.
- **Every primer example validates** (`ToolsTest`). A wrong example in text meant for
  a model to imitate is copied into every script written from it.

## Git workflow

Branch from `develop` (`feat/`, `fix/`, `docs/`, `chore/`), open the PR against
`develop` with `gh pr create --base develop`, and never push to `main` or `develop`
directly. If `gh` is unauthenticated, set `GH_TOKEN` from `~/.gh-token`.
Run `./gradlew clean build` before calling work done.
