# relix-mcp — Project Context for Claude

An MCP server through which a language model validates Relix scripts, and reads the
language reference. The Relix engine is **not** here: it is
`com.darkcollective.relix:relix` from Maven Central, released from
[relix-core](https://github.com/DarkCollective/relix-core).

## Hard rules

- **Java 21** toolchain, as the engine. No preview features.
- **The engine through its published API only.** Depend on the released jar and nothing
  else of Relix; use only packages its module descriptor exports. `EngineBoundaryTest`
  (jdeps) enforces it, because the MCP SDK's invalid automatic module name keeps this
  build on the class path. A gap in the public API is fixed in relix-core and released,
  then used here. It is never worked around with internals, reflection or copied code.
- **No magic knowledge.** This server is a reference for embedders, so it must not rely
  on engine behaviour that is not documented API: no parsing of message text, no
  internal constants such as the name the engine gives a session's script.
- **Validation opens nothing.** `NothingIsOpenedTest` counts connections, requests and
  file reads. Every session built for validation passes a catalog, so live
  introspection is never constructed.
- **Every primer example validates** (`ToolsTest`). A wrong example in text meant for
  a model to imitate is copied into every script written from it.

## Git workflow

Branch from `develop` (`feat/`, `fix/`, `docs/`, `chore/`), open the PR against
`develop` with `gh pr create --base develop`, and never push to `main` or `develop`
directly. If `gh` is unauthenticated, set `GH_TOKEN` from `~/.gh-token`.
Run `./gradlew clean build` before calling work done.
