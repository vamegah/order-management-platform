"""Reject missing, empty, failed or skipped JUnit evidence from non-Maven checks."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check(path, minimum=1):
    try:
        cases = ET.parse(path).getroot().findall('.//testcase')
    except (OSError, ET.ParseError) as failure:
        return 0, [f'{path}: {failure}']
    errors = []
    if len(cases) < minimum:
        errors.append(f'{path}: expected at least {minimum} cases, found {len(cases)}')
    for tag in ('failure', 'error', 'skipped'):
        count = sum(case.find(tag) is not None for case in cases)
        if count:
            errors.append(f'{path}: {count} {tag} cases')
    return len(cases), errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--minimum-tests', type=int, default=1)
    args = parser.parse_args()
    if args.minimum_tests < 1:
        parser.error('--minimum-tests must be positive')
    count, errors = check(args.report, args.minimum_tests)
    if errors:
        print('JUnit evidence rejected:\n' + '\n'.join(errors), file=sys.stderr)
        return 1
    print(f'JUnit evidence accepted: {count} executed cases, no failures, errors or skips.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
