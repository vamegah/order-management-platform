"""Require complete Maven test and coverage evidence after a clean verify.

Uses only Python's standard library. Run from any directory; source discovery
checks the reactor's *Test/*Tests/*IT suites so a missing plugin/report cannot
silently turn a build green. E2e suites are required with --include-e2e;
isolated scheduled recovery additionally requires --include-recovery-e2e.
"""

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def reactor_modules(root):
    """Follow declared reactor paths, including nested aggregators."""
    pom = ET.parse(root / "pom.xml").getroot()
    yield root, pom
    for module in pom.findall("m:modules/m:module", NS):
        yield from reactor_modules(root / module.text.strip())


def expected_suites(module, include_e2e, include_recovery_e2e=False):
    for source in (module / "src/test/java").rglob("*.java"):
        name = source.stem
        if not name.endswith(("Test", "Tests", "IT")):
            continue
        code = source.read_text(encoding="utf-8")
        if re.search(r"\babstract\s+class\s+" + re.escape(name) + r"\b", code):
            continue
        if not include_e2e and re.search(r'@Tag\(\s*"e2e"\s*\)', code):
            continue
        if not include_recovery_e2e and re.search(r'@Tag\(\s*"recovery-e2e"\s*\)', code):
            continue
        package = re.search(r"^package\s+([\w.]+)\s*;", code, re.MULTILINE)
        if package:
            phase = "failsafe" if name.endswith("IT") else "surefire"
            yield phase, f"{package[1]}.{name}"


def check(root, include_e2e=False, include_integration=True, include_recovery_e2e=False):
    errors = []
    test_count = 0
    for module, pom in reactor_modules(root):
        label = module.relative_to(root).as_posix()
        reports = {}
        for phase in ("surefire", "failsafe"):
            if phase == "failsafe" and not include_integration:
                continue
            for report in (module / f"target/{phase}-reports").glob("TEST-*.xml"):
                suite = ET.parse(report).getroot()
                name = suite.get("name", "")
                tests = int(suite.get("tests", "0"))
                reports[phase, name] = tests
                test_count += tests
                for result in ("failures", "errors", "skipped"):
                    count = int(suite.get(result, "0"))
                    if count:
                        errors.append(f"{label}: {name}: {count} {result}")

        # Maven never compiles src/test in a pom-packaged aggregator.
        suites = () if pom.findtext("m:packaging", namespaces=NS) == "pom" else expected_suites(module, include_e2e, include_recovery_e2e)
        for phase, name in suites:
            if phase == "failsafe" and not include_integration:
                continue
            # JUnit nested suites may have only Outer$Nested reports.
            runs = sum(count for (kind, suite), count in reports.items()
                       if kind == phase and (suite == name or suite.startswith(name + "$")))
            if runs == 0:
                errors.append(f"{label}: missing/nonexecuted {phase} suite {name}")

        plugins = pom.findall("m:build/m:plugins/m:plugin", NS)
        coverage_required = any(
            plugin.findtext("m:artifactId", namespaces=NS) == "jacoco-maven-plugin"
            and plugin.findall("m:executions/m:execution/m:goals/m:goal", NS)
            for plugin in plugins
        )
        if coverage_required:
            report = module / "target/site/jacoco/jacoco.xml"
            if not report.is_file():
                errors.append(f"{label}: missing JaCoCo XML (coverage was not measured)")
            else:
                counter = ET.parse(report).getroot().find("counter[@type='INSTRUCTION']")
                if counter is None or int(counter.get("covered", "0")) == 0:
                    errors.append(f"{label}: no covered production instructions")
    return test_count, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--include-e2e", action="store_true")
    parser.add_argument("--include-recovery-e2e", action="store_true")
    args = parser.parse_args()
    try:
        count, errors = check(args.root.resolve(), args.include_e2e,
                              include_recovery_e2e=args.include_recovery_e2e)
    except (ET.ParseError, OSError, ValueError) as error:
        print(f"Invalid verification evidence: {error}", file=sys.stderr)
        return 1
    if errors:
        print("Verification evidence rejected:\n" + "\n".join(errors), file=sys.stderr)
        return 1
    print(f"Verification evidence accepted: {count} tests, no failures, errors, skips or missing suites.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
