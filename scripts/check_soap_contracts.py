"""Freeze the published SOAP v1 wire contract and require identical bank/client copies.

Whitespace, namespace prefixes, annotations and deployment addresses may change.
Other v1 changes require a new version; the existing endpoint remains supported.
JAXB generation and Java compilation are enforced separately by Maven verification.
"""

import argparse
from io import StringIO
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


XSD = "http://www.w3.org/2001/XMLSchema"
WSDL = "http://schemas.xmlsoap.org/wsdl/"
SOAP = "http://schemas.xmlsoap.org/wsdl/soap/"
MODULES = ("legacy-bank-service", "legacy-banking-adapter")
CONTRACTS = {"banking.xsd": "xsd/banking.xsd", "legacy-bank.wsdl": "wsdl/legacy-bank.wsdl"}
QNAME_ATTRIBUTES = {"type", "ref", "base", "element", "message", "binding", "itemType", "substitutionGroup"}
IGNORED = {f"{{{XSD}}}annotation", f"{{{WSDL}}}documentation"}


def normalized(path, schema_only=False, seen=()):
    """Resolve QName attributes in their own namespace scope before comparison."""
    path = path.resolve()
    if path in seen:
        raise ValueError(f"{path}: circular schema import")
    scopes = []
    pending = []
    for event, value in ET.iterparse(StringIO(path.read_text(encoding="utf-8")),
                                     events=("start-ns", "start", "end")):
        if event == "start-ns":
            pending.append(value)
        elif event == "start":
            scope = dict(scopes[-1]) if scopes else {"xml": "http://www.w3.org/XML/1998/namespace"}
            scope.update(pending)
            pending.clear()
            scopes.append(scope)
            for key, text in list(value.attrib.items()):
                if key in QNAME_ATTRIBUTES or key == "memberTypes":
                    names = []
                    for name in text.split():
                        prefix, separator, local = name.partition(":")
                        if separator:
                            if prefix not in scope:
                                raise ValueError(f"{path}: undeclared QName prefix {prefix}")
                            names.append(f"{{{scope[prefix]}}}{local}")
                        else:
                            names.append(f"{{{scope.get('', '')}}}{name}")
                    value.set(key, " ".join(names))
        else:
            scopes.pop()
            root = value
    if schema_only:
        schemas = root.findall(f"{{{WSDL}}}types/{{{XSD}}}schema")
        if len(schemas) != 1:
            raise ValueError(f"{path}: expected one embedded banking schema")
        root = schemas[0]

    def structure(node):
        if (node.tag == f"{{{XSD}}}schema" and len(node) == 1
                and node[0].tag == f"{{{XSD}}}import"):
            location = node[0].get("schemaLocation", "")
            imported = (path.parent / location).resolve()
            if not location or ":" in location or not imported.is_relative_to(path.parent.parent):
                raise ValueError(f"{path}: schema imports must stay in the local contract directory")
            result = normalized(imported, seen=(*seen, path))
            if dict(result[1]).get("targetNamespace") != node[0].get("namespace"):
                raise ValueError(f"{path}: schema import namespace mismatch")
            return result
        attributes = dict(node.attrib)
        if node.tag == f"{{{SOAP}}}address":
            attributes.pop("location", None)
        return (node.tag, tuple(sorted(attributes.items())), (node.text or "").strip(),
                tuple(structure(child) for child in node if child.tag not in IGNORED))

    return structure(root)


def check(root, base_root=None):
    errors = []
    for name, relative in CONTRACTS.items():
        fixture = root / "contracts/soap/v1" / name
        baseline = normalized(fixture)
        if base_root is not None:
            previous_fixture = base_root / "contracts/soap/v1" / name
            # Older branches may predate fixtures; their producer contract is still binding.
            previous = previous_fixture if previous_fixture.is_file() else (
                base_root / MODULES[0] / "src/main/resources" / relative)
            if normalized(previous) != baseline:
                errors.append(f"{name}: published v1 baseline changed from the pull request base")
        for module in MODULES:
            current = root / module / "src/main/resources" / relative
            if normalized(current) != baseline:
                errors.append(f"{module}: {name} differs from the published v1 contract")
            if name.endswith(".wsdl"):
                schema = root / module / "src/main/resources/xsd/banking.xsd"
                if normalized(current, schema_only=True) != normalized(schema):
                    errors.append(f"{module}: WSDL embedded schema differs from banking.xsd")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--base-root", type=Path)
    args = parser.parse_args(argv)
    try:
        errors = check(args.root, args.base_root)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"SOAP contract evidence rejected: {error}", file=sys.stderr)
        return 1
    if errors:
        print("SOAP contract drift rejected:\n" + "\n".join(errors), file=sys.stderr)
        return 1
    print("SOAP v1 WSDL/XSD, producer/client copies and embedded schemas match.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
