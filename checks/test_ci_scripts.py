"""Prevent an assertion failure with adb exit code zero from making CI green."""
import importlib.util
import pathlib
import unittest
from unittest.mock import patch
import subprocess
import tempfile
import contextlib
import io

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

    def test_crash_retains_failure_and_diagnostic(self):
        def adb(command, **kwargs):
            output = '1\n' if 'getprop' in command else ''
            if 'instrument' in command:
                output = 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n'
            if 'logcat' in command and '-d' in command:
                output = 'FATAL EXCEPTION: main\njava.lang.AssertionError: diagnostic fixture\n'
            return subprocess.CompletedProcess(command, 0, output, '')

        with tempfile.TemporaryDirectory() as directory:
            argv = ['android-checks.py', '--device', 'emulator-5554', '--suites', 'visual', '--reports', directory]
            with patch('sys.argv', argv), patch.object(checks.subprocess, 'run', side_effect=adb), contextlib.redirect_stdout(io.StringIO()):
                self.assertTrue(checks.main())
            reports = pathlib.Path(directory)
            self.assertIn('diagnostic fixture', (reports / 'visual-logcat.txt').read_text())
            self.assertIn('failures="1"', (reports / 'junit.xml').read_text())
