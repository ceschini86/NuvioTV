import unittest

from fork_resolve_version_conflict import resolve


class ForkResolveVersionConflictTest(unittest.TestCase):
    def test_version_only_conflict_keeps_the_fork_side(self):
        text = (
            "defaultConfig {\n"
            "<<<<<<< HEAD\n"
            "        versionCode = 1082\n"
            '        versionName = "1.2.0"\n'
            "=======\n"
            "        versionCode = 1090\n"
            '        versionName = "1.2.0"\n'
            ">>>>>>> upstream-stable-1.2.0\n"
            "}\n"
        )
        self.assertEqual(
            resolve(text),
            'defaultConfig {\n        versionCode = 1082\n        versionName = "1.2.0"\n}\n',
        )

    def test_other_conflicts_are_left_for_a_human(self):
        text = (
            "<<<<<<< HEAD\n"
            "        versionCode = 1082\n"
            "=======\n"
            "        versionCode = 1090\n"
            ">>>>>>> upstream\n"
            "<<<<<<< HEAD\n"
            '    implementation("a:b:1")\n'
            "=======\n"
            '    implementation("a:b:2")\n'
            ">>>>>>> upstream\n"
        )
        self.assertIsNone(resolve(text))

    def test_diff3_base_section_is_skipped(self):
        text = (
            "<<<<<<< HEAD\n"
            "        versionCode = 1082\n"
            "||||||| base\n"
            "        versionCode = 1064\n"
            "=======\n"
            "        versionCode = 1077\n"
            ">>>>>>> upstream\n"
        )
        self.assertEqual(resolve(text), "        versionCode = 1082\n")

    def test_no_conflict_means_nothing_to_resolve(self):
        self.assertIsNone(resolve("versionCode = 1082\n"))


if __name__ == "__main__":
    unittest.main()
