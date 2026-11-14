"""Consumer-only transport and validation; imports no platform implementation code."""
from dataclasses import dataclass, field
import json
import math
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from graphql import (build_schema, get_operation_ast, get_named_type, is_enum_type,
                     is_input_object_type, is_list_type, is_non_null_type,
                     is_scalar_type, parse, type_from_ast, value_from_ast_untyped)
from graphql.execution.values import get_variable_values
from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError

UNSET=object()


def reject_json_constant(value):
    raise ValueError("Non-standard JSON number")


class ContractViolation(ValueError):
    pass


@dataclass(frozen=True)
class HttpResult:
    status: int
    data: object=field(repr=False)
    headers: dict=field(repr=False)
    raw: bytes=field(repr=False)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Credentials must never follow a redirect to another host.
        return None


def validate_value(document,schema,value,where):
    root={"components":document.get("components",{}),**schema}
    try:
        Draft202012Validator(root,format_checker=FormatChecker()).validate(value)
    except ValidationError as error:
        path="/".join(map(str,error.absolute_path))
        raise ContractViolation(f"{where}: invalid {error.validator} at /{path}") from None


def resolve(document,value):
    seen=set()
    while isinstance(value,dict) and "$ref" in value:
        reference=value["$ref"]
        if not reference.startswith("#/") or reference in seen:
            raise ContractViolation("Only acyclic document-local object references are supported")
        seen.add(reference)
        value=document
        for part in reference[2:].split("/"):
            value=value[part.replace("~1","/").replace("~0","~")]
    return value


class Transport:
    def __init__(self,base_url,token=None,timeout=20):
        url=urlsplit(base_url)
        if url.scheme not in ("http","https") or not url.netloc or url.username or url.password:
            raise ContractViolation("An explicit HTTP(S) API origin without credentials is required")
        self.base_url=base_url.rstrip("/")
        self.token=token
        self.timeout=timeout
        self.opener=build_opener(NoRedirect())

    def send(self,method,path,*,query=None,headers=None,body=UNSET,token=UNSET):
        if not path.startswith("/") or path.startswith("//"):
            raise ContractViolation("Requests must use an origin-relative API path")
        request_headers={"Accept":"application/json","User-Agent":"omp-public-api-consumer/1"}
        request_headers.update(headers or {})
        selected=self.token if token is UNSET else token
        bearer=selected() if callable(selected) else selected
        if bearer: request_headers["Authorization"]="Bearer "+bearer
        encoded=None
        if body is not UNSET:
            encoded=json.dumps(body,separators=(",",":"),ensure_ascii=False).encode("utf-8")
            request_headers.setdefault("Content-Type","application/json")
        values={}
        for name,value in (query or {}).items():
            if value is None: continue
            values[name]=str(value).lower() if isinstance(value,bool) else value
        url=self.base_url+path+("?"+urlencode(values,doseq=True) if values else "")
        request=Request(url,data=encoded,headers=request_headers,method=method)
        try:
            response=self.opener.open(request,timeout=self.timeout)
        except HTTPError as error:
            response=error
        except (URLError,TimeoutError) as error:
            raise ContractViolation("API transport did not complete") from None
        try:
            try: raw=response.read()
            except OSError: raise ContractViolation("API response did not complete") from None
            response_headers={key.lower():value for key,value in response.headers.items()}
            media=response_headers.get("content-type","").split(";",1)[0].lower()
            data=None
            if raw and (media.endswith("json") or media.endswith("+json")):
                try: data=json.loads(raw,parse_constant=reject_json_constant)
                except (UnicodeError,ValueError): raise ContractViolation("API returned invalid JSON") from None
            return HttpResult(response.status,data,response_headers,raw)
        finally:
            response.close()


class RestClient(Transport):
    def __init__(self,document,base_url=None,token=None,timeout=20):
        super().__init__(base_url or document["servers"][0]["url"],token,timeout)
        self.document=document
        self.operations={operation["operationId"]:(method.upper(),path,item,operation)
                for path,item in document["paths"].items() for method,operation in item.items()
                if method in {"get","post","put","delete","patch","head","options","trace"}}

    def call(self,operation_id,*,path_params=None,query=None,headers=None,body=UNSET,
             expected_status=None,validate_request=True,token=UNSET):
        if operation_id not in self.operations: raise ContractViolation("Unknown published operation")
        method,path,path_item,operation=self.operations[operation_id]
        path_params,query,headers=dict(path_params or {}),dict(query or {}),dict(headers or {})
        parameters=[resolve(self.document,p) for p in path_item.get("parameters",[])+operation.get("parameters",[])]
        if validate_request:
            by_location={"path":path_params,"query":query,"header":{k.lower():v for k,v in headers.items()}}
            for parameter in parameters:
                location=parameter["in"]
                if location not in by_location: continue
                name=parameter["name"].lower() if location=="header" else parameter["name"]
                value=by_location[location].get(name,UNSET)
                if value is UNSET:
                    if parameter.get("required"): raise ContractViolation(operation_id+": missing "+name)
                else:
                    validate_value(self.document,parameter.get("schema",{}),value,operation_id+" parameter "+name)
            allowed={p["name"] for p in parameters if p["in"]=="query"}
            if query.keys()-allowed: raise ContractViolation(operation_id+": undeclared query parameters")
            request_body=resolve(self.document,operation.get("requestBody",{}))
            if body is UNSET:
                if request_body.get("required"): raise ContractViolation(operation_id+": request body required")
            else:
                media=request_body.get("content",{})
                description=media.get("application/json") or media.get("*/*")
                if description is None: raise ContractViolation(operation_id+": JSON request body is not published")
                validate_value(self.document,description.get("schema",{}),body,operation_id+" request")
        for name,value in path_params.items(): path=path.replace("{"+name+"}",quote(str(value),safe=""))
        if "{" in path: raise ContractViolation(operation_id+": missing path parameter")
        result=self.send(method,path,query=query,headers=headers,body=body,token=token)
        if expected_status is not None and result.status!=expected_status:
            raise ContractViolation(f"{operation_id}: expected HTTP {expected_status}, received {result.status}")
        response=operation.get("responses",{}).get(str(result.status)) or operation.get("responses",{}).get("default")
        if response is None: raise ContractViolation(f"{operation_id}: undocumented HTTP {result.status}")
        content=resolve(self.document,response).get("content",{})
        if result.raw:
            actual=result.headers.get("content-type","").split(";",1)[0].lower()
            description=content.get(actual) or content.get(actual.split("/",1)[0]+"/*") or content.get("*/*")
            if description is None: raise ContractViolation(f"{operation_id}: undocumented response media type for HTTP {result.status}")
            if result.data is None and result.raw.strip()!=b"null": raise ContractViolation(operation_id+": expected a JSON response")
            validate_value(self.document,description.get("schema",{}),result.data,operation_id+" response")
        elif content and result.status not in (204,304):
            raise ContractViolation(operation_id+": missing documented response body")
        return result


class GraphQlClient(Transport):
    def __init__(self,sdl,operations,scalars,base_url,token=None,timeout=20):
        super().__init__(base_url,token,timeout)
        self.schema=build_schema(sdl)
        self.operations=operations
        self.scalars=scalars

    def execute_raw(self,query=UNSET,*,variables=None,extensions=None,expected_status=None,token=UNSET):
        payload={"variables":variables or {}}
        if query is not UNSET: payload["query"]=query
        if extensions is not None: payload["extensions"]=extensions
        result=self.send("POST","/graphql",body=payload,token=token,
                headers={"Accept":"application/graphql-response+json, application/json"})
        if expected_status is not None and result.status!=expected_status:
            raise ContractViolation(f"GraphQL: expected HTTP {expected_status}, received {result.status}")
        if result.status==200:
            if not isinstance(result.data,dict) or not ("data" in result.data or result.data.get("errors")):
                raise ContractViolation("GraphQL response has no data or errors")
            if result.data.get("data",UNSET) is None and not result.data.get("errors"):
                raise ContractViolation("Null GraphQL root data requires an error")
            if "errors" in result.data and not isinstance(result.data["errors"],list):
                raise ContractViolation("GraphQL errors must be a list")
            for error in result.data.get("errors",[]):
                if not isinstance(error,dict) or not isinstance(error.get("message"),str):
                    raise ContractViolation("GraphQL error must contain a message")
        return result

    def execute(self,name,*,variables=None,expected_status=200,token=UNSET):
        if name not in self.operations: raise ContractViolation("Unknown compiled GraphQL operation")
        document=parse(self.operations[name])
        operation=get_operation_ast(document)
        definitions=operation.variable_definitions or ()
        values=get_variable_values(self.schema,definitions,variables or {})
        if isinstance(values,list): raise ContractViolation(name+": invalid GraphQL variables")
        for definition in definitions:
            key=definition.variable.name.value
            if key in values: self._input(type_from_ast(self.schema,definition.type),values[key],key)
        result=self.execute_raw(self.operations[name],variables=variables,expected_status=expected_status,token=token)
        if result.status==200 and result.data.get("data") is not None:
            root=self.schema.query_type if operation.operation.value=="query" else self.schema.mutation_type
            fragments={item.name.value:item for item in document.definitions if item.kind=="fragment_definition"}
            self._output(root,result.data["data"],operation.selection_set,values,fragments,"data")
        return result

    def _input(self,type_,value,where):
        if value is None: return
        if is_non_null_type(type_): return self._input(type_.of_type,value,where)
        if is_list_type(type_):
            for item in value: self._input(type_.of_type,item,where+"[]")
        elif is_input_object_type(type_):
            for key,item in value.items(): self._input(type_.fields[key].type,item,where+"."+key)
        elif is_scalar_type(type_) and type_.name in self.scalars:
            validate_value({},self.scalars[type_.name]["input"],value,where)

    def _fields(self,selection,type_,value,variables,fragments):
        for node in selection.selections:
            include=True
            for directive in node.directives or ():
                arguments={arg.name.value:value_from_ast_untyped(arg.value,variables) for arg in directive.arguments or ()}
                if directive.name.value=="skip" and arguments.get("if"): include=False
                if directive.name.value=="include" and not arguments.get("if"): include=False
            if not include: continue
            if node.kind=="field": yield node,type_
            else:
                fragment=fragments[node.name.value] if node.kind=="fragment_spread" else node
                condition=fragment.type_condition.name.value if fragment.type_condition else type_.name
                actual=value.get("__typename",type_.name)
                if condition in (actual,type_.name):
                    yield from self._fields(fragment.selection_set,self.schema.get_type(condition),value,variables,fragments)

    def _output(self,type_,value,selection,variables,fragments,where):
        if is_non_null_type(type_):
            if value is None: raise ContractViolation(where+": non-null GraphQL value was null")
            return self._output(type_.of_type,value,selection,variables,fragments,where)
        if value is None: return
        if is_list_type(type_):
            if not isinstance(value,list): raise ContractViolation(where+": expected GraphQL list")
            for index,item in enumerate(value): self._output(type_.of_type,item,selection,variables,fragments,f"{where}[{index}]")
            return
        if is_scalar_type(type_):
            if type_.name in self.scalars: validate_value({},self.scalars[type_.name]["output"],value,where)
            elif type_.name in ("String","ID") and not isinstance(value,str): raise ContractViolation(where+": expected string")
            elif type_.name=="Boolean" and not isinstance(value,bool): raise ContractViolation(where+": expected boolean")
            elif type_.name=="Int" and (type(value) is not int or not -(2**31)<=value<2**31): raise ContractViolation(where+": expected Int")
            elif type_.name=="Float" and (type(value) not in (int,float) or not math.isfinite(value)): raise ContractViolation(where+": expected Float")
            return
        if is_enum_type(type_):
            if value not in type_.values: raise ContractViolation(where+": unknown GraphQL enum value")
            return
        if not isinstance(value,dict): raise ContractViolation(where+": expected GraphQL object")
        selected=set()
        for node,parent in self._fields(selection,type_,value,variables,fragments):
            key=node.alias.value if node.alias else node.name.value
            selected.add(key)
            if key not in value: raise ContractViolation(where+": missing selected GraphQL field "+key)
            if node.name.value=="__typename":
                if not isinstance(value[key],str): raise ContractViolation(where+": invalid __typename")
            else: self._output(parent.fields[node.name.value].type,value[key],node.selection_set,variables,fragments,where+"."+key)
        if value.keys()-selected: raise ContractViolation(where+": unexpected unselected GraphQL fields")
