"""Require complete SpotBugs evidence, including analysis/classpath failures."""
from pathlib import Path
import argparse
import sys
import xml.etree.ElementTree as ET
from check_verification import NS, reactor_modules


def check(root):
    errors = []
    count = 0
    for module, model in reactor_modules(root):
        if model.findtext('m:packaging', namespaces=NS) == 'pom':
            continue
        if not any((module / 'src/main/java').rglob('*.java')) and not any((module / 'src/main/proto').rglob('*.proto')):
            continue
        path = module / 'target/spotbugsXml.xml'
        try:
            report = ET.parse(path).getroot()
            summary = report.find('FindBugsSummary')
            if summary is None or int(summary.get('total_classes', '0')) <= 0:
                errors.append(f'{module}: no analyzed classes')
            for tag in ('BugInstance', 'Error', 'MissingClass'):
                findings = report.findall('.//' + tag)
                if findings:
                    errors.append(f'{module}: {len(findings)} {tag} entries')
            count += 1
        except (OSError, ET.ParseError, ValueError) as failure:
            errors.append(f'{path}: {failure}')
    if not count:
        errors.append('No static-analysis reports found')
    return count, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    count, errors = check(args.root.resolve())
    if errors:
        print('Static-analysis evidence rejected:\n' + '\n'.join(errors), file=sys.stderr)
        return 1
    print(f'Static-analysis evidence accepted: {count} complete reports, no findings, errors or missing classes.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
