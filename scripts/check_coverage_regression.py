"""Compare clean unit-phase JaCoCo coverage with a PR's exact base revision.

This gate complements verify's absolute combined unit/integration thresholds.
Only generated protobuf/JAXB classes are omitted, equally on both revisions.
No application exclusions or rounded-percentage tolerance are applied.
"""

import argparse
from fractions import Fraction
import json
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET

from check_verification import NS, check, reactor_modules


GENERATED_PREFIXES = ("com/example/omp/proto/", "com/omp/bank/soap/")
SCHEMA = "omp-unit-instruction-coverage-v1"
NEW_MODULE_MINIMUM = Fraction(4, 5)


def instruction_counts(report, required_classes=()):
    """Sum class counters so identical generated-code filtering applies to both sides."""
    covered = missed = 0
    names = set()
    for cls in ET.parse(report).getroot().findall("package/class"):
        name = cls.get("name", "")
        if not name or name in names:
            raise ValueError(f"{report}: missing or duplicate class name")
        names.add(name)
        if name.startswith(GENERATED_PREFIXES):
            continue
        counters = cls.findall("counter[@type='INSTRUCTION']")
        # JaCoCo can omit counters for interfaces and fully filtered classes.
        if not counters:
            continue
        if len(counters) != 1:
            raise ValueError(f"{report}: duplicate instruction counter for {name}")
        c, m = (int(counters[0].attrib[key]) for key in ("covered", "missed"))
        if c < 0 or m < 0:
            raise ValueError(f"{report}: negative instruction count")
        covered += c
        missed += m
    missing = set(required_classes) - names
    if missing:
        raise ValueError(f"{report}: handwritten classes missing from report: {', '.join(sorted(missing))}")
    if covered == 0:
        raise ValueError(f"{report}: no covered handwritten production instructions")
    return {"covered": covered, "missed": missed}


def handwritten_classes(module):
    """Require source class/record/enum files, including uncovered config/DTOs.

    Interfaces without executable code may be absent from JaCoCo reports.
    Generated sources are outside src/main/java and handled by the prefix policy.
    """
    for source in (module / "src/main/java").rglob("*.java"):
        code = source.read_text(encoding="utf-8")
        if not re.search(r"\b(?:class|record|enum)\s+" + re.escape(source.stem) + r"\b", code):
            continue
        package = re.search(r"^\s*package\s+([\w.]+)\s*;", code, re.MULTILINE)
        name = (package[1].replace(".", "/") + "/" if package else "") + source.stem
        if not name.startswith(GENERATED_PREFIXES):
            yield name


def snapshot(root, revision):
    count, errors = check(root, include_integration=False)
    if errors:
        raise ValueError("Incomplete unit evidence:\n" + "\n".join(errors))
    modules = {}
    for module, pom in reactor_modules(root):
        # Maven does not compile the scaffolding sources in pom-packaged aggregators.
        if pom.findtext("m:packaging", namespaces=NS) == "pom":
            continue
        if not any((module / "src/main/java").rglob("*.java")):
            continue
        label = module.relative_to(root).as_posix()
        modules[label] = instruction_counts(
            module / "target/site/jacoco/jacoco.xml", handwritten_classes(module))
    if not modules or count == 0:
        raise ValueError("No production modules or executed unit tests")
    return {"schema": SCHEMA, "revision": revision, "tests": count, "modules": modules}


def validate_snapshot(data):
    if data.get("schema") != SCHEMA:
        raise ValueError("Coverage snapshot schema/measurement policy mismatch")
    if not isinstance(data.get("revision"), str) or not data["revision"].strip():
        raise ValueError("Coverage snapshot must identify its revision")
    if type(data.get("tests")) is not int or data["tests"] <= 0:
        raise ValueError("Coverage snapshot must contain executed tests")
    modules = data.get("modules")
    if not isinstance(modules, dict) or not modules:
        raise ValueError("Coverage snapshot has no modules")
    for name, counts in modules.items():
        path = PurePosixPath(name)
        if not name or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
            raise ValueError(f"Invalid module path: {name}")
        if not isinstance(counts, dict) or set(counts) != {"covered", "missed"}:
            raise ValueError(f"{name}: invalid instruction counters")
        if any(type(n) is not int or n < 0 for n in counts.values()) or counts["covered"] == 0:
            raise ValueError(f"{name}: invalid or unmeasured coverage")
    return modules


def ratio(counts):
    return Fraction(counts["covered"], counts["covered"] + counts["missed"])


def compare(base, head):
    before, after = validate_snapshot(base), validate_snapshot(head)
    rows, errors = [], []
    for module in sorted(before.keys() | after.keys()):
        if module not in after:
            errors.append(f"{module}: baseline module is missing from head coverage")
            rows.append((module, ratio(before[module]), None, "MISSING"))
            continue
        new = ratio(after[module])
        if module not in before:
            good = new >= NEW_MODULE_MINIMUM
            if not good:
                errors.append(f"{module}: new module needs at least 80% unit instruction coverage")
            rows.append((module, None, new, "NEW" if good else "FAIL"))
            continue
        old = ratio(before[module])
        if new < old:
            errors.append(f"{module}: unit instruction coverage regressed")
        rows.append((module, old, new, "PASS" if new >= old else "FAIL"))
    return rows, errors


def markdown(rows):
    lines = ["| Module | Base unit coverage | Head unit coverage | Result |",
             "|---|---:|---:|---|"]
    for module, old, new, result in rows:
        values = ["—" if n is None else f"{float(n) * 100:.6f}%" for n in (old, new)]
        lines.append(f"| {module} | {values[0]} | {values[1]} | {result} |")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("snapshot")
    export.add_argument("--root", type=Path, required=True)
    export.add_argument("--revision", required=True)
    export.add_argument("--output", type=Path, required=True)
    diff = commands.add_parser("compare")
    diff.add_argument("--base", type=Path, required=True)
    diff.add_argument("--head", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "snapshot":
            data = snapshot(args.root.resolve(), args.revision)
            validate_snapshot(data)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
            print(f"Measured {len(data['modules'])} modules and {data['tests']} unit tests.")
            return 0
        base = json.loads(args.base.read_text(encoding="utf-8"))
        head = json.loads(args.head.read_text(encoding="utf-8"))
        rows, errors = compare(base, head)
        print(markdown(rows))
        if errors:
            print("Coverage regression rejected:\n" + "\n".join(errors), file=sys.stderr)
            return 1
        return 0
    except (ET.ParseError, OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        print(f"Invalid coverage evidence: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
