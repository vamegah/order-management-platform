import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE=Path(__file__).resolve().parents[1]/"check_api_contracts.py"
spec=importlib.util.spec_from_file_location("api_contracts",MODULE)
api=importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


def document():
    return {
        "openapi":"3.1.0","info":{"title":"Example","version":"1.0.0"},
        "security":[{"bearerAuth":[]}],
        "x-implemented-routes":["POST /api/v1/examples"],
        "components":{"securitySchemes":{"bearerAuth":{"type":"http","scheme":"bearer"}},
            "schemas":{"Input":{"type":"object","properties":{"name":{"type":"string","minLength":2}},"required":["name"]},
                       "Output":{"type":"object","properties":{"id":{"type":"string"},"state":{"type":"string","enum":["A","B"]}},"required":["id"]}}},
        "paths":{"/api/v1/examples":{"post":{"operationId":"Example_create",
            "requestBody":{"required":True,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Input"}}}},
            "responses":{"200":{"description":"Created","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Output"}}}}}}}},
    }


class OpenApiContractsTest(unittest.TestCase):
    def setUp(self):
        self.before=document()
        self.after=copy.deepcopy(self.before)

    def errors(self):
        return api.compare_openapi(self.before,self.after)

    def test_structurally_valid_contract(self):
        api.validate_openapi(self.before)
        self.assertEqual([],self.errors())

    def test_invalid_openapi_rejected(self):
        self.after.pop("info")
        with self.assertRaises(Exception): api.validate_openapi(self.after)

    def test_external_and_missing_references_rejected(self):
        schema=self.after["components"]["schemas"]["Input"]
        schema["$ref"]="https://example.invalid/secret"
        with self.assertRaises(ValueError): api.validate_openapi(self.after)
        schema["$ref"]="#/components/schemas/Missing"
        with self.assertRaises(KeyError): api.validate_openapi(self.after)

    def test_hidden_or_unpublished_route_rejected(self):
        self.after["x-implemented-routes"].append("GET /api/v1/hidden")
        with self.assertRaisesRegex(ValueError,"routes"): api.validate_openapi(self.after)

    def test_duplicate_route_and_operation_identity_rejected(self):
        self.after["x-implemented-routes"]*=2
        with self.assertRaises(ValueError): api.validate_openapi(self.after)
        self.after=copy.deepcopy(self.before)
        self.after["paths"]["/api/v1/another"]={"post":copy.deepcopy(self.after["paths"]["/api/v1/examples"]["post"])}
        self.after["x-implemented-routes"].append("POST /api/v1/another")
        with self.assertRaisesRegex(ValueError,"Operation IDs"): api.validate_openapi(self.after)

    def test_removed_property_is_breaking_even_if_it_was_optional(self):
        del self.after["components"]["schemas"]["Output"]["properties"]["state"]
        self.assertTrue(any("property removed" in error for error in self.errors()))

    def test_new_required_input_and_stricter_bounds_are_breaking(self):
        schema=self.after["components"]["schemas"]["Input"]
        schema["properties"]["tenant"]={"type":"string"}
        schema["required"].append("tenant")
        schema["properties"]["name"]["minLength"]=3
        self.assertEqual(2,len(self.errors()))

    def test_optional_fields_and_new_operations_are_additive(self):
        for name in ("Input","Output"):
            self.after["components"]["schemas"][name]["properties"]["extra"]={"type":"string"}
        self.after["paths"]["/api/v1/new"]={"get":{"operationId":"Example_new","responses":{"204":{"description":"Empty"}}}}
        self.assertEqual([],self.errors())

    def test_response_type_nullability_and_enum_widening_are_breaking(self):
        output=self.after["components"]["schemas"]["Output"]["properties"]
        output["id"]["type"]=["string","null"]
        output["state"]["enum"].append("C")
        self.assertEqual(2,len(self.errors()))

    def test_request_widening_and_response_narrowing_are_safe(self):
        self.after["components"]["schemas"]["Input"]["properties"]["name"]["minLength"]=1
        self.after["components"]["schemas"]["Output"]["properties"]["state"]["enum"]=["A"]
        self.assertEqual([],self.errors())

    def test_security_and_success_status_changes_are_breaking(self):
        operation=self.after["paths"]["/api/v1/examples"]["post"]
        operation["security"]=[]
        operation["responses"]["202"]={"description":"New asynchronous outcome"}
        self.assertEqual(2,len(self.errors()))
        with self.assertRaisesRegex(ValueError,"authentication"): api.validate_openapi(self.after)

    def test_new_required_or_removed_parameters_are_breaking(self):
        before=self.before["paths"]["/api/v1/examples"]["post"]
        after=self.after["paths"]["/api/v1/examples"]["post"]
        before["parameters"]=[{"name":"page","in":"query","schema":{"type":"integer"}}]
        after["parameters"]=[{"name":"tenant","in":"query","required":True,"schema":{"type":"string"}}]
        self.assertEqual(2,len(self.errors()))

    def test_unproven_complex_schema_changes_fail_conservatively(self):
        self.after["components"]["schemas"]["Input"]["properties"]["name"]["pattern"]="[a-z]+"
        self.assertTrue(any("unproven compatibility" in error for error in self.errors()))

    def test_descriptions_and_examples_do_not_change_the_wire_contract(self):
        self.after["components"]["schemas"]["Input"]["description"]="Clearer wording"
        self.assertEqual([],self.errors())

    def test_integer_to_number_request_widening_is_safe_but_response_widening_is_not(self):
        before=self.before["components"]["schemas"]
        after=self.after["components"]["schemas"]
        before["Input"]["properties"]["name"]={"type":"integer"}
        after["Input"]["properties"]["name"]={"type":"number"}
        self.assertEqual([],self.errors())
        before["Output"]["properties"]["id"]={"type":"integer"}
        after["Output"]["properties"]["id"]={"type":"number"}
        self.assertTrue(self.errors())


    def test_business_fields_named_description_cannot_be_ignored_as_metadata(self):
        self.before["components"]["schemas"]["Output"]["properties"]["description"]={"type":"string"}
        self.assertNotEqual(api.wire_document(self.before),api.wire_document(self.after))
        self.assertTrue(self.errors())

    def test_complex_constraints_preserve_business_field_names(self):
        for contract in (self.before,self.after):
            contract["components"]["schemas"]["Output"]["allOf"]=[{"properties":{"description":{"type":"string"}}}]
        self.after["components"]["schemas"]["Output"]["allOf"]=[{"properties":{}}]
        self.assertTrue(self.errors())

    def test_reference_siblings_and_aliases_are_checked(self):
        for contract in (self.before,self.after):
            schemas=contract["components"]["schemas"]
            schemas["Identifier"]={"type":"string"}
            schemas["Alias"]={"$ref":"#/components/schemas/Identifier"}
            schemas["Output"]["properties"]["id"]={"$ref":"#/components/schemas/Alias","maxLength":5}
        self.after["components"]["schemas"]["Output"]["properties"]["id"]["maxLength"]=10
        self.assertTrue(self.errors())
        self.after["components"]["schemas"]["Output"]["properties"]["id"]["maxLength"]=5
        self.after["components"]["schemas"]["Identifier"]["type"]="integer"
        self.assertTrue(self.errors())

    def test_recursive_models_terminate_and_still_detect_changed_fields(self):
        for contract in (self.before,self.after):
            contract["components"]["schemas"]["Output"]["properties"]["child"]={"$ref":"#/components/schemas/Output"}
        self.assertEqual([],self.errors())
        self.after["components"]["schemas"]["Output"]["properties"]["id"]["type"]="integer"
        self.assertTrue(self.errors())

    def test_live_comparison_preserves_authorization_extensions(self):
        self.after["paths"]["/api/v1/examples"]["post"]["x-method-authorization"]="hasRole('admin')"
        self.assertNotEqual(api.wire_document(self.before),api.wire_document(self.after))



class GraphQlContractsTest(unittest.TestCase):
    def test_breaking_field_and_required_argument_changes_rejected(self):
        before="type Query { item(id: ID): String, old: String }"
        after="type Query { item(id: ID!): String }"
        self.assertEqual(2,len(api.compare_graphql(before,after)))

    def test_additive_fields_and_optional_arguments_allowed(self):
        before="type Query { item(id: ID!): String }"
        after="type Query { item(id: ID!, locale: String): String, other: Int }"
        self.assertEqual([],api.compare_graphql(before,after))

    def test_new_output_enum_values_are_rejected_but_input_only_values_are_additive(self):
        before="enum State { A } type Query { state: State }"
        after="enum State { A B } type Query { state: State }"
        self.assertTrue(api.compare_graphql(before,after))
        before="enum State { A } type Query { item(state: State): String }"
        after="enum State { A B } type Query { item(state: State): String }"
        self.assertEqual([],api.compare_graphql(before,after))

    def test_new_output_union_members_are_rejected(self):
        before="type A { id: ID! } union Result = A type Query { result: Result }"
        after="type A { id: ID! } type B { id: ID! } union Result = A | B type Query { result: Result }"
        self.assertTrue(api.compare_graphql(before,after))

    def test_changed_defaults_are_rejected_and_optional_inputs_remain_additive(self):
        self.assertTrue(api.compare_graphql("type Query { item(limit: Int = 20): String }", "type Query { item(limit: Int = 100): String }"))
        before="input Filter { name: String } type Query { item(filter: Filter): String }"
        after="input Filter { name: String, locale: String } type Query { item(filter: Filter): String }"
        self.assertEqual([],api.compare_graphql(before,after))

    def test_malformed_schema_rejected(self):
        with self.assertRaises(Exception): api.compare_graphql("type Query { a: String }","type Query {")

    def test_updating_both_head_fixtures_does_not_hide_rest_or_graphql_breakage(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory)/"base"
            head=Path(directory)/"head"
            for root in (base,head):
                for filename in api.API_FILES.values():
                    path=root/"contracts/openapi/v1"/filename
                    path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(json.dumps(document()),encoding="utf-8")
                for relative in ("contracts/graphql/v1/schema.graphqls","graphql-gateway/src/main/resources/graphql/schema.graphqls"):
                    path=root/relative
                    path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text("type Query { old: String, retained: Int }",encoding="utf-8")
                (root/"contracts/graphql/v1/scalars.json").write_text("{}",encoding="utf-8")
            for relative in ("contracts/graphql/v1/schema.graphqls","graphql-gateway/src/main/resources/graphql/schema.graphqls"):
                (head/relative).write_text("type Query { retained: Int }",encoding="utf-8")
            for filename in api.API_FILES.values():
                path=head/"contracts/openapi/v1"/filename
                changed=json.loads(path.read_text())
                del changed["components"]["schemas"]["Output"]["properties"]["state"]
                path.write_text(json.dumps(changed),encoding="utf-8")
            errors=api.check(head,base)
            self.assertEqual(7,len(errors))
            self.assertTrue(any("GraphQL" in error for error in errors))

    def test_first_graphql_publication_still_compares_the_existing_base_implementation(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory)/"base"
            head=Path(directory)/"head"
            for filename in api.API_FILES.values():
                path=head/"contracts/openapi/v1"/filename
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(json.dumps(document()),encoding="utf-8")
            source="graphql-gateway/src/main/resources/graphql/schema.graphqls"
            (base/source).parent.mkdir(parents=True,exist_ok=True)
            (base/source).write_text("type Query { old: String, retained: Int }",encoding="utf-8")
            for relative in (source,"contracts/graphql/v1/schema.graphqls"):
                (head/relative).parent.mkdir(parents=True,exist_ok=True)
                (head/relative).write_text("type Query { retained: Int }",encoding="utf-8")
            (head/"contracts/graphql/v1/scalars.json").write_text("{}",encoding="utf-8")
            self.assertTrue(any("GraphQL" in error for error in api.check(head,base)))


if __name__=="__main__":
    unittest.main()
