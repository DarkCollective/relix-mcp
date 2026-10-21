#!/usr/bin/env python3
"""Tests for the eval harness's grading: python3 -m unittest tools/model-eval/test_run_eval.py"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import run_eval  # noqa: E402


def result(*rows, columns=("v",)):
    return {"columns": list(columns), "types": ["NUMBER"] * len(columns),
            "rows": [list(r) for r in rows]}


class BestTest(unittest.TestCase):
    POPULATION = result((2.5187,))
    SAMPLE = result((2.5607,))

    def test_the_gold_reading_matches(self):
        self.assertEqual(run_eval.best([self.POPULATION, self.SAMPLE], [result((2.5187,))])[0],
                         "match")

    def test_another_accepted_reading_matches(self):
        grade, answer = run_eval.best([self.POPULATION, self.SAMPLE], [result((2.5607,))])
        self.assertEqual((grade, answer["rows"]), ("match", [[2.5607]]))

    def test_a_reading_the_question_does_not_list_fails(self):
        self.assertEqual(run_eval.best([self.POPULATION], [result((2.5607,))])[0], "mismatch")

    def test_a_match_on_any_reading_beats_extra_columns_on_another(self):
        wide = result((2.5187, 1), columns=("v", "n"))
        self.assertEqual(run_eval.best([self.POPULATION, self.SAMPLE],
                                       [wide, result((2.5607,))])[0], "match")


class LoadSuiteTest(unittest.TestCase):

    def test_accepted_readings_are_read_with_the_gold(self):
        with tempfile.TemporaryDirectory() as root:
            os.mkdir(os.path.join(root, "s"))
            with open(os.path.join(root, "s", "data.relix"), "w") as f:
                f.write("T := [\n| v |\n|---|\n| 1 |\n];\n")
            with open(os.path.join(root, "s", "queries.json"), "w") as f:
                json.dump([{"instruction": "q", "category": "c",
                            "output": "RELIX EXPRESSION:\nquery { T };\n\nEXPECTED OUTPUT:\nv",
                            "also_accepted": [" query { π v (T) }; ", ""]},
                           {"instruction": "r", "output": "query { T };"}], f)
            _, items = run_eval.load_suite(root, "s")
        self.assertEqual(items[0]["gold"], "query { T };")
        self.assertEqual(items[0]["accepted"], ["query { π v (T) };"])
        self.assertEqual(items[1]["accepted"], [])


if __name__ == "__main__":
    unittest.main()
