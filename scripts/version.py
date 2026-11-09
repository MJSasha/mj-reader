#!/usr/bin/env python3
"""Read the shared Android version; reject tags that do not match the source."""
import argparse
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def version():
    values = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text().splitlines()
                  if line.strip() and not line.startswith('#'))
    name, code = values['VERSION_NAME'], values['VERSION_CODE']
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', name):
        raise ValueError('VERSION_NAME must be MAJOR.MINOR.PATCH')
    if not code.isdigit() or not 0 < int(code) <= 2100000000:
        raise ValueError('VERSION_CODE must be a positive Android version code')
    return name, int(code)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', help='Require this tag to match version.properties')
    args = parser.parse_args()
    name, code = version()
    if args.tag is not None and args.tag != 'v' + name:
        parser.error(f'Tag must be v{name}, got {args.tag!r}')
    print(f'version={name}\nversion_code={code}\ntag=v{name}')
