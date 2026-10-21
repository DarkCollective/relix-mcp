# Model eval

Runs held-out natural-language questions through a local model that answers by
calling this server's tools, and grades each answer by the rows it returned.

It exists to find what to change when a model fails: the system prompt, this
server's descriptions, primer or `relix_learn` pages, or an engine error message
in relix-core. It is not part of the Gradle build.

## What it uses

- **Questions:** the relix-ask held-out suites in the relix repository,
  `docs/llm/<suite>/` (`data.relix` and `queries.json`, with a gold query per
  question).
- **Model:** any model LM Studio serves through its OpenAI-compatible API,
  `http://localhost:1234/v1` by default.
- **Server:** this repository's launcher (`./gradlew installDist`), started over
  stdio once per worker, so the tools are the ones on this branch and the engine
  is whatever `relixVersion` resolves to.
- **Data:** loaded once per server and suite with `relix_load`; the model is given
  the data key and each table's columns, not the rows (`--data key`, the default).
  The data is loaded read-only, and the model is not offered `relix_load` or
  `relix_unload`: the data is the harness's, and one model once replaced a suite
  table under the harness's key, for every later question on the same server.
  `--data inline` puts the declarations in the question instead, for the model to
  copy into every script, as before `relix_load` existed; `home-automation`'s data
  (about 31,000 tokens) is too large for that.
- **System prompt:** `system-prompt.txt` with a data key, `system-prompt-inline.txt`
  with `--data inline`, or `--prompt`.

## Running

```bash
./gradlew installDist
python3 tools/model-eval/run_eval.py --suites-dir ../relix/docs/llm \
    --suite ecommerce --suite issue-tracker --per-category 3 --workers 4
```

`--per-category N` takes N questions from each category of each suite, chosen at
random with `--seed`, among those whose gold query returns rows. `--only
ecommerce:3,issue-tracker:17` reruns particular questions.

## NOW()

A suite's data is built relative to an instant (`RELIX_SUITE_NOW`, relix's #451), so
"the last 7 days" holds the rows its question was written for only when `NOW()` is that
instant. Each run therefore **stages** the suites: it rebuilds each one with its own
`build_suite.py` at one instant, `--now` or the current time, into the run's
directory, and starts every server, the one that grades the gold queries included,
with `RELIX_MCP_NOW` set to it. The questions and gold queries are the builders' own,
unchanged; only the data's dates move. Pass the same `--now` to repeat a run exactly.
`--no-stage` uses the suites as committed, with the real clock.

`RELIX_MCP_NOW` is read by the server itself (`SessionClock`): an ISO-8601 instant fixes
every session's `NOW()` there. It is for tests and evaluations; unset, the server uses
the system clock.

## The model

Load the model with enough context before a run; the harness refuses to start below
`--min-context` (32,768 by default), because a model loaded by its first API request gets
LM Studio's default (8,192 for gpt-oss-20b) and loses the start of the conversation:

```bash
~/.lmstudio/bin/lms load openai/gpt-oss-20b --context-length 65536 --parallel 1
```

One worker is the default: two parallel requests at that context crashed gpt-oss-20b on a
48 GB Mac.

Each run writes `runs/<timestamp>/`:

- `summary.md`: pass rate, results by category, failed tool calls grouped by
  error, and every question that didn't pass with its gold, its submission and
  its errors;
- `results.jsonl`: one record per question;
- `transcripts/`: every message, tool call and result.

## Grading

A question passes (`match`) when the rows of one of the queries `relix_submit` ran
equal the gold query's rows over the same data, as a multiset. Any one of them: a
model asked for "all details" of something often adds a query per related table. A question
that leaves a choice open, such as sample or population variance, lists a gold query for each
reading it allows in `also_accepted`, and the rows of any of them pass. Columns are matched by their
values, so the model's column names and order don't matter. `extra_columns`
means the gold's rows were all there with more columns than asked for;
`mismatch` means different rows; `data_altered` means different rows from a script whose copy of a table it reads differs from the data given (the model retyped it), kept apart from a wrong query; it arises only with `--data inline`, since over a data key the server ignores a table the script declares again; `right_not_submitted` means the model never submitted, but one of its `relix_run` calls returned the right rows (the summary says which call), so it solved the question and failed only to finish; `no_submit` means it never submitted and no run of it was right. The summary's "right rows reached" counts `match`, `extra_columns` and `right_not_submitted`.
Row order isn't graded.
