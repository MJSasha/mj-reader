#!/usr/bin/env python3
"""Run MJReader's custom instrumentation on an explicitly selected emulator."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNNER = 'com.mjreader.test/com.mjreader.ReaderSmokeTest'
SUITES = ('smoke', 'catalog', 'visual', 'adaptive', 'updates')


def passed(returncode, output):
    # `adb am instrument -w` can exit zero after an assertion failure.
    return returncode == 0 and any(line.startswith('PASS: ') for line in output.splitlines()) \
        and 'INSTRUMENTATION_FAILED' not in output and 'INSTRUMENTATION_ABORTED' not in output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True, help='Explicit emulator serial, never a physical phone')
    parser.add_argument('--suites', default=','.join(SUITES))
    parser.add_argument('--reset-app', action='store_true', help='Erase MJReader test data before each suite')
    parser.add_argument('--reports', type=pathlib.Path, default=ROOT / 'dist/reports/android')
    args = parser.parse_args()
    suites = args.suites.split(',')
    if not args.device.startswith('emulator-') or any(s not in SUITES for s in suites):
        parser.error('Use an emulator serial and suites: ' + ','.join(SUITES))
    adb = ['adb', '-s', args.device]

    def run(*command, check=True, timeout=60):
        return subprocess.run(adb + list(command), text=True, capture_output=True, check=check, timeout=timeout)

    if run('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
        parser.error('Refusing to modify a non-emulator device')
    args.reports.mkdir(parents=True, exist_ok=True)
    report = ET.Element('testsuite', name='MJReader Android')
    failures = 0
    for suite in suites:
        started = time.monotonic()
        case = ET.SubElement(report, 'testcase', name=suite, classname='com.mjreader.ReaderSmokeTest')
        output = ''
        try:
            if args.reset_app:
                run('shell', 'pm', 'clear', 'com.mjreader')
            run('shell', 'wm', 'size', '430x932')
            run('shell', 'wm', 'density', '160')
            run('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
            run('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
            run('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1')
            run('shell', 'cmd', 'locale', 'set-app-locales', 'com.mjreader', '--locales', 'en-US')
            command = ['shell', 'am', 'instrument', '-w']
            if suite != 'smoke':
                command += ['-e', suite, 'true']
            result = run(*command, RUNNER, check=False, timeout=300)
            output = result.stdout + result.stderr
            print(output, flush=True)
            if not passed(result.returncode, output):
                raise RuntimeError('Instrumentation did not report PASS')
        except Exception as error:
            failures += 1
            ET.SubElement(case, 'failure', message=str(error)).text = output or repr(error)
            print(f'FAIL: {suite}: {error}', flush=True)
        finally:
            case.set('time', f'{time.monotonic() - started:.3f}')
            ET.SubElement(case, 'system-out').text = output
            (args.reports / f'{suite}.log').write_text(output)
            try:
                names = run('exec-out', 'run-as', 'com.mjreader', 'ls', '-1', 'files').stdout.splitlines()
                directory = args.reports / 'screenshots' / suite
                directory.mkdir(parents=True, exist_ok=True)
                for name in names:
                    if not re.fullmatch(r'qa-[\w.-]+\.png', name):
                        continue
                    image = subprocess.run(adb + ['exec-out', 'run-as', 'com.mjreader', 'cat', 'files/' + name],
                                           capture_output=True, check=True, timeout=20).stdout
                    (directory / name).write_bytes(image)
            except Exception as error:
                print(f'Screenshot collection: {error}', flush=True)
            report.set('tests', str(len(report)))
            report.set('failures', str(failures))
            ET.ElementTree(report).write(args.reports / 'junit.xml', encoding='utf-8', xml_declaration=True)
        if failures:
            break
    return bool(failures)


if __name__ == '__main__':
    raise SystemExit(main())
