import copy
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location("event_guard",Path(__file__).resolve().parents[1]/"check_event_contracts.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class EventCompatibilityTest(unittest.TestCase):
    def setUp(self):self.old={"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":True}
    def test_additive_optional_field_is_compatible(self):
        new=copy.deepcopy(self.old);new["properties"]["new"]={"type":"string"};module.compatible(self.old,new)
    def test_required_addition_is_rejected(self):
        new=copy.deepcopy(self.old);new["required"].append("new")
        with self.assertRaises(ValueError):module.compatible(self.old,new)
    def test_field_removal_is_rejected(self):
        new=copy.deepcopy(self.old);new["properties"].clear()
        with self.assertRaises(ValueError):module.compatible(self.old,new)
    def test_field_type_change_is_rejected(self):
        new=copy.deepcopy(self.old);new["properties"]["id"]["type"]="integer"
        with self.assertRaises(ValueError):module.compatible(self.old,new)
    def test_unknown_fields_cannot_be_forbidden(self):
        new=copy.deepcopy(self.old);new["additionalProperties"]=False
        with self.assertRaises(ValueError):module.compatible(self.old,new)
    def test_narrower_range_or_enum_is_rejected(self):
        for old,new in [({"type":"integer","minimum":0},{"type":"integer","minimum":1}),({"type":"string","enum":["a","b"]},{"type":"string","enum":["a"]})]:
            with self.assertRaises(ValueError):module.compatible(old,new)
