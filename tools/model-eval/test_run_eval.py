"""Tests for run_eval's grading. Standard library only:

    python3 -m unittest discover -s tools/model-eval
"""

import unittest

import run_eval

CLUSTER = "query { CLUSTER product_a, product_b AS bundle (also_bought) };"
ROLLUP = "query { γ ROLLUP (sprint_id, priority), SUM(story_points) → total_points (issues) };"
PIVOT = ("Counts := { γ room_id, status, COUNT(*) → n (devices) };\n"
         "query { PIVOT n BY status PER room_id (Counts) };")
GROUPING_SETS = "query { γ GROUPING SETS ((rating), (category)), COUNT(*) → review_count (reviews) };"


def result(columns, types, rows):
    return {"columns": columns, "types": types, "rows": rows, "failure": None}


class ClusterAnswersAreComparedAsPartitions(unittest.TestCase):

    gold = run_eval.graded_as(CLUSTER, result(
        ["product_a", "bundle"], ["NUMBER", "NUMBER"],
        [[101, 1], [102, 1], [111, 1], [103, 2], [107, 2], [105, 3]]))

    def test_the_gold_names_its_label_column(self):
        self.assertEqual(self.gold["partition"], "bundle")

    def test_the_same_groups_under_other_labels_match(self):
        # Each bundle labelled by its smallest member, in another column order.
        answer = result(["bundle", "product"], ["NUMBER", "NUMBER"],
                        [[101, 101], [101, 102], [101, 111], [103, 103], [103, 107], [105, 105]])
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_other_groups_do_not_match(self):
        # The same group sizes, but 111 is put with 103 and 102 with 107.
        answer = result(["product_a", "bundle"], ["NUMBER", "NUMBER"],
                        [[101, 1], [102, 2], [111, 1], [103, 1], [107, 2], [105, 3]])
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")

    def test_any_other_column_must_still_match_exactly(self):
        answer = result(["product_a", "bundle"], ["NUMBER", "NUMBER"],
                        [[101, 1], [102, 1], [111, 1], [103, 2], [107, 2], [106, 3]])
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")

    def test_a_query_without_cluster_compares_labels_as_values(self):
        gold = run_eval.graded_as("query { T };", result(
            ["product_a", "bundle"], ["NUMBER", "NUMBER"], [[101, 1], [102, 2]]))
        answer = result(["product_a", "bundle"], ["NUMBER", "NUMBER"], [[101, 7], [102, 8]])
        self.assertNotIn("partition", gold)
        self.assertEqual(run_eval.compare(gold, answer), "mismatch")


class GroupingSetsAnsweredSeparatelyAreStacked(unittest.TestCase):

    gold = run_eval.graded_as(GROUPING_SETS, result(
        ["rating", "category", "review_count"], ["NUMBER", "STRING", "NUMBER"],
        [[5, None, 4], [4, None, 3], [None, "Books", 3], [None, "Toys", 2]]))

    by_rating = result(["rating", "review_count"], ["NUMBER", "NUMBER"], [[4, 3], [5, 4]])
    by_category = result(["category", "review_count"], ["STRING", "NUMBER"],
                         [["Toys", 2], ["Books", 3]])

    def test_one_query_per_grouping_matches(self):
        grade, answer = run_eval.best(self.gold, [self.by_rating, self.by_category])
        self.assertEqual(grade, "match")
        self.assertEqual(answer["stacked"], 2)

    def test_a_result_with_columns_the_gold_lacks_is_left_out_of_the_stack(self):
        preview = result(["rating", "body"], ["NUMBER", "STRING"], [[5, "Great"]])
        grade, _ = run_eval.best(self.gold, [preview, self.by_rating, self.by_category])
        self.assertEqual(grade, "match")

    def test_a_wrong_count_in_one_grouping_does_not_match(self):
        wrong = result(["category", "review_count"], ["STRING", "NUMBER"],
                       [["Toys", 2], ["Books", 4]])
        grade, _ = run_eval.best(self.gold, [self.by_rating, wrong])
        self.assertEqual(grade, "mismatch")

    def test_one_grouping_alone_does_not_match(self):
        grade, _ = run_eval.best(self.gold, [self.by_rating])
        self.assertEqual(grade, "mismatch")

    def test_the_gold_names_its_sets(self):
        self.assertEqual(self.gold["grouping_sets"], [["rating"], ["category"]])

    def test_one_result_laid_out_long_matches(self):
        # A label of its own spelling, each value as text, then the count.
        long = result(["group_type", "group_value", "review_count"], ["STRING", "STRING", "NUMBER"],
                      [["by rating", "5", 4], ["by rating", "4", 3],
                       ["by category", "Books", 3], ["by category", "Toys", 2]])
        self.assertEqual(run_eval.best(self.gold, [long])[0], "match")

    def test_a_long_layout_in_another_column_order_matches(self):
        long = result(["value", "review_count", "kind"], ["STRING", "NUMBER", "STRING"],
                      [["Toys", 2, "c"], ["5", 4, "r"], ["Books", 3, "c"], ["4", 3, "r"]])
        self.assertEqual(run_eval.best(self.gold, [long])[0], "match")

    def test_a_long_layout_that_labels_a_value_with_the_wrong_grouping_does_not_match(self):
        long = result(["group_type", "group_value", "review_count"], ["STRING", "STRING", "NUMBER"],
                      [["rating", "5", 4], ["rating", "4", 3],
                       ["rating", "Books", 3], ["category", "Toys", 2]])
        self.assertEqual(run_eval.best(self.gold, [long])[0], "mismatch")

    def test_a_long_layout_with_a_wrong_count_does_not_match(self):
        long = result(["group_type", "group_value", "review_count"], ["STRING", "STRING", "NUMBER"],
                      [["rating", "5", 4], ["rating", "4", 3],
                       ["category", "Books", 2], ["category", "Toys", 2]])
        self.assertEqual(run_eval.best(self.gold, [long])[0], "mismatch")

    def test_sets_of_several_columns_have_no_long_layout(self):
        gold = run_eval.graded_as(
            "query { γ GROUPING SETS ((rating, category), ()), COUNT(*) → n (reviews) };",
            result(["rating", "category", "n"], ["NUMBER", "STRING", "NUMBER"], [[5, "Toys", 1]]))
        self.assertEqual(gold["grouping_sets"], [["rating", "category"], []])
        self.assertIsNone(run_eval.unpivoted(gold))

    def test_results_are_not_stacked_for_a_gold_without_grouping_sets(self):
        gold = {k: v for k, v in self.gold.items() if k != "grouping_sets"}
        grade, _ = run_eval.best(gold, [self.by_rating, self.by_category])
        self.assertEqual(grade, "mismatch")


class SubtotalRowsMayBeLabelled(unittest.TestCase):

    gold = run_eval.graded_as(ROLLUP, result(
        ["sprint_id", "priority", "total_points"], ["NUMBER", "STRING", "NUMBER"],
        [[301, "high", 18], [301, "low", 1], [302, "high", 13],
         [301, None, 19], [302, None, 13], [None, None, 32]]))

    def test_the_gold_names_its_subtotal_columns(self):
        self.assertEqual(self.gold["subtotals"], ["sprint_id", "priority"])

    def test_labels_in_place_of_null_match(self):
        answer = result(["sprint_id", "priority", "total_points"], ["NUMBER", "STRING", "NUMBER"],
                        [[301, "high", 18], [301, "low", 1], [302, "high", 13],
                         [301, "Subtotal", 19], [302, "Subtotal", 13], [None, "Grand Total", 32]])
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_a_label_in_a_number_column_matches(self):
        # 'Total' in the sprint column makes it text, its ids too.
        answer = result(["sprint", "priority", "points"], ["STRING", "STRING", "NUMBER"],
                        [["301", "high", 18], ["301", "low", 1], ["302", "high", 13],
                         ["301", None, 19], ["302", None, 13], ["Total", None, 32]])
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_a_labelled_subtotal_with_a_wrong_total_does_not_match(self):
        answer = result(["sprint_id", "priority", "total_points"], ["NUMBER", "STRING", "NUMBER"],
                        [[301, "high", 18], [301, "low", 1], [302, "high", 13],
                         [301, "Subtotal", 20], [302, "Subtotal", 13], [None, "Grand Total", 32]])
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")

    def test_a_wrong_value_in_a_detail_row_does_not_match(self):
        # 'medium' is none of the gold's priorities, but it stands on a detail row.
        answer = result(["sprint_id", "priority", "total_points"], ["NUMBER", "STRING", "NUMBER"],
                        [[301, "high", 18], [301, "medium", 1], [302, "high", 13],
                         [301, None, 19], [302, None, 13], [None, None, 32]])
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")

    def test_labels_are_not_read_as_null_without_rollup(self):
        gold = {k: v for k, v in self.gold.items() if k != "subtotals"}
        answer = result(["sprint_id", "priority", "total_points"], ["NUMBER", "STRING", "NUMBER"],
                        [[301, "high", 18], [301, "low", 1], [302, "high", 13],
                         [301, "Subtotal", 19], [302, "Subtotal", 13], [None, "Grand Total", 32]])
        self.assertEqual(run_eval.compare(gold, answer), "mismatch")


class PivotCellsWithNoRowsAreZero(unittest.TestCase):

    gold = run_eval.graded_as(PIVOT, result(
        ["room_id", "offline", "online"], ["NUMBER", "NUMBER", "NUMBER"],
        [[201, 6, 5], [202, 2, None]]))

    def test_the_gold_names_its_value_columns(self):
        self.assertEqual(self.gold["pivot_values"], ["offline", "online"])

    def test_a_zero_where_the_pivot_has_null_matches(self):
        answer = result(["room_id", "online", "offline"], ["NUMBER", "NUMBER", "NUMBER"],
                        [[201, 5, 6], [202, 0, 2]])
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_a_wrong_count_does_not_match(self):
        answer = result(["room_id", "online", "offline"], ["NUMBER", "NUMBER", "NUMBER"],
                        [[201, 5, 6], [202, 1, 2]])
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")

    def test_a_key_is_not_zeroed(self):
        gold = run_eval.graded_as(PIVOT, result(
            ["room_id", "offline"], ["NUMBER", "NUMBER"], [[None, 6], [202, 2]]))
        answer = result(["room_id", "offline"], ["NUMBER", "NUMBER"], [[0, 6], [202, 2]])
        self.assertEqual(run_eval.compare(gold, answer), "mismatch")

    def test_zero_is_not_null_without_pivot(self):
        gold = {k: v for k, v in self.gold.items() if k != "pivot_values"}
        answer = result(["room_id", "online", "offline"], ["NUMBER", "NUMBER", "NUMBER"],
                        [[201, 5, 6], [202, 0, 2]])
        self.assertEqual(run_eval.compare(gold, answer), "mismatch")


class ADayIsItsMidnight(unittest.TestCase):

    gold = result(["bucket", "sum_minutes"], ["TIMESTAMP", "NUMBER"],
                  [["2026-10-21T00:00:00Z", 300], ["2026-10-22T00:00:00Z", 120]])

    def test_dates_match_midnight_timestamps(self):
        answer = result(["day", "total_minutes"], ["DATE", "NUMBER"],
                        [["2026-10-22", 120], ["2026-10-21", 300]])
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_another_time_of_day_is_not_the_date(self):
        gold = result(["at"], ["TIMESTAMP"], [["2026-10-21T09:30:00Z"]])
        self.assertEqual(run_eval.compare(gold, result(["at"], ["DATE"], [["2026-10-21"]])), "mismatch")

    def test_a_midnight_in_another_zone_is_not_the_date(self):
        gold = result(["at"], ["TIMESTAMPTZ"], [["2026-10-21T00:00:00-04:00"]])
        self.assertEqual(run_eval.compare(gold, result(["at"], ["DATE"], [["2026-10-21"]])), "mismatch")


class TemperatureRisesForAStuckModel(unittest.TestCase):

    ALIAS = "script:6:29: error: Syntax error in RA expression: Relix has no table aliases: write RENAME o (orders)"

    def escalation(self):
        return run_eval.Escalation(0.2, [0.8, 1.0], 3)

    def fail(self, e, error=None, line=6):
        return e.observe("relix_validate", {"script": "…"}, (error or self.ALIAS).replace("script:6:", f"script:{line}:"), "Invalid")

    def test_three_identical_errors_raise_it(self):
        e = self.escalation()
        self.assertIsNone(self.fail(e)); self.assertIsNone(self.fail(e))
        self.assertTrue(self.fail(e).startswith("up to 0.8"))
        self.assertEqual(e.temperature, 0.8)

    def test_the_same_error_on_another_line_counts_as_the_same(self):
        e = self.escalation()
        self.fail(e, line=3); self.fail(e, line=9); self.fail(e, line=12)
        self.assertEqual(e.temperature, 0.8)

    def test_three_more_raise_it_again_and_no_further(self):
        e = self.escalation()
        for _ in range(9):
            self.fail(e)
        self.assertEqual(e.temperature, 1.0)

    def test_different_errors_do_not_raise_it(self):
        e = self.escalation()
        self.fail(e); self.fail(e, "script:1:1: error: Expected end of input; found ','"); self.fail(e)
        self.assertEqual(e.temperature, 0.2)

    def test_a_script_that_goes_through_drops_it_back(self):
        e = self.escalation()
        for _ in range(3):
            self.fail(e)
        self.assertTrue(e.observe("relix_validate", {"script": "…"}, None, "Valid.").startswith("back to 0.2"))
        self.assertEqual(e.temperature, 0.2)
        self.fail(e); self.fail(e)
        self.assertEqual(e.temperature, 0.2)

    def test_a_lookup_asked_again_and_again_raises_it(self):
        e = self.escalation()
        for topic in ("RECORD", "record", "Record "):
            e.observe("relix_learn", {"topic": topic}, None, "No page is called 'RECORD'.")
        self.assertEqual(e.temperature, 0.8)

    def test_a_lookup_that_finds_a_page_is_not_a_miss(self):
        e = self.escalation()
        for _ in range(3):
            e.observe("relix_learn", {"topic": "IIf"}, None, "# Name: IIf")
        self.assertEqual(e.temperature, 0.2)


class AModelRepeatingAMissIsTold(unittest.TestCase):

    ALIAS = TemperatureRisesForAStuckModel.ALIAS

    def fail(self, n, error=None, line=6):
        return n.observe("relix_validate", {"script": "…"},
                         (error or self.ALIAS).replace("script:6:", f"script:{line}:"), "Invalid")

    def test_the_third_identical_error_is_answered_with_a_nudge_quoting_it(self):
        n = run_eval.Nudge(3)
        self.assertIsNone(self.fail(n, line=2)); self.assertIsNone(self.fail(n, line=4))
        told = self.fail(n, line=8)
        self.assertIn("3 times in a row", told)
        self.assertIn("write RENAME o (orders)", told)

    def test_it_is_told_again_after_as_many_more(self):
        n = run_eval.Nudge(3)
        told = [self.fail(n) for _ in range(6)]
        self.assertEqual([t is not None for t in told], [False, False, True, False, False, True])

    def test_a_script_that_goes_through_starts_the_count_again(self):
        n = run_eval.Nudge(3)
        self.fail(n); self.fail(n)
        n.observe("relix_run", {"script": "…"}, None, "| a |")
        self.assertIsNone(self.fail(n)); self.assertIsNone(self.fail(n))

    def test_different_errors_are_not_a_repeat(self):
        n = run_eval.Nudge(3)
        self.fail(n); self.fail(n, "script:1:1: error: Expected end of input; found ','")
        self.assertIsNone(self.fail(n))

    def test_a_lookup_that_keeps_finding_no_page_is_told_to_try_the_contents(self):
        n = run_eval.Nudge(2)
        n.observe("relix_learn", {"topic": "RECORD"}, None, "No page is called 'RECORD'.")
        told = n.observe("relix_learn", {"topic": "record"}, None, "No page is called 'record'.")
        self.assertIn("no page for 'record'", told)
        self.assertIn("no topic", told)


class AnInternalServerErrorIsAFailedCall(unittest.TestCase):

    class Server:
        tools = [{"name": "relix_run", "description": "", "inputSchema": {"type": "object"}}]

        def call(self, name, arguments):
            raise run_eval.ServerError("tools/call", {"code": -32603, "message": "No schema annotation"})

    def test_the_question_goes_on_and_the_error_is_kept_with_its_call(self):
        replies = [{"tool_calls": [{"id": "1", "type": "function", "function": {
                       "name": "relix_run", "arguments": '{"script": "query R;"}'}}]},
                   {"content": ""}, {"content": ""}, {"content": ""}]
        real, run_eval.chat = run_eval.chat, lambda api, payload, timeout: {
            "choices": [{"message": replies.pop(0)}]}
        try:
            args = type("Args", (), {"data": "inline", "model": "m", "tool_choice": "auto", "max_turns": 4,
                                     "max_calls": 25, "escalate": None, "nudge_after": None,
                                     "reminders": 0, "on_ask": "continue", "api": "", "timeout": 1})()
            out = run_eval.ask({"instruction": "q", "suite": "s"}, "R := [];", "p", self.Server(), args)
        finally:
            run_eval.chat = real
        self.assertEqual(out["server_errors"], [{"call": 1, "tool": "relix_run", "error": "No schema annotation"}])
        self.assertEqual(out["failures"][0]["call"], 1)
        self.assertEqual(out["events"][1]["arguments"], {"script": "query R;"})


class AStuckAttemptGetsAFreshOne(unittest.TestCase):

    def outcome(self, calls, stop, submission=None, failed_at=()):
        return {"events": [{"tool": "relix_validate"}] * calls, "calls": calls,
                "failures": [{"call": c, "tool": "relix_validate", "error": "e"} for c in failed_at],
                "learn": [], "runs": [], "asks": [], "escalations": [], "nudges": [], "server_errors": [], "recovered": 0, "retries": 0,
                "reminders": 0, "garbled": 0, "seconds": 1.0, "submission": submission,
                "final_text": "", "stop": stop}

    def attempt(self, outcomes, retry_after=16, max_calls=25):
        budgets = []

        def fake_ask(item, data, prompt, server, args, budget=None):
            budgets.append(budget)
            return outcomes.pop(0)
        real, run_eval.ask = run_eval.ask, fake_ask
        try:
            args = type("Args", (), {"retry_after": retry_after, "max_calls": max_calls})()
            return run_eval.attempted({}, "", "", None, args), budgets
        finally:
            run_eval.ask = real

    def test_a_first_attempt_that_runs_out_is_followed_by_a_fresh_one(self):
        answer = {"script": "query R;", "results": []}
        out, budgets = self.attempt([self.outcome(16, "call_limit", failed_at=(3, 4)),
                                     self.outcome(4, "answered", answer, failed_at=(2,))])
        self.assertEqual(budgets, [16, 9])
        self.assertEqual(out["attempts"], 2)
        self.assertEqual(out["calls"], 20)
        self.assertEqual(out["submission"], answer)
        self.assertEqual([f["call"] for f in out["failures"]], [3, 4, 18])
        self.assertIn({"harness": run_eval.SECOND_ATTEMPT}, out["events"])
        self.assertEqual(out["first_stop"], "call_limit")

    def test_a_submitted_answer_is_not_retried_even_if_wrong(self):
        out, budgets = self.attempt([self.outcome(5, "answered", {"script": "x", "results": []})])
        self.assertEqual((out["attempts"], budgets), (1, [16]))

    def test_an_api_error_is_not_retried(self):
        out, budgets = self.attempt([self.outcome(3, "api_error: timed out")])
        self.assertEqual((out["attempts"], budgets), (1, [16]))

    def test_without_the_flag_one_attempt_has_the_whole_budget(self):
        out, budgets = self.attempt([self.outcome(25, "call_limit")], retry_after=None)
        self.assertEqual((out["attempts"], budgets), (1, [None]))


if __name__ == "__main__":
    unittest.main()
