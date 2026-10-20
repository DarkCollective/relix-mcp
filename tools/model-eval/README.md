# Model eval

Runs held-out natural-language questions through a local model that answers by
calling this server's tools, and grades each answer by the rows it returned.

It exists to find what to change when a model fails: the system prompt, this
server's descriptions, primer or `relix_learn` pages, or an engine error message
in relix-core. It is not part of the Gradle build.

## What it uses

- **Questions:** the relix-ask held-out suites in the relix repository,
  `docs/llm/<suite>/` (`data.relix` and `queries.json`, with a gold query per
  question). `ecommerce` and `issue-tracker` fit a model's context;
  `home-automation`'s data is too large to repeat in every tool call.
- **Model:** any model LM Studio serves through its OpenAI-compatible API,
  `http://localhost:1234/v1` by default.
- **Server:** this repository's launcher (`./gradlew installDist`), started over
  stdio once per worker, so the tools are the ones on this branch and the engine
  is whatever `relixVersion` resolves to.
- **System prompt:** `system-prompt.txt`, or `--prompt`.

## Running

```bash
./gradlew installDist
python3 tools/model-eval/run_eval.py --suites-dir ../relix/docs/llm \
    --suite ecommerce --suite issue-tracker --per-category 3 --workers 4
```

`--per-category N` takes N questions from each category of each suite, chosen at
random with `--seed`, among those whose gold query returns rows today (some golds
are relative to `NOW()` and their data ages). `--only ecommerce:3,issue-tracker:17`
reruns particular questions.

Each run writes `runs/<timestamp>/`:

- `summary.md`: pass rate, results by category, failed tool calls grouped by
  error, and every question that didn't pass with its gold, its submission and
  its errors;
- `results.jsonl`: one record per question;
- `transcripts/`: every message, tool call and result.

## Grading

A question passes (`match`) when the rows `relix_submit` returned equal the gold
query's rows over the same data, as a multiset. Columns are matched by their
values, so the model's column names and order don't matter. `extra_columns`
means the gold's rows were all there with more columns than asked for;
`mismatch` means different rows; `no_submit` means the model never submitted.
Row order isn't graded.
