"""Prevent an assertion failure with adb exit code zero from making CI green."""
import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'scripts/android-checks.py'
spec = importlib.util.spec_from_file_location('android_checks', path)
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class InstrumentationResultCheck(unittest.TestCase):
    def test_real_success(self):
        self.assertTrue(checks.passed(0, 'PASS: imports and reading\n'))

    def test_zero_exit_is_not_success(self):
        for output in ['', 'java.lang.AssertionError: chapter anchor\n', 'Process crashed.\n',
                       'INSTRUMENTATION_FAILED: runner missing\n', 'INSTRUMENTATION_CODE: 0\n']:
            self.assertFalse(checks.passed(0, output))

    def test_aborted_or_failed_process(self):
        self.assertFalse(checks.passed(1, 'PASS: partial\n'))
        self.assertFalse(checks.passed(0, 'PASS: partial\nINSTRUMENTATION_ABORTED\n'))
