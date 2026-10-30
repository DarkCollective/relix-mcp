"""Tests for run_eval's grading. Standard library only:

    python3 -m unittest discover -s tools/model-eval
"""

import io
import os
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


class AnInstantIsTheSameInAnyZone(unittest.TestCase):

    gold = result(["visit_id", "visit_at_tz"], ["NUMBER", "TIMESTAMPTZ"],
                  [[1, "2026-03-02T15:30:00Z"], [3, "2026-05-18T08:15:00Z"]])

    def test_the_same_instants_at_another_offset_match(self):
        answer = result(["visit_id", "visit_at_tz"], ["NUMBER", "TIMESTAMPTZ"],
                        [[1, "2026-03-02T10:30:00-05:00"], [3, "2026-05-18T04:15:00-04:00"]])
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_the_wall_clock_time_is_not_the_instant(self):
        answer = result(["visit_id", "visit_at_tz"], ["NUMBER", "TIMESTAMP"],
                        [[1, "2026-03-02T10:30:00Z"], [3, "2026-05-18T04:15:00Z"]])
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")


TREE = "query {\n    TREE device_id BY parent_device_id AS children (mesh)\n};"


class TreeSiblingsMayComeInAnyOrder(unittest.TestCase):

    gold = run_eval.graded_as(TREE, result(
        ["device_id", "parent_device_id", "children"], ["NUMBER", "NUMBER", "ANY"],
        [[6000, None, "[{device_id: 6001, parent_device_id: 6000, children: [{device_id: 502, "
                      "parent_device_id: 6001, children: []}]}, {device_id: 501, "
                      "parent_device_id: 6000, children: []}]"]]))

    def answer(self, children):
        return result(["device_id", "parent_device_id", "children"], ["NUMBER", "NUMBER", "ANY"],
                      [[6000, None, children]])

    def test_the_gold_names_its_children_column(self):
        self.assertEqual(self.gold["unordered"], ["children"])

    def test_siblings_in_another_order_match(self):
        answer = self.answer("[{device_id: 501, parent_device_id: 6000, children: []}, {device_id: 6001, "
                             "parent_device_id: 6000, children: [{device_id: 502, parent_device_id: 6001, "
                             "children: []}]}]")
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_a_child_under_another_parent_does_not_match(self):
        answer = self.answer("[{device_id: 501, parent_device_id: 6000, children: [{device_id: 502, "
                             "parent_device_id: 6001, children: []}]}, {device_id: 6001, "
                             "parent_device_id: 6000, children: []}]")
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")

    def test_a_tree_with_order_keeps_its_order(self):
        ordered = TREE.replace("AS children", "ORDER device_id DESC AS children")
        plain = {k: v for k, v in self.gold.items() if k != "unordered"}
        self.assertNotIn("unordered", run_eval.graded_as(ordered, plain))


ROLLUP_LEVEL = "query {\n    γ ROLLUP (floor), COUNT(*) → device_count, GROUPING(floor) → level (devices)\n};"


class AGroupingLevelMayBeLabelledAnyWay(unittest.TestCase):

    gold = run_eval.graded_as(ROLLUP_LEVEL, result(
        ["floor", "device_count", "level"], ["NUMBER", "NUMBER", "NUMBER"],
        [[0, 12, 0], [1, 8, 0], [None, 20, 1]]))

    def test_the_gold_names_its_level_column(self):
        self.assertEqual(self.gold["partition"], "level")

    def test_labels_that_tell_the_levels_apart_match(self):
        answer = result(["floor", "device_count", "level"], ["NUMBER", "NUMBER", "STRING"],
                        [[0, 12, "floor"], [1, 8, "floor"], [None, 20, "grand total"]])
        self.assertEqual(run_eval.compare(self.gold, answer), "match")

    def test_one_label_for_every_level_does_not_match(self):
        answer = result(["floor", "device_count", "level"], ["NUMBER", "NUMBER", "STRING"],
                        [[0, 12, "row"], [1, 8, "row"], [None, 20, "row"]])
        self.assertEqual(run_eval.compare(self.gold, answer), "mismatch")


BASKET = ("Names := { γ order_id, COLLECT(name) → names (order_items ⋈ π product_id, name (products)) };\n"
          "query {\n    π order_id, ARRAY_TO_STRING(names, ' + ') → basket (Names)\n};")
DEVICE_NAMES = "query {\n    γ room_id, COLLECT(name) → device_names (devices)\n};"


class AggregatedItemsMayComeInAnyOrder(unittest.TestCase):

    basket = run_eval.graded_as(BASKET, result(
        ["order_id", "basket"], ["NUMBER", "STRING"],
        [[1002, "Wireless Mouse + The Relix Handbook"], [1003, "The Relix Handbook"]]))
    names = run_eval.graded_as(DEVICE_NAMES, result(
        ["room_id", "device_names"], ["NUMBER", "ANY"], [[202, "[Kitchen Device 2, Kitchen Device 1]"]]))

    def test_the_golds_name_their_columns(self):
        self.assertEqual(self.basket["joined"], {"basket": " + "})
        self.assertEqual(self.names["unordered"], ["device_names"])

    def test_a_joined_string_in_another_order_matches(self):
        answer = result(["order_id", "basket"], ["NUMBER", "STRING"],
                        [[1002, "The Relix Handbook + Wireless Mouse"], [1003, "The Relix Handbook"]])
        self.assertEqual(run_eval.compare(self.basket, answer), "match")

    def test_another_separator_does_not_match(self):
        answer = result(["order_id", "basket"], ["NUMBER", "STRING"],
                        [[1002, "Wireless Mouse, The Relix Handbook"], [1003, "The Relix Handbook"]])
        self.assertEqual(run_eval.compare(self.basket, answer), "mismatch")

    def test_a_string_agg_without_order_is_joined(self):
        gold = run_eval.graded_as("query { γ order_id, STRING_AGG(name, ' + ') → basket (Items) };",
                                  result(["order_id", "basket"], ["NUMBER", "STRING"], [[1002, "b + a"]]))
        self.assertEqual(gold["joined"], {"basket": " + "})

    def test_an_ordered_aggregate_keeps_its_order(self):
        for gold in ("query { γ order_id, STRING_AGG(name, ' + ' ORDER BY name) → basket (Items) };",
                     "query { γ room_id, COLLECT(name ORDER BY name) → device_names (devices) };"):
            marked = run_eval.graded_as(gold, result(["order_id", "basket", "room_id", "device_names"],
                                                     ["NUMBER"] * 4, []))
            self.assertNotIn("joined", marked)
            self.assertNotIn("unordered", marked)

    def test_an_array_in_another_order_matches(self):
        answer = result(["room_id", "device_names"], ["NUMBER", "ANY"], [[202, "[Kitchen Device 1, Kitchen Device 2]"]])
        self.assertEqual(run_eval.compare(self.names, answer), "match")

    def test_an_array_missing_an_item_does_not_match(self):
        answer = result(["room_id", "device_names"], ["NUMBER", "ANY"], [[202, "[Kitchen Device 1]"]])
        self.assertEqual(run_eval.compare(self.names, answer), "mismatch")


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
        real, run_eval.chat = run_eval.chat, lambda api, payload, timeout, key=None: {
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


class AHostedModelGetsOnlyWhatEveryApiAccepts(unittest.TestCase):

    def args(self, **kw):
        return type("Args", (), {"hosted": None, "reasoning_effort": None, "temperature": None, **kw})()

    def test_lm_studio_gets_its_preset(self):
        self.assertIs(run_eval.sampled(self.args()), run_eval.SAMPLING)

    def test_a_hosted_api_gets_only_a_named_temperature_and_effort(self):
        self.assertEqual(run_eval.sampled(self.args(hosted="KEY")), {})
        self.assertEqual(run_eval.sampled(self.args(hosted="KEY", temperature=0.2)), {"temperature": 0.2})
        self.assertEqual(run_eval.sampled(self.args(hosted="KEY", reasoning_effort="high"))["reasoning_effort"],
                         "high")

    def test_the_key_is_sent_as_a_bearer_token(self):
        sent = []

        class Response(io.BytesIO):
            def __enter__(self): return self
            def __exit__(self, *a): return False

        real = run_eval.urllib.request.urlopen
        run_eval.urllib.request.urlopen = lambda request, timeout: sent.append(request) or Response(b"{}")
        try:
            run_eval.chat("https://api", {}, 1, "secret")
            run_eval.chat("http://local", {}, 1)
        finally:
            run_eval.urllib.request.urlopen = real
        self.assertEqual(sent[0].get_header("Authorization"), "Bearer secret")
        self.assertIsNone(sent[1].get_header("Authorization"))


class PagesAreRetrievedForTheQuestion(unittest.TestCase):

    SEARCH = ("No page is called 'q'.\n\nThe ordering functions; ask learn for one by its name:\nGREATEST, LEAST\n\n"
              " These pages mention it, most relevant first; ask learn for one by the name in brackets:\n\n"
              "- Window Offset [operators/window-offset.md]: LAG, LEAD\n  > WINDOW LAG(x) SORT t AS p (R)\n"
              "- MIN [aggregates/min.md]: Minimum value\n  > γ k, MIN(x) → m (R)\n"
              "- Sort [operators/sort.md]: Sort rows\n")

    class Server:
        tools = [{"name": "relix_learn", "description": "", "inputSchema": {"type": "object"}}]

        def __init__(self):
            self.asked = []

        def call(self, name, arguments):
            self.asked.append(arguments)
            text = PagesAreRetrievedForTheQuestion.SEARCH if "detail" not in arguments \
                else f"# Name: {arguments['topic']}"
            return {"content": [{"type": "text", "text": text}]}

    def test_the_first_k_hits_are_fetched_brief(self):
        server = self.Server()
        paths, pages = run_eval.retrieved(server, "q", 2)
        self.assertEqual(paths, ["operators/window-offset.md", "aggregates/min.md"])
        self.assertEqual(server.asked[1:], [{"topic": p, "detail": "brief"} for p in paths])
        self.assertTrue(pages.startswith(run_eval.RETRIEVED))
        self.assertIn("# Name: aggregates/min.md", pages)

    def test_the_pages_come_before_the_question_and_are_recorded(self):
        sent = []
        real, run_eval.chat = run_eval.chat, lambda api, payload, timeout, key=None: (
            sent.append(payload["messages"][1]["content"]) or {"choices": [{"message": {"content": ""}}]})
        try:
            args = type("Args", (), {"data": "inline", "model": "m", "tool_choice": "auto", "max_turns": 1,
                                     "max_calls": 25, "escalate": None, "nudge_after": None,
                                     "reminders": 0, "on_ask": "continue", "api": "", "timeout": 1,
                                     "retrieve": 1})()
            out = run_eval.ask({"instruction": "the question", "suite": "s"}, "R := [];", "p", self.Server(), args)
        finally:
            run_eval.chat = real
        self.assertEqual(out["retrieved"], ["operators/window-offset.md"])
        self.assertLess(sent[0].index(run_eval.RETRIEVED), sent[0].index("the question"))


class TheCatalogueNamesItsPages(unittest.TestCase):

    def test_every_operator_line_names_a_page(self):
        with open(os.path.join(run_eval.HERE, "operator-catalogue.txt")) as f:
            text = f.read()
        lines = text.split("\n- ")[1:]
        self.assertTrue(lines)
        for line in lines:
            self.assertTrue(run_eval.catalogue_pages(line), line)


if __name__ == "__main__":
    unittest.main()
