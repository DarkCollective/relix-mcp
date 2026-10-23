"""Tests for run_eval's grading. Standard library only:

    python3 -m unittest discover -s tools/model-eval
"""

import unittest

import run_eval

CLUSTER = "query { CLUSTER product_a, product_b AS bundle (also_bought) };"
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

    def test_results_are_not_stacked_for_a_gold_without_grouping_sets(self):
        gold = {k: v for k, v in self.gold.items() if k != "grouping_sets"}
        grade, _ = run_eval.best(gold, [self.by_rating, self.by_category])
        self.assertEqual(grade, "mismatch")


if __name__ == "__main__":
    unittest.main()
