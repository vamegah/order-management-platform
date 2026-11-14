"""Validate published APIs, compare independent producer exports and enforce v1 compatibility.

Simple schema changes use request/response variance. Changes to complex JSON Schema
constraints that cannot be proved compatible are rejected conservatively. A new v2
contract never replaces a supported v1 contract. Remote schema references are forbidden.
"""
import argparse
import json
from decimal import Decimal
from pathlib import Path
import sys

from graphql import (GraphQLError, build_schema, find_breaking_changes, find_dangerous_changes,
                     get_named_type, is_enum_type, is_union_type, is_object_type, is_interface_type,
                     lexicographic_sort_schema, print_schema)
from openapi_spec_validator import validate
from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

API_FILES = {
    "product-service": "product.json",
    "customer-service": "customer.json",
    "order-service": "order.json",
    "notification-service": "webhooks.json",
    "payment-service": "payment.json",
    "legacy-banking-adapter": "banking.json",
}
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
DOC_KEYS = {"title", "description", "summary", "example", "examples", "deprecated", "externalDocs"}
SCHEMA_KEYS = {"$ref", "type", "enum", "properties", "required", "items", "additionalProperties",
               "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
               "minItems", "maxItems", "minProperties", "maxProperties", "multipleOf"}
SCALAR_TYPES = {"string", "number", "integer", "boolean", "object", "array", "null"}


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


NAMED_MAPS = {"properties", "schemas", "paths", "responses", "headers", "content", "securitySchemes", "security"}


def semantic(value, names=False):
    if isinstance(value, dict):
        return {key: semantic(item, not names and key in NAMED_MAPS)
                for key, item in sorted(value.items())
                if names or (key not in DOC_KEYS and not key.startswith("x-"))}
    if isinstance(value, list):
        return [semantic(item, names) for item in value]
    return value


def pointer(document, reference):
    if not reference.startswith("#/"):
        raise ValueError("Only document-local references are supported: " + reference)
    value = document
    for part in reference[2:].split("/"):
        value = value[part.replace("~1", "/").replace("~0", "~")]
    return value


def walk(value):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def operations(document):
    return {(method.upper(), path): operation for path, item in document.get("paths", {}).items()
            for method, operation in item.items() if method in HTTP_METHODS}


def validate_openapi(document):
    if not str(document.get("openapi", "")).startswith("3.1."):
        raise ValueError("Published HTTP contracts must use OpenAPI 3.1")
    for node in walk(document):
        if isinstance(node, dict) and "$ref" in node:
            pointer(document, node["$ref"])
    exposed = operations(document)
    inventory = document.get("x-implemented-routes")
    if not inventory or len(inventory) != len(set(inventory)):
        raise ValueError("A complete, unique implementation route inventory is required")
    declared = {method + " " + path for method, path in exposed}
    if set(inventory) != declared:
        raise ValueError("Undeclared or missing routes: " + repr(sorted(set(inventory) ^ declared)))
    identities = [operation.get("operationId") for operation in exposed.values()]
    if not all(identities) or len(identities) != len(set(identities)):
        raise ValueError("Operation IDs must be present and unique")
    for route, operation in exposed.items():
        if not operation.get("security", document.get("security")):
            raise ValueError(f"{route}: authentication must be documented")
    if not document.get("components", {}).get("schemas"):
        raise ValueError("Typed component schemas are required")
    try:
        validate(document)
    except Exception as error:
        raise ValueError("Invalid OpenAPI document: " + str(error).splitlines()[0]) from error


class Compatibility:
    def __init__(self, before, after):
        self.before, self.after, self.errors = before, after, []

    def fail(self, where, reason):
        self.errors.append(f"{where}: {reason}")

    def equal(self, before, after, where):
        if semantic(before) != semantic(after):
            self.fail(where, "existing wire behavior changed")

    @staticmethod
    def types(schema):
        value = schema.get("type")
        return set(value if isinstance(value, list) else [value]) if value else SCALAR_TYPES

    @staticmethod
    def subset(left, right):
        return all(value in right or (value == "integer" and "number" in right) for value in left)

    def schema(self, before, after, where, direction, seen=frozenset()):
        if before is True:
            before = {}
        if after is True:
            after = {}
        if before is False or after is False:
            if before != after:
                self.fail(where, "boolean schema changed")
            return
        before, after = dict(before or {}), dict(after or {})
        references = (before.get("$ref"), after.get("$ref"), direction)
        if references[0] or references[1]:
            if references[0] != references[1]:
                self.fail(where, "published model identity changed")
                return
            expanded_seen = seen | {references}
            self.schema({k:v for k,v in before.items() if k != "$ref"},
                        {k:v for k,v in after.items() if k != "$ref"},
                        where + " reference constraints", direction, expanded_seen)
            if references not in seen:
                self.schema(pointer(self.before, references[0]), pointer(self.after, references[1]),
                            where, direction, expanded_seen)
            return
        old_types, new_types = self.types(before), self.types(after)
        left, right = (old_types, new_types) if direction == "request" else (new_types, old_types)
        if not self.subset(left, right):
            self.fail(where, direction + " type changed incompatibly")
        old_enum, new_enum = before.get("enum"), after.get("enum")
        if old_enum is not None or new_enum is not None:
            first, second = (old_enum, new_enum) if direction == "request" else (new_enum, old_enum)
            if second is not None and (first is None or not {json.dumps(x,sort_keys=True) for x in first}
                                                      <= {json.dumps(x,sort_keys=True) for x in second}):
                self.fail(where, direction + " enum changed incompatibly")
        old_properties, new_properties = before.get("properties", {}), after.get("properties", {})
        for name, schema in old_properties.items():
            if name not in new_properties:
                self.fail(where + "." + name, "published property removed")
            else:
                self.schema(schema, new_properties[name], where + "." + name, direction, seen)
        old_required, new_required = set(before.get("required", [])), set(after.get("required", []))
        if direction == "request" and not new_required <= old_required:
            self.fail(where, "new required input properties")
        if direction == "response" and not old_required <= new_required:
            self.fail(where, "required response properties became optional")
        if direction == "response" and before.get("additionalProperties") is False and new_properties.keys() - old_properties.keys():
            self.fail(where, "new response properties violate the old closed object")
        if "items" in before or "items" in after:
            self.schema(before.get("items", {}), after.get("items", {}), where + "[]", direction, seen)
        old_additional, new_additional = before.get("additionalProperties", True), after.get("additionalProperties", True)
        if old_additional != new_additional:
            if old_additional is False:
                if direction == "response": self.fail(where, "response additional properties widened")
            elif new_additional is False:
                if direction == "request": self.fail(where, "request additional properties restricted")
            else:
                self.schema(old_additional, new_additional, where + ".*", direction, seen)
        for key in ("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
                    "minItems", "maxItems", "minProperties", "maxProperties"):
            old_value, new_value = before.get(key), after.get(key)
            if old_value == new_value: continue
            is_minimum = key.startswith("min") or key == "exclusiveMinimum"
            narrower = (new_value is not None and (old_value is None or
                        ((Decimal(str(new_value)) > Decimal(str(old_value))) if is_minimum else
                         (Decimal(str(new_value)) < Decimal(str(old_value))))))
            wider = (old_value is not None and (new_value is None or
                     ((Decimal(str(new_value)) < Decimal(str(old_value))) if is_minimum else
                      (Decimal(str(new_value)) > Decimal(str(old_value))))))
            if (direction == "request" and narrower) or (direction == "response" and wider):
                self.fail(where, key + " changed incompatibly")
        if before.get("multipleOf") != after.get("multipleOf"):
            first, second = (before.get("multipleOf"), after.get("multipleOf")) if direction == "request" else (after.get("multipleOf"), before.get("multipleOf"))
            if second is not None and (first is None or Decimal(str(first)) % Decimal(str(second)) != 0):
                self.fail(where, "multipleOf changed incompatibly")
        known = SCHEMA_KEYS | DOC_KEYS | {"$schema"}
        for key in (before.keys() | after.keys()) - known:
            if key.startswith("x-"): continue
            if semantic(before.get(key)) != semantic(after.get(key)):
                self.fail(where, f"unproven compatibility for changed {key}")

    def media(self, before, after, where, direction):
        for media_type, body in before.items():
            if media_type not in after:
                self.fail(where, "media type removed: " + media_type)
            else:
                self.schema(body.get("schema", {}), after[media_type].get("schema", {}), where + " " + media_type, direction)

    def operation(self, before, after, old_path, new_path, where):
        self.equal(before.get("operationId"), after.get("operationId"), where + " operationId")
        self.equal(before.get("security", self.before.get("security", [])),
                   after.get("security", self.after.get("security", [])), where + " security")
        self.equal(before.get("x-method-authorization"), after.get("x-method-authorization"), where + " authorization")
        def parameters(document, path, operation):
            values = path.get("parameters", []) + operation.get("parameters", [])
            values = [pointer(document,p["$ref"]) if "$ref" in p else p for p in values]
            return {(p["in"],p["name"]):p for p in values}
        old_params, new_params = parameters(self.before,old_path,before), parameters(self.after,new_path,after)
        for identity, parameter in old_params.items():
            if identity not in new_params:
                self.fail(where, "parameter removed: " + repr(identity)); continue
            changed = new_params[identity]
            if changed.get("required",False) and not parameter.get("required",False):
                self.fail(where, "parameter became required: " + repr(identity))
            self.schema(parameter.get("schema",{}),changed.get("schema",{}),where+" "+repr(identity),"request")
            for key in ("style","explode","allowReserved","allowEmptyValue","content"):
                self.equal(parameter.get(key),changed.get(key),where+" parameter "+key)
        for identity in new_params.keys() - old_params.keys():
            if new_params[identity].get("required",False): self.fail(where,"new required parameter: "+repr(identity))
        old_body, new_body = before.get("requestBody",{}), after.get("requestBody",{})
        if "$ref" in old_body: old_body=pointer(self.before,old_body["$ref"])
        if "$ref" in new_body: new_body=pointer(self.after,new_body["$ref"])
        if new_body.get("required",False) and not old_body.get("required",False): self.fail(where,"body became required")
        self.media(old_body.get("content",{}),new_body.get("content",{}),where+" body","request")
        old_responses, new_responses = before.get("responses",{}), after.get("responses",{})
        for code, response in old_responses.items():
            if code not in new_responses:
                self.fail(where,"response removed: "+code); continue
            changed=new_responses[code]
            if "$ref" in response: response=pointer(self.before,response["$ref"])
            if "$ref" in changed: changed=pointer(self.after,changed["$ref"])
            self.media(response.get("content",{}),changed.get("content",{}),where+" response "+code,"response")
            for name, header in response.get("headers",{}).items():
                current=changed.get("headers",{}).get(name)
                if current is None: self.fail(where,"response header removed: "+name)
                else: self.schema(header.get("schema",{}),current.get("schema",{}),where+" header "+name,"response")
        for code in new_responses.keys() - old_responses.keys():
            if code.startswith("2"): self.fail(where,"new success status requires old consumer support: "+code)


def compare_openapi(before, after):
    comparison=Compatibility(before,after)
    old_operations,new_operations=operations(before),operations(after)
    for route, operation in old_operations.items():
        where=" ".join(route)
        if route not in new_operations:
            comparison.fail(where,"published operation removed"); continue
        comparison.operation(operation,new_operations[route],before["paths"][route[1]],after["paths"][route[1]],where)
    old_components,new_components=before.get("components",{}),after.get("components",{})
    for name in old_components.get("schemas",{}):
        if name not in new_components.get("schemas",{}): comparison.fail(name,"published model removed")
    for name,scheme in old_components.get("securitySchemes",{}).items():
        comparison.equal(scheme,new_components.get("securitySchemes",{}).get(name),"security scheme "+name)
    return comparison.errors


def output_types(schema):
    found={}
    pending=[schema.query_type,schema.mutation_type,schema.subscription_type]
    while pending:
        value=pending.pop()
        if value is None: continue
        value=get_named_type(value)
        if value.name in found: continue
        found[value.name]=value
        if is_object_type(value) or is_interface_type(value): pending.extend(field.type for field in value.fields.values())
        elif is_union_type(value): pending.extend(value.types)
    return found


def compare_graphql(before,after):
    previous,current=build_schema(before),build_schema(after)
    errors=[change.description for change in find_breaking_changes(previous,current)]
    # Generated consumers validate enum/union outputs strictly. Input-only enum
    # additions remain compatible, while new output alternatives require v2.
    for name,old in output_types(previous).items():
        new=current.get_type(name)
        if is_enum_type(old) and is_enum_type(new) and new.values.keys()-old.values.keys():
            errors.append("Published output enum gained unknown values: "+name)
        if is_union_type(old) and is_union_type(new) and {value.name for value in new.types}-{value.name for value in old.types}:
            errors.append("Published output union gained unknown members: "+name)
    for change in find_dangerous_changes(previous,current):
        if change.type.name in {"ARG_DEFAULT_VALUE_CHANGE","INPUT_FIELD_DEFAULT_VALUE_CHANGE"}:
            errors.append(change.description)
    return errors


def wire_document(document):
    # Ignore only deployment/Info metadata. Preserve every schema field, including fields
    # named description, title or examples, and every authorization extension.
    return {key:document.get(key) for key in ("openapi","paths","components","security","x-implemented-routes")}


def check(root,base_root=None,live_root=None):
    root=Path(root)
    errors=[]
    for service,filename in API_FILES.items():
        relative=Path("contracts/openapi/v1")/filename
        try:
            published=load(root/relative)
            validate_openapi(published)
            if base_root is not None and (Path(base_root)/relative).is_file():
                previous=load(Path(base_root)/relative)
                validate_openapi(previous)
                errors.extend(f"{service}: {error}" for error in compare_openapi(previous,published))
            if live_root is not None:
                live=load(Path(live_root)/filename)
                validate_openapi(live)
                if wire_document(published)!=wire_document(live): errors.append(service+": implementation/spec drift")
        except (ValueError,KeyError,TypeError,OSError) as error:
            errors.append(f"{service}: {error}")
    implementation=root/"graphql-gateway/src/main/resources/graphql/schema.graphqls"
    publication=Path("contracts/graphql/v1/schema.graphqls")
    try:
        current=(root/publication).read_text(encoding="utf-8-sig")
        source=implementation.read_text(encoding="utf-8-sig")
        if print_schema(lexicographic_sort_schema(build_schema(current))) != print_schema(lexicographic_sort_schema(build_schema(source))):
            errors.append("GraphQL implementation/published schema drift")
        scalar_path=Path("contracts/graphql/v1/scalars.json")
        scalar_contracts=load(root/scalar_path)
        scalar_names={name for name,type_ in build_schema(current).type_map.items()
                      if type_.__class__.__name__=="GraphQLScalarType" and not name.startswith("__")
                      and name not in {"String","Int","Float","Boolean","ID"}}
        if set(scalar_contracts)!=scalar_names:
            errors.append("GraphQL serialized scalar contracts do not match the published custom scalar names")
        for name,shapes in scalar_contracts.items():
            if set(shapes)!={"input","output"}: raise ValueError("Scalar input/output contracts are required: "+name)
            for shape in shapes.values():
                if any(isinstance(node,dict) and "$ref" in node for node in walk(shape)):
                    raise ValueError("Scalar wire schemas must be self-contained")
                Draft202012Validator.check_schema(shape)
        if base_root is not None and (Path(base_root)/scalar_path).is_file():
            previous_scalars=load(Path(base_root)/scalar_path)
            comparison=Compatibility({}, {})
            for name,shapes in previous_scalars.items():
                if name not in scalar_contracts:
                    errors.append("GraphQL serialized scalar removed: "+name)
                    continue
                comparison.schema(shapes["input"],scalar_contracts[name]["input"],name+" input","request")
                comparison.schema(shapes["output"],scalar_contracts[name]["output"],name+" output","response")
            errors.extend("GraphQL scalar: "+error for error in comparison.errors)
        if base_root is not None:
            previous=Path(base_root)/publication
            if not previous.is_file(): previous=Path(base_root)/implementation.relative_to(root)
            errors.extend("GraphQL: "+error for error in compare_graphql(previous.read_text(encoding="utf-8-sig"),current))
    except (GraphQLError,SchemaError,ValueError,TypeError,OSError) as error:
        errors.append("GraphQL: "+str(error))
    return errors


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root",type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument("--base-root",type=Path)
    parser.add_argument("--live-root",type=Path)
    options=parser.parse_args()
    errors=check(options.root,options.base_root,options.live_root)
    if errors:
        for error in errors: print(error,file=sys.stderr)
        return 1
    print("Public API contracts accepted: six OpenAPI 3.1 APIs and GraphQL v1; no drift or incompatible base changes.")
    return 0


if __name__=="__main__":
    raise SystemExit(main())
