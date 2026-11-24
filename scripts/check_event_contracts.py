"""Validate published event fixtures and reject narrowing of a version-one schema."""
import argparse
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

def compatible(old, new, path="envelope"):
    if old.get("type") != new.get("type") or old.get("const") != new.get("const"):
        raise ValueError(path + ": incompatible type or constant")
    if set(new.get("required", ())) != set(old.get("required", ())):
        raise ValueError(path + ": required fields changed")
    if old.get("additionalProperties", True) and not new.get("additionalProperties", True):
        raise ValueError(path + ": unknown fields must remain additive")
    for key in ("format", "pattern", "oneOf", "anyOf", "allOf", "not", "$ref"):
        if old.get(key) != new.get(key):
            raise ValueError(path + ": constraint changed: " + key)
    if "enum" in new and ("enum" not in old or not set(old["enum"]).issubset(new["enum"])):
        raise ValueError(path + ": narrowed enumeration")
    for key in ("minimum", "minLength", "minItems"):
        if new.get(key, float("-inf")) > old.get(key, float("-inf")):
            raise ValueError(path + ": narrowed lower bound")
    for key in ("maximum", "maxLength", "maxItems"):
        if new.get(key, float("inf")) < old.get(key, float("inf")):
            raise ValueError(path + ": narrowed upper bound")
    for name, definition in old.get("properties", {}).items():
        if name not in new.get("properties", {}):
            raise ValueError(path + ": removed field " + name)
        compatible(definition, new["properties"][name], path + "." + name)
    for name, definition in old.get("$defs", {}).items():
        if name not in new.get("$defs", {}):raise ValueError(path+": removed definition "+name)
        compatible(definition,new["$defs"][name],path+".$defs."+name)
    if "items" in old:
        compatible(old["items"], new.get("items", {}), path + "[]")

def check(root, base=None):
    path=Path("contracts/events/envelope-v1.schema.json")
    schema=json.loads((root/path).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    published=json.loads((root/"contracts/events/published/envelope-v1.schema.json").read_text(encoding="utf-8"))
    compatible(published,schema)
    if base and (base/path).exists():
        compatible(json.loads((base/path).read_text(encoding="utf-8")),schema)
        if (base/"contracts/events/published/envelope-v1.schema.json").read_bytes() != (root/"contracts/events/published/envelope-v1.schema.json").read_bytes():
            raise ValueError("Published v1 fixture is immutable")
    for payload in (root/"contracts/events").glob("*-v1.schema.json"):
        current=json.loads(payload.read_text(encoding="utf-8"));Draft202012Validator.check_schema(current)
        frozen=root/"contracts/events/published"/payload.name
        compatible(json.loads(frozen.read_text(encoding="utf-8")),current)
        if base and (base/"contracts/events"/payload.name).exists():
            compatible(json.loads((base/"contracts/events"/payload.name).read_text(encoding="utf-8")),current)
            if frozen.read_bytes()!=(base/"contracts/events/published"/payload.name).read_bytes():raise ValueError("Published payload schema changed")
    fixtures=list((root/"contracts/events/fixtures").glob("*.json"))
    if len(fixtures)<3:raise ValueError("All emitting domains need published fixtures")
    validator=Draft202012Validator(schema,format_checker=FormatChecker())
    for fixture in fixtures:
        validator.validate(json.loads(fixture.read_text(encoding="utf-8")))
    webhook_path=Path("contracts/webhooks/delivery-v1.schema.json")
    webhook_fixtures=[]
    if base and (base/webhook_path).exists() and not (root/webhook_path).exists():
        raise ValueError("Published webhook contract removed")
    if (root/webhook_path).exists():
        current=json.loads((root/webhook_path).read_text(encoding="utf-8"))
        frozen=Path("contracts/webhooks/published/delivery-v1.schema.json")
        Draft202012Validator.check_schema(current)
        compatible(json.loads((root/frozen).read_text(encoding="utf-8")),current,"webhook")
        if base and (base/webhook_path).exists():
            compatible(json.loads((base/webhook_path).read_text(encoding="utf-8")),current,"webhook")
            if (base/frozen).read_bytes() != (root/frozen).read_bytes():
                raise ValueError("Published webhook v1 schema changed")
        webhook_fixtures=list((root/"contracts/webhooks/fixtures").glob("*.json"))
        if not webhook_fixtures:raise ValueError("Webhook fixtures required")
        for fixture in webhook_fixtures:
            Draft202012Validator(current,format_checker=FormatChecker()).validate(json.loads(fixture.read_text(encoding="utf-8")))
    return {"webhookFixtures":len(webhook_fixtures),"fixtures":len(fixtures),"publishedCompatible":True,"baseCompared":bool(base)}

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--base-root",type=Path);parser.add_argument("--root",type=Path,default=Path.cwd());args=parser.parse_args()
    report=check(args.root,args.base_root)
    output=args.root/"coverage/event-contracts.json";output.parent.mkdir(exist_ok=True);output.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report))
