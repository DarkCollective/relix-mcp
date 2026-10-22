#!/usr/bin/env python3
"""Runs held-out NL->Relix questions through a local model that uses this MCP server.

Each question from a relix-ask evaluation suite (relix's docs/llm/<suite>/ with
data.relix and queries.json) is put to a model served by LM Studio's
OpenAI-compatible API, with a system prompt and this server's tools. The model's
tool calls are executed against relix-mcp's own launcher over stdio, exactly as an
MCP client would, until it submits an answer, stops, or runs out of calls.

By default the harness loads each suite's data with relix_load, once per server,
and gives the model the data key and the tables' columns instead of the rows, as
an embedding application would: the model never retypes a row. --data inline puts
the declarations in the user's message instead, for the model to copy into every
script.

A question passes when the rows of one of the queries relix_submit ran equal the
rows its gold query returns over the same data (as a multiset; columns matched by their values,
so the model's column names and order don't matter). Every run's transcript is
kept, with each failed tool call and its error, so a failure can be traced to a
cause: the system prompt, the server's descriptions or pages, or an engine error
message.

Standard library only. Typical use, from the relix-mcp root after
`./gradlew installDist`:

    python3 tools/model-eval/run_eval.py \\
        --suites-dir ../relix/docs/llm --suite ecommerce --suite issue-tracker \\
        --per-category 3 --workers 4
"""

from __future__ import annotations

import argparse
import collections
import concurrent.futures
import datetime
import decimal
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DEFAULT_LAUNCHER = os.path.join(ROOT, "build", "install", "relix-mcp", "bin", "relix-mcp")
DEFAULT_PROMPT = os.path.join(HERE, "system-prompt.txt")
INLINE_PROMPT = os.path.join(HERE, "system-prompt-inline.txt")
DEFAULT_API = "http://localhost:1234/v1"
DEFAULT_MODEL = "openai/gpt-oss-20b"

# What LM Studio's chat sends gpt-oss (its saved preset), so a run here behaves as one there.
SAMPLING = {"temperature": 0.8, "top_p": 0.8, "top_k": 40, "min_p": 0.05, "repeat_penalty": 1.1,
            "reasoning_effort": "medium"}

_EXPRESSION = re.compile(r"RELIX EXPRESSION:\s*(.*?)\s*(?:EXPECTED OUTPUT:|\Z)", re.DOTALL)


# --------------------------------------------------------------------------- #
# MCP over stdio
# --------------------------------------------------------------------------- #

class McpServer:
    """One relix-mcp process, spoken to with newline-delimited JSON-RPC."""

    def __init__(self, launcher: str, now: str | None = None, max_rows: int | None = None):
        env = {**os.environ, **({"RELIX_MCP_NOW": now} if now else {}),
               **({"RELIX_MCP_MAX_OUTPUT_ROWS": str(max_rows)} if max_rows else {})}
        self._proc = subprocess.Popen([launcher], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                      stderr=subprocess.DEVNULL, text=True, bufsize=1, env=env)
        self._next = 0
        self._lock = threading.Lock()
        self.keys = {}  # suite name -> (data key, tables as relix_load described them)
        init = self._request("initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "relix-model-eval", "version": "1"}})
        self.server_info = init.get("serverInfo", {})
        self._notify("notifications/initialized")
        self.tools = self._request("tools/list", {}).get("tools", [])

    def _notify(self, method: str):
        self._proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": method}) + "\n")
        self._proc.stdin.flush()

    def _request(self, method: str, params: dict) -> dict:
        with self._lock:
            self._next += 1
            ident = self._next
            self._proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": ident, "method": method,
                                               "params": params}) + "\n")
            self._proc.stdin.flush()
            for line in self._proc.stdout:
                message = json.loads(line)
                if message.get("id") == ident:
                    if "error" in message:
                        raise RuntimeError(f"{method}: {message['error']}")
                    return message["result"]
        raise RuntimeError(f"{method}: the server closed its output")

    def call(self, name: str, arguments: dict) -> dict:
        """Calls a tool; returns its result: content, structuredContent, isError."""
        return self._request("tools/call", {"name": name, "arguments": arguments})

    def load(self, suite: str, data: str) -> tuple[str, list[dict]]:
        """The suite's data key on this server, loading the data the first time it is asked for."""
        with self._lock:
            held = self.keys.get(suite)
        if held is None:
            # Read-only: no call, the model's included, can replace a suite table under it.
            result = self.call("relix_load", {"tables": data, "read_only": True})
            structured = result.get("structuredContent") or {}
            if result.get("isError") or not structured.get("key"):
                raise RuntimeError(f"relix_load refused {suite}'s data: {text_of(result)[:500]}")
            held = (structured["key"], structured.get("tables") or [])
            with self._lock:
                self.keys[suite] = held
        return held

    def unload_all(self):
        for key, _ in list(self.keys.values()):
            self.call("relix_unload", {"key": key})
        self.keys.clear()

    def close(self):
        try:
            self._proc.stdin.close()
            self._proc.wait(timeout=10)
        except Exception:
            self._proc.kill()


def text_of(result: dict) -> str:
    return "".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text")


# --------------------------------------------------------------------------- #
# Suites
# --------------------------------------------------------------------------- #

def stage(suites_dir: str, names: list[str], now: str, out: str) -> str:
    """Rebuilds each suite with its own builder at one instant; returns where they went.

    A suite's data is generated relative to RELIX_SUITE_NOW (relix's docs/llm, #451 there),
    so its NOW()-relative questions mean what they were written to mean only when the
    engine's NOW() is that same instant: the servers are started with RELIX_MCP_NOW set to
    it. The questions and gold queries are the builder's own, unchanged. A suite with no
    builder is used as it is.
    """
    staged = os.path.join(out, "suites")
    for name in names:
        builder = os.path.join(suites_dir, name, "build_suite.py")
        target = os.path.join(staged, name)
        if os.path.exists(builder):
            subprocess.run([sys.executable, "-I", builder, "--out-dir", target], check=True,
                           env={**os.environ, "RELIX_SUITE_NOW": now}, stdout=subprocess.DEVNULL)
        else:
            import shutil
            shutil.copytree(os.path.join(suites_dir, name), target)
    return staged


def load_suite(suites_dir: str, name: str) -> tuple[str, list[dict]]:
    base = os.path.join(suites_dir, name)
    with open(os.path.join(base, "data.relix")) as f:
        data = f.read().strip()
    with open(os.path.join(base, "queries.json")) as f:
        queries = json.load(f)
    items = []
    for index, query in enumerate(queries):
        match = _EXPRESSION.search(query["output"])
        items.append({"suite": name, "index": index, "category": query.get("category", ""),
                      "instruction": query["instruction"],
                      "gold": match.group(1).strip() if match else query["output"].strip(),
                      # The other readings a question that leaves a choice open accepts, each a
                      # gold script of its own; "gold" stays the canonical one.
                      "accepted": [a.strip() for a in query.get("also_accepted") or []]})
    return data, items


_DECLARATION = re.compile(r"(\w+)\s*:=\s*\[.*?\];", re.DOTALL)


def _tables(text: str) -> dict[str, list[tuple]]:
    """Each inline table's rows, cells stripped, separator rows left out."""
    tables = {}
    for m in _DECLARATION.finditer(text):
        rows = []
        body = m.group(0)[m.group(0).index("[") + 1:]  # a header may follow '[' on its line
        for line in body.splitlines():
            line = line.strip().rstrip("];").strip()
            if not line.startswith("|"):
                continue
            cells = tuple(c.strip() for c in line.strip("|").split("|"))
            if all(set(c) <= set("-: ") for c in cells):
                continue
            rows.append(cells)
        tables[m.group(1)] = rows
    return tables


def altered_tables(data: str, script: str | None) -> list[str]:
    """The data's tables that the script declares with different rows.

    Rule 2 of the prompt says to copy the tables as given; a model that abbreviates
    or retypes rows may still pass when the question doesn't read that table. A
    table the script leaves out is not counted: an unused table changes nothing.
    """
    if not script:
        return []
    given, written = _tables(data), _tables(script)
    return [name for name, rows in given.items() if name in written and written[name] != rows]


def read_by_query(script: str | None, tables: list[str]) -> list[str]:
    """Those of the tables that the script's statements, its declarations aside, name."""
    if not script or not tables:
        return []
    statements = _DECLARATION.sub("", script)
    return [t for t in tables if re.search(rf"\b{re.escape(t)}\b", statements, re.IGNORECASE)]


def gold_result(server: McpServer, suite: str, data: str, gold: str, by_key: bool) -> dict | None:
    """The gold query's last result over the suite's data, or None when it fails."""
    if by_key:
        run = server.call("relix_run", {"script": gold, "data": server.load(suite, data)[0]})
    else:
        run = server.call("relix_run", {"script": data + "\n\n" + gold})
    results = (run.get("structuredContent") or {}).get("results") or []
    if not results or results[-1].get("failure") is not None:
        return None
    return results[-1]


def choose(items: list[dict], per_category: int, seed: int,
           usable: callable) -> list[dict]:
    """Up to per_category items from each category, chosen at random among usable ones."""
    by_category = collections.defaultdict(list)
    for item in items:
        by_category[item["category"]].append(item)
    rng = random.Random(seed)
    chosen = []
    for category in sorted(by_category):
        pool = by_category[category][:]
        rng.shuffle(pool)
        taken = 0
        for item in pool:
            if taken == per_category:
                break
            if usable(item):
                chosen.append(item)
                taken += 1
    return chosen


# --------------------------------------------------------------------------- #
# Grading
# --------------------------------------------------------------------------- #

def _value(value, kind: str):
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) or (kind == "NUMBER" and isinstance(value, str)):
        # To 9 significant digits: the server prints a computed value to 10 decimal places
        # and a gold may carry 20, and 2.5186777748 is 2.51867777130171996775 for any answer.
        try:
            return decimal.Decimal(f"{float(decimal.Decimal(str(value))):.9g}").normalize()
        except (decimal.InvalidOperation, ValueError, OverflowError):
            return str(value)
    return value if isinstance(value, str) else json.dumps(value, sort_keys=True)


def _columns(result: dict) -> list[list]:
    types = result.get("types") or [""] * len(result["columns"])
    rows = result.get("rows") or []
    return [[_value(row[i], types[i]) for row in rows] for i in range(len(result["columns"]))]


def compare(gold: dict, answer: dict) -> str:
    """'match', 'extra_columns' (gold's columns and rows, plus more columns),
    'missing_columns' (gold's rows, with only some of its columns) or 'mismatch'.

    A model column stands for a gold column when their values agree as multisets;
    then the rows, projected onto that assignment, must agree as a multiset too.
    """
    gold_cols, answer_cols = _columns(gold), _columns(answer)
    if 0 < len(answer_cols) < len(gold_cols) and compare(answer, gold) == "extra_columns":
        # The gold's rows, but fewer of its columns: the counterpart of extra_columns, a
        # reading of which columns to show rather than wrong rows.
        return "missing_columns"
    if len(answer_cols) < len(gold_cols) or (gold_cols and len(gold_cols[0]) != len(answer_cols[0])):
        return "mismatch"
    if not gold_cols:
        return "match" if not answer_cols else "extra_columns"
    signature = [collections.Counter(map(repr, c)) for c in answer_cols]
    candidates = [[j for j, s in enumerate(signature) if s == collections.Counter(map(repr, g))]
                  for g in gold_cols]
    want = collections.Counter(zip(*[tuple(map(repr, g)) for g in gold_cols]))

    def search(i: int, used: list[int]) -> bool:
        if i == len(gold_cols):
            got = collections.Counter(zip(*[tuple(map(repr, answer_cols[j])) for j in used]))
            return got == want
        return any(search(i + 1, used + [j]) for j in candidates[i] if j not in used)

    if not search(0, []):
        return "mismatch"
    return "match" if len(answer_cols) == len(gold_cols) else "extra_columns"


GRADING_ROWS = 100_000

# The grades whose rows are the gold's, whichever columns carry them.
RIGHT_ROWS = ("match", "extra_columns", "missing_columns")


def whole_results(grader: "McpServer", item: dict, data: dict, script: str | None,
                  results: list[dict], args) -> list[dict]:
    """A script's results whole: as given when none was cut at the row cap, otherwise
    those of the same script run again on the grading server, whose cap is far higher."""
    if not script or not any(r.get("truncated") for r in results):
        return results
    if args.data == "key":
        run = grader.call("relix_run", {"script": script,
                                        "data": grader.load(item["suite"], data[item["suite"]])[0]})
    else:
        run = grader.call("relix_run", {"script": script})
    rerun = [r for r in (run.get("structuredContent") or {}).get("results") or []
             if r.get("failure") is None]
    return rerun or results


def best(gold: dict, results: list[dict]) -> tuple[str, dict | None]:
    """The best grade any one of a script's query results earns, and that result.

    A model asked for "all details" of something often adds a query per related table; the
    answer is the one that holds the rows asked for, wherever it comes in the script, so a
    script is graded by its best query rather than its last.
    """
    graded = [(compare(gold, r), r) for r in results]
    for grade in ("match", "extra_columns", "missing_columns"):
        for g, r in graded:
            if g == grade:
                return g, r
    return "mismatch", results[-1] if results else None


def best_reading(golds: list[dict | None], results: list[dict]) -> tuple[str, dict | None, int | None]:
    """The best grade a script earns against any reading the question accepts.

    golds[0] is the canonical gold and the rest are the question's also_accepted readings;
    a reading whose gold did not run is None. Returns the grade, the result that earned it,
    and which reading it matched (0 for the canonical one), or None when none did.
    """
    fallback = ("mismatch", results[-1] if results else None, None)
    for wanted in ("match", "extra_columns", "missing_columns"):
        for n, gold in enumerate(golds):
            if gold is not None:
                grade, result = best(gold, results)
                if grade == wanted:
                    return grade, result, n
    return fallback


# --------------------------------------------------------------------------- #
# The model loop
# --------------------------------------------------------------------------- #

def openai_tools(tools: list[dict]) -> list[dict]:
    return [{"type": "function", "function": {
        "name": t["name"], "description": t.get("description", ""),
        "parameters": t.get("inputSchema", {"type": "object", "properties": {}})}}
        for t in tools]


def chat(api: str, payload: dict, timeout: int) -> dict:
    request = urllib.request.Request(api + "/chat/completions", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def loaded_context(api: str, model: str) -> int | None:
    """The context LM Studio loaded the model with, or None when it isn't loaded or can't say.

    A model the API loads on first use gets LM Studio's default context (8,192 tokens for
    gpt-oss-20b), too small for the system prompt, the tools and the data. LM Studio then
    drops the start of the conversation, and the model restarts the task after a call.
    """
    try:
        with urllib.request.urlopen(api.rsplit("/v1", 1)[0] + "/api/v0/models", timeout=10) as r:
            models = json.load(r).get("data", [])
    except (urllib.error.URLError, TimeoutError, ValueError):
        return None
    for m in models:
        if m.get("id") == model and m.get("state") == "loaded":
            return m.get("loaded_context_length")
    return None


def failed(name: str, result: dict) -> str | None:
    """The first line of a tool result that reports a problem, or None."""
    text = text_of(result)
    if result.get("isError") or text.startswith(("Invalid", "Not submitted", "No page is called")) \
            or "\nFailed: " in text or text.startswith("Failed: "):
        if name == "relix_learn" and text.startswith("No page is called"):
            return None  # a search, not a failure
        lines = [line for line in text.splitlines() if "error:" in line or "Failed:" in line]
        return (lines[0] if lines else text.splitlines()[0])[:300]
    return None


def described(key: str, tables: list[dict]) -> str:
    """What the model is told of data loaded for it: the key, and each table's columns."""
    lines = [f'The data is loaded under the data key {key}. Pass "data": "{key}" with every '
             f"relix_validate, relix_run, relix_submit and relix_explain call. Its tables:", ""]
    for t in tables:
        columns = ", ".join(f"{c} {ty}" for c, ty in zip(t["columns"], t["types"]))
        lines.append(f"- {t['name']} ({t['rows']} rows): {columns}")
        for column, values in (t.get("values") or {}).items():
            lines.append(f"  {column} is one of: {', '.join(values)}")
    return "\n".join(lines)


# The tools that change what a data key holds. On the client path the harness loads the data
# and the model only reads it: a model that called relix_load with the harness's key and a
# table of its own replaced the suite's table for every later question on that server.
CLIENT_ONLY = {"relix_load", "relix_unload"}


# A tool the harness answers itself, as a client would: the model's way to say a question
# cannot be answered as written, rather than guessing. No user is there to reply, so the
# answer tells it to choose a reading and say which; with --on-ask stop, asking ends the
# question instead, graded "asked".
ASK_USER = {"type": "function", "function": {
    "name": "ask_user",
    "description": "Ask the user what they mean, when the question cannot be answered as "
                   "written: a word that is neither a column nor one of a column's values, or a "
                   "choice the question leaves open that the data cannot settle. Check the data "
                   "first; ask only what it cannot tell you.",
    "parameters": {"type": "object", "required": ["question"], "properties": {
        "question": {"type": "string", "description": "the one thing you need the user to say"}}}}}

NO_ONE_TO_ASK = ("The user is not available to answer. Choose the most reasonable reading of the "
                 "question, say which you chose in your assumption line, and finish with relix_submit.")

# LM Studio drops a call whose arguments do not parse and returns an empty message; a client
# that says so lets the model make the call again, as most agent clients report a malformed call.
DROPPED_CALL = ("Your last tool call was not made: its arguments were not valid JSON, often because "
                "of an unescaped quote or line break inside the script. Make the call again.")
DROPPED_RETRIES = 2

# What a model that stopped without submitting is told, --reminders times at most: the rows it
# writes in its reply are not an answer. Its answer is then graded as any other, and marked
# submitted_after_reminder, so the summary can give the pass rate with and without it.
NOT_SUBMITTED = ("You have not called relix_submit, so there is no answer yet: a script or rows "
                 "written in your reply do not count. If you have the script, call relix_submit with "
                 "it now. If the question cannot be answered as written, call ask_user.")

# gpt-oss sometimes emits the start of a call in its own format and no arguments at all:
# `<|channel|>relix_run?commentary=true}` or `to=relix_run?`. That is a call it meant to
# make and could not finish, not an answer, so it is told to make the call again, as a
# dropped call is, rather than reminded to submit.
GARBLED_CALL = ("Your last tool call came through garbled, as text and without its arguments, "
                "so it was not made. Make the call again, as a tool call.")
_CALL_MARKUP = re.compile(r"<\|(?:channel|message|call|constrain)\|>|\bto=(?:functions\.)?(?:relix_\w+|ask_user)\b")


def garbled_call(content: str) -> bool:
    """Whether a reply is the markup of a tool call with nothing to make of it."""
    return bool(_CALL_MARKUP.search(content)) and leaked_call(content) is None


_LEAKED = re.compile(r"(?:to=functions\.|\b)(relix_\w+|ask_user)\b[^{]*?(\{.*\})", re.DOTALL)


def leaked_call(content: str) -> tuple[str, dict] | None:
    """A tool call the model wrote into its reply instead of making it, or None.

    gpt-oss sometimes emits its call in its own format, `to=functions.relix_submit ...
    <|message|>{"script": ...}`, which LM Studio passes through as text rather than parsing,
    and sometimes writes `relix_submit {"script": ...}`. Either is a call the model meant to
    make, so the harness makes it, and counts it as recovered.
    """
    for match in _LEAKED.finditer(content):
        text = match.group(2)
        for end in range(len(text), 0, -1):  # the longest prefix that is one JSON object
            if text[end - 1] != "}":
                continue
            try:
                arguments = json.loads(text[:end])
            except json.JSONDecodeError:
                continue
            if isinstance(arguments, dict):
                return match.group(1), arguments
    return None


def ask(item: dict, data: str, prompt: str, server: McpServer, args) -> dict:
    offered = [t for t in server.tools if args.data != "key" or t["name"] not in CLIENT_ONLY]
    tools = openai_tools(offered) + [ASK_USER]
    callable_names = {t["name"] for t in offered} | {"ask_user"}
    if args.data == "key":
        given = described(*server.load(item["suite"], data))
    else:
        given = f"Here is the data.\n\n{data}"
    messages = [{"role": "system", "content": prompt},
                {"role": "user", "content": f"{given}\n\n{item['instruction']}"}]
    events, calls, failures, topics, runs = [], 0, [], [], []
    asks, recovered, retries, reminders, garbled = [], 0, 0, 0, 0
    submission, final_text, stop = None, "", "turns"
    started = time.time()
    for _ in range(args.max_turns):
        # Until it submits, every turn must be a call (--tool-choice required), so the only way
        # to finish is relix_submit; after it, one turn with no calls for the closing reply.
        payload = {"model": args.model, "messages": messages, "tools": tools,
                   "tool_choice": "none" if submission is not None else args.tool_choice, **SAMPLING}
        try:
            reply = chat(args.api, payload, args.timeout)
        except urllib.error.HTTPError as e:
            stop = f"api_error: {e.code} {e.read().decode(errors='replace')[:300]}"
            break
        except (urllib.error.URLError, TimeoutError) as e:
            stop = f"api_error: {e}"
            break
        message = reply["choices"][0]["message"]
        tool_calls = (message.get("tool_calls") or [])[:1]  # the template renders one call a turn
        reasoning = message.get("reasoning") or message.get("reasoning_content") or ""
        content = message.get("content") or ""
        events.append({"assistant": content, "reasoning": reasoning, "tool_calls": tool_calls})
        # The reasoning is not sent back. Passing it in later turns' content made gpt-oss
        # restart its plan after every call: it re-solved and re-submitted answered questions,
        # and repeated one lookup twenty times.
        if not tool_calls and submission is None:
            leaked = leaked_call(content)
            if leaked:
                recovered += 1
                tool_calls = [{"id": f"recovered-{recovered}", "type": "function", "recovered": True,
                               "function": {"name": leaked[0], "arguments": json.dumps(leaked[1])}}]
                events[-1]["recovered"] = leaked[0]
        messages.append({"role": "assistant", "content": content,
                         **({"tool_calls": tool_calls} if tool_calls else {})})
        if not tool_calls:
            final_text = content
            if submission:
                stop = "answered"
            elif not content.strip():
                if retries < DROPPED_RETRIES:
                    retries += 1
                    messages.append({"role": "user", "content": DROPPED_CALL})
                    events.append({"harness": DROPPED_CALL})
                    continue
                stop = "dropped_tool_call"  # LM Studio omits a call whose arguments don't parse
            elif garbled_call(content) and garbled < DROPPED_RETRIES:
                garbled += 1
                messages.append({"role": "user", "content": GARBLED_CALL})
                events.append({"harness": GARBLED_CALL})
                continue
            else:
                if reminders < args.reminders:
                    reminders += 1
                    messages.append({"role": "user", "content": NOT_SUBMITTED})
                    events.append({"harness": NOT_SUBMITTED})
                    continue
                stop = "stopped_without_submit"
            break
        if submission is not None:
            # The turn after a submission is for the closing reply. A call instead changes
            # nothing: the first submission is the answer a user is shown.
            stop = "answered_then_kept_calling"
            break
        for call in tool_calls:
            calls += 1
            name = call["function"]["name"]
            try:
                arguments = json.loads(call["function"].get("arguments") or "{}")
            except json.JSONDecodeError:
                arguments = None
            if arguments is None:
                result = {"content": [{"type": "text", "text": "The arguments were not valid JSON."}],
                          "isError": True}
            elif name not in callable_names:
                result = {"content": [{"type": "text", "text": f"There is no tool called {name}."}],
                          "isError": True}
            elif name == "ask_user":
                asks.append({"call": calls, "question": str(arguments.get("question", ""))})
                result = {"content": [{"type": "text", "text": NO_ONE_TO_ASK}]}
            else:
                result = server.call(name, arguments)
            if name == "relix_learn" and arguments:
                topics.append(arguments.get("topic", "(contents)"))
            problem = failed(name, result) if arguments is not None else "arguments were not valid JSON"
            if problem:
                failures.append({"call": calls, "tool": name, "error": problem})
            structured = result.get("structuredContent") or {}
            results = structured.get("results") or []
            ran = [r for r in results if r.get("failure") is None]
            if name == "relix_submit" and structured.get("submitted"):
                submission = {"script": structured.get("script"), "results": ran}
            finished = (name == "relix_run" and structured.get("valid") and not structured.get("stopped")
                        and results and results[-1].get("failure") is None)
            if finished:
                runs.append({"call": calls, "results": ran, "script": arguments.get("script")})
            events.append({"tool": name, "arguments": arguments, "result": text_of(result),
                           **({"rows": results[-1]} if finished else {})})
            messages.append({"role": "tool", "tool_call_id": call.get("id", ""),
                             "content": text_of(result)})
        if asks and submission is None and args.on_ask == "stop":
            stop = "asked"
            break
        if calls >= args.max_calls and submission is None:
            stop = "call_limit"
            break
    return {"events": events, "calls": calls, "failures": failures, "learn": topics, "runs": runs,
            "asks": asks, "recovered": recovered, "retries": retries, "reminders": reminders,
            "garbled": garbled,
            "submission": submission, "final_text": final_text, "stop": stop,
            "seconds": round(time.time() - started, 1)}


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--suites-dir", required=True, help="relix's docs/llm")
    parser.add_argument("--suite", action="append", required=True, help="a suite under --suites-dir (repeatable)")
    parser.add_argument("--per-category", type=int, default=3)
    parser.add_argument("--seed", type=int, default=20261020)
    parser.add_argument("--only", help="comma-separated suite:index ids to run instead of a sample")
    parser.add_argument("--now", help="the instant NOW() is, as ISO-8601 (default: the current time); "
                                      "the suites are staged at it and the servers' clocks fixed to it")
    parser.add_argument("--no-stage", action="store_true",
                        help="use the suites as committed, with the real clock (NOW() questions drift)")
    parser.add_argument("--data", choices=("key", "inline"), default="key",
                        help="'key' (the default) loads each suite once with relix_load and gives the "
                             "model its key and columns; 'inline' puts the declarations in the question "
                             "for the model to copy into every script")
    parser.add_argument("--tool-choice", choices=("auto", "required"), default="auto",
                        help="sent as tool_choice before relix_submit; LM Studio ignored 'required' for "
                             "gpt-oss-20b (it still answered in text), so 'auto' is the default")
    parser.add_argument("--reminders", type=int, default=2,
                        help="how many times a model that stops without submitting is told to submit "
                             "(default 2; 0 grades its first stop, as before)")
    parser.add_argument("--on-ask", choices=("continue", "stop"), default="continue",
                        help="when the model calls ask_user: 'continue' (the default) tells it no one can "
                             "answer and to choose a reading; 'stop' ends the question, graded 'asked'")
    parser.add_argument("--prompt", help=f"the system prompt (default: {os.path.basename(DEFAULT_PROMPT)}, "
                                         f"or {os.path.basename(INLINE_PROMPT)} with --data inline)")
    parser.add_argument("--launcher", default=DEFAULT_LAUNCHER)
    parser.add_argument("--api", default=DEFAULT_API)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--temperature", type=float, default=None, help="overrides the preset's 0.8")
    parser.add_argument("--workers", type=int, default=1,
                        help="parallel requests; 2 crashed gpt-oss-20b with a 65,536-token context "
                             "on a 48 GB Mac, and 4 at a smaller one")
    parser.add_argument("--min-context", type=int, default=32768,
                        help="refuse to run unless the model is loaded with at least this context")
    parser.add_argument("--max-turns", type=int, default=40)
    parser.add_argument("--max-calls", type=int, default=25)
    parser.add_argument("--timeout", type=int, default=600, help="seconds per model request")
    parser.add_argument("--out", default=os.path.join(HERE, "runs"))
    args = parser.parse_args(argv)

    context = loaded_context(args.api, args.model)
    if context is None or context < args.min_context:
        print(f"{args.model} is {'not loaded' if context is None else f'loaded with a {context}-token context'}"
              f"; it needs at least {args.min_context}. Load it first:\n"
              f"  ~/.lmstudio/bin/lms load {args.model} --context-length 65536 --parallel {args.workers}",
              file=sys.stderr)
        return 2
    if args.prompt is None:
        args.prompt = INLINE_PROMPT if args.data == "inline" else DEFAULT_PROMPT
    with open(args.prompt) as f:
        prompt = f.read()
    if args.temperature is not None:
        SAMPLING["temperature"] = args.temperature
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = os.path.join(args.out, stamp)
    os.makedirs(os.path.join(out, "transcripts"))
    now = None
    if not args.no_stage:
        now = args.now or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        args.suites_dir = stage(args.suites_dir, args.suite, now, out)
    args.pinned_now = now

    # The grading server returns results whole: the model's are cut at the server's 200-row
    # cap, and two cuts of the same rows may differ, so a cut result is run again here.
    grader = McpServer(args.launcher, now, max_rows=GRADING_ROWS)
    data, items, golds = {}, [], {}
    for name in args.suite:
        data[name], suite_items = load_suite(args.suites_dir, name)
        for item in suite_items:
            item["id"] = f"{name}:{item['index']}"
        items.extend(suite_items)

    def usable(item):
        readings = [gold_result(grader, item["suite"], data[item["suite"]], program,
                                args.data == "key") for program in [item["gold"], *item["accepted"]]]
        for n, reading in enumerate(readings[1:], 1):
            if reading is None:
                print(f"{item['id']}: accepted reading {n} does not run; it is left out",
                      file=sys.stderr)
        golds[item["id"]] = readings
        return readings[0] is not None and bool(readings[0].get("rows"))

    if args.only:
        wanted = set(args.only.split(","))
        chosen = [i for i in items if i["id"] in wanted]
        for item in chosen:
            usable(item)
    else:
        chosen = []
        for name in args.suite:
            chosen += choose([i for i in items if i["suite"] == name], args.per_category, args.seed, usable)
    print(f"{len(chosen)} questions; server {grader.server_info.get('title')}; model {args.model}; "
          f"NOW() {now or 'the real clock'}", file=sys.stderr)

    servers = [McpServer(args.launcher, now) for _ in range(args.workers)]
    free = list(servers)
    free_lock = threading.Lock()

    def run(item):
        with free_lock:
            server = free.pop()
        try:
            outcome = ask(item, data[item["suite"]], prompt, server, args)
        finally:
            with free_lock:
                free.append(server)
        readings = golds.get(item["id"]) or [None]
        gold = readings[0]
        answer = None
        right_at = None
        reading = None
        regraded = 0
        if outcome["submission"] is not None:
            whole = whole_results(grader, item, data, outcome["submission"]["script"],
                                  outcome["submission"]["results"], args)
            regraded += whole is not outcome["submission"]["results"]
            outcome["submission"]["results"] = whole
        for r in outcome["runs"]:
            whole = whole_results(grader, item, data, r.get("script"), r["results"], args)
            regraded += whole is not r["results"]
            r["results"] = whole
        if outcome["submission"] is None and outcome["stop"] == "asked":
            grade = "asked"
        elif outcome["submission"] is None:
            grade = "no_submit"
            # Did a run show the right rows, though no answer was given? Then the model
            # solved it and failed only to finish: a different problem from not solving it.
            right_at = next((r["call"] for r in reversed(outcome["runs"])
                             if best_reading(readings, r["results"])[0] in ("match", "extra_columns")),
                            None)
            if right_at is not None:
                grade = "right_not_submitted"
        elif gold is None:
            grade = "mismatch"
        else:
            grade, answer, reading = best_reading(readings, outcome["submission"]["results"])
        submitted = (outcome["submission"] or {}).get("script")
        # Over a data key the server ignores a table the script declares again, so a copy
        # can't change the rows; only an inline run's copy can.
        altered = altered_tables(data[item["suite"]], submitted) if args.data == "inline" else []
        if grade in ("mismatch", "extra_columns") and read_by_query(submitted, altered):
            # The rows differ because the model retyped a table it reads, not because its
            # query is wrong: a copying failure, kept apart from a wrong answer.
            grade = "data_altered"
        record = {k: item[k] for k in ("id", "suite", "category", "instruction", "gold")}
        record.update(grade=grade, right_at_call=right_at, stop=outcome["stop"], calls=outcome["calls"],
                      failures=outcome["failures"], learn=outcome["learn"],
                      submitted=submitted, altered_tables=altered,
                      assumption="ssum" in outcome["final_text"],
                      raw_tokens="<|" in outcome["final_text"], seconds=outcome["seconds"],
                      accepted_reading=reading, asks=outcome["asks"], regraded_whole=regraded,
                      recovered_calls=outcome["recovered"], dropped_call_retries=outcome["retries"],
                      garbled_call_retries=outcome["garbled"],
                      submitted_after_reminder=bool(outcome["reminders"] and outcome["submission"]),
                      gold_columns=(gold or {}).get("columns"),
                      answer_columns=(answer or {}).get("columns"))
        with open(os.path.join(out, "transcripts", item["id"].replace(":", "-") + ".json"), "w") as f:
            json.dump({**record, "gold_result": gold, "accepted": item["accepted"],
                       "accepted_results": readings[1:], "answer_result": answer,
                       "final_text": outcome["final_text"], "events": outcome["events"]}, f, indent=1)
        print(f"{record['id']:<18} {grade:<14} calls={record['calls']:<3} "
              f"failed={len(record['failures']):<2} {record['seconds']}s", file=sys.stderr)
        return record

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        records = list(pool.map(run, chosen))
    for server in servers + [grader]:
        server.unload_all()
        server.close()

    with open(os.path.join(out, "results.jsonl"), "w") as f:
        for record in records:
            f.write(json.dumps(record) + "\n")
    with open(os.path.join(out, "summary.md"), "w") as f:
        f.write(summary(records, args, grader.server_info))
    print(os.path.join(out, "summary.md"))
    return 0


def summary(records: list[dict], args, server_info: dict) -> str:
    total = len(records)
    grades = collections.Counter(r["grade"] for r in records)
    lines = [f"# Model eval: {args.model}", "",
             f"Server: {server_info.get('title')} {server_info.get('version')}. "
             f"Suites: {', '.join(args.suite)}. Questions: {total}. "
             f"Data: {'loaded once, by key' if args.data == 'key' else 'inline, copied by the model'}. "
             f"NOW(): {getattr(args, 'pinned_now', None) or 'the real clock'}.", "",
             "| grade | count |", "|---|---|"]
    lines += [f"| {g} | {n} |" for g, n in grades.most_common()]
    passed = grades["match"]
    first_try = sum(1 for r in records if r["grade"] == "match" and not r.get("submitted_after_reminder")
                    and not r.get("recovered_calls"))
    reached = grades["match"] + grades["extra_columns"] + grades["right_not_submitted"]
    right_rows = sum(1 for r in records if r["grade"] in RIGHT_ROWS)
    lines += ["", f"Right rows submitted, in any columns: {right_rows}/{total} "
                  f"({100 * right_rows / max(total, 1):.0f}%)."]
    lines += ["", f"Pass rate: {passed}/{total} ({100 * passed / max(total, 1):.0f}%); "
                  f"without a reminder or a recovered call: {first_try}/{total} "
                  f"({100 * first_try / max(total, 1):.0f}%). "
                  f"Right rows reached, submitted or not: {reached}/{total}. "
                  f"Mean calls: {sum(r['calls'] for r in records) / max(total, 1):.1f}. "
                  f"Runs with a failed call: {sum(1 for r in records if r['failures'])}. "
                  f"Submissions that altered the data: "
                  f"{sum(1 for r in records if r['altered_tables'])}. "
                  f"Replies with an assumption line: {sum(1 for r in records if r['assumption'])}. "
                  f"Passes on a reading other than the canonical gold: "
                  f"{sum(1 for r in records if r.get('accepted_reading'))}. "
                  f"Calls recovered from the reply's text: "
                  f"{sum(r.get('recovered_calls', 0) for r in records)}. "
                  f"Dropped calls retried: {sum(r.get('dropped_call_retries', 0) for r in records)}. "
                  f"Garbled calls retried: {sum(r.get('garbled_call_retries', 0) for r in records)}. "
                  f"Submitted after a reminder: "
                  f"{sum(1 for r in records if r.get('submitted_after_reminder'))}. "
                  f"Questions where the model asked the user: "
                  f"{sum(1 for r in records if r.get('asks'))}.", "",
              "## By category", "",
              "Right rows: the gold's rows in any columns (match, extra_columns or missing_columns), "
              "the nearest to relix's agentic grade, which also tolerates extra columns.", "",
              "| category | pass | right rows | of |", "|---|---|---|---|"]
    by_category = collections.defaultdict(list)
    for r in records:
        by_category[r["category"]].append(r)
    for category in sorted(by_category):
        rs = by_category[category]
        right = sum(r["grade"] in RIGHT_ROWS for r in rs)
        lines.append(f"| {category} | {sum(r['grade'] == 'match' for r in rs)} | {right} | {len(rs)} |")
    asked = [r for r in records if r.get("asks")]
    if asked:
        lines += ["", "## Questions the model asked about", "",
                  "What it asked, and how the question then went: a question it could not answer "
                  "as written, or one it should have checked the data for.", ""]
        for r in sorted(asked, key=lambda r: r["id"]):
            for a in r["asks"]:
                lines.append(f"- {r['id']} ({r['grade']}): > {r['instruction']}  \n  asked: {a['question']}")
    lines += ["", "## Failed tool calls, by first error line", ""]
    errors = collections.Counter(
        re.sub(r"script:\d+:\d+: ", "", f["error"])[:160] for r in records for f in r["failures"])
    lines += [f"- {n} × `{e}`" for e, n in errors.most_common()]
    lines += ["", "## Not passed", ""]
    for r in sorted(records, key=lambda r: r["id"]):
        if r["grade"] == "match":
            continue
        lines += [f"### {r['id']} ({r['category']}): {r['grade']}, {r['calls']} calls, stop={r['stop']}",
                  "", f"> {r['instruction']}", "", "Gold:", "```relix", r["gold"], "```"]
        if r["submitted"]:
            body = "\n".join(l for l in r["submitted"].splitlines()
                             if l.strip() and not l.lstrip().startswith("|") and l.strip() != "];"
                             and ":= [" not in l)
            lines += ["Submitted (data declarations left out):", "```relix", body, "```"]
        if r.get("right_at_call"):
            lines.append(f"The right rows came back at call {r['right_at_call']}, but were never submitted.")
        lines += [f"Columns: gold {r['gold_columns']}, answer {r['answer_columns']}"]
        if r["altered_tables"]:
            lines.append(f"Data altered in the submission: {', '.join(r['altered_tables'])}")
        lines.append("")
        for f_ in r["failures"]:
            lines.append(f"- call {f_['call']} {f_['tool']}: `{f_['error']}`")
        lines.append("")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())
