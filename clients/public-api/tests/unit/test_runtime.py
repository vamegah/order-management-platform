import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, unquote, urlsplit

ROOT=Path(__file__).resolve().parents[4]


def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec)
    sys.modules[name]=result
    spec.loader.exec_module(result)
    return result


runtime=module('public_api_runtime',ROOT/'clients/public-api/runtime.py')
generator=module('public_api_generator',ROOT/'clients/public-api/generate.py')


def contract(origin):
    return {'openapi':'3.1.0','info':{'title':'Unit API','version':'1.0.0'},'servers':[{'url':origin}],
        'security':[{'bearerAuth':[]}],'x-implemented-routes':['GET /api/v1/items/{id}','POST /api/v1/items'],
        'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer'}},'schemas':{
            'Item':{'type':'object','properties':{'id':{'type':'string'},'state':{'type':'string','enum':['A','B']}},'required':['id']},
            'Input':{'type':'object','properties':{'name':{'type':'string','minLength':1}},'required':['name']}}},
        'paths':{'/api/v1/items/{id}':{'get':{'operationId':'Item_get','parameters':[
            {'name':'id','in':'path','required':True,'schema':{'type':'string','minLength':1}},
            {'name':'ids','in':'query','schema':{'type':'array','items':{'type':'string'}}},
            {'name':'size','in':'query','schema':{'type':'integer','minimum':1,'maximum':100}}],
            'responses':{'200':{'description':'Item','content':{'application/json':{'schema':{'$ref':'#/components/schemas/Item'}}}}}}},
        '/api/v1/items':{'post':{'operationId':'Item_create','requestBody':{'required':True,'content':{
            'application/json':{'schema':{'$ref':'#/components/schemas/Input'}}}},'responses':{'200':{'description':'Created',
            'content':{'application/json':{'schema':{'$ref':'#/components/schemas/Item'}}}}}}}}}


class Handler(BaseHTTPRequestHandler):
    calls=[]
    override=None
    status_override=200
    graph={'data':{'item':{'id':'one','amount':'1.25','optional':None}}}

    def log_message(self,*args):
        pass

    def reply(self,value,status=200):
        payload=json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()

    def do_GET(self):
        type(self).calls.append((self.path,self.headers.get('Authorization')))
        if self.path=='/redirect':
            self.send_response(302)
            self.send_header('Location',f'http://localhost:{self.server.server_port}/redirect-target')
            self.send_header('Content-Length','0')
            self.end_headers()
            return
        value=type(self).override
        if value is None: value={'id':unquote(urlsplit(self.path).path.rsplit('/',1)[-1]),'state':'A'}
        self.reply(value,type(self).status_override)

    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
        type(self).calls.append((self.path,self.headers.get('Authorization')))
        if self.path=='/graphql': self.reply(type(self).graph)
        else: self.reply({'id':body['name'],'state':'A'})


class LocalServer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True)
        cls.thread.start()
        cls.origin=f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(2)

    def setUp(self):
        Handler.calls=[]
        Handler.override=None
        Handler.status_override=200
        Handler.graph={'data':{'item':{'id':'one','amount':'1.25','optional':None}}}


class RestConsumerTest(LocalServer):
    def setUp(self):
        super().setUp()
        self.document=contract(self.origin)
        self.client=runtime.RestClient(self.document,token='unit-token')

    def test_reference_response_validation_and_token_transport(self):
        result=self.client.call('Item_get',path_params={'id':'one'},expected_status=200)
        self.assertEqual('one',result.data['id'])
        self.assertEqual('Bearer unit-token',Handler.calls[-1][1])

    def test_response_type_enum_and_required_property_changes_fail(self):
        for invalid in ({'id':1},{'id':'one','state':'C'},{'state':'A'}):
            Handler.override=invalid
            with self.subTest(invalid=invalid),self.assertRaises(runtime.ContractViolation):
                self.client.call('Item_get',path_params={'id':'one'})

    def test_parameters_and_body_are_checked_before_network_io(self):
        for arguments in ({'path_params':{}},{'path_params':{'id':'one'},'query':{'size':0}},
                          {'path_params':{'id':'one'},'query':{'unexpected':1}}):
            with self.assertRaises(runtime.ContractViolation): self.client.call('Item_get',**arguments)
        with self.assertRaises(runtime.ContractViolation): self.client.call('Item_create',body={})
        with self.assertRaises(runtime.ContractViolation): self.client.call('Item_create')
        self.assertEqual([],Handler.calls)

    def test_compiled_input_body_and_path_query_encoding(self):
        response=self.client.call('Item_create',body={'name':'created'},expected_status=200)
        self.assertEqual('created',response.data['id'])
        self.client.call('Item_get',path_params={'id':'a/b'},query={'ids':['one','two'],'size':2})
        self.assertIn('a%2Fb',Handler.calls[-1][0])
        self.assertEqual(['one','two'],parse_qs(urlsplit(Handler.calls[-1][0]).query)['ids'])

    def test_undocumented_status_and_media_type_fail(self):
        Handler.status_override=418
        with self.assertRaisesRegex(runtime.ContractViolation,'undocumented HTTP'):
            self.client.call('Item_get',path_params={'id':'one'})
        Handler.status_override=200
        self.document['paths']['/api/v1/items/{id}']['get']['responses']['200']['content']={'application/problem+json':{'schema':{}}}
        with self.assertRaisesRegex(runtime.ContractViolation,'media type'):
            self.client.call('Item_get',path_params={'id':'one'})

    def test_redirect_does_not_forward_bearer_credentials(self):
        result=self.client.send('GET','/redirect')
        self.assertEqual(302,result.status)
        self.assertEqual(1,len(Handler.calls))

    def test_rotating_token_provider_is_resolved_per_request(self):
        tokens=iter(['first','second'])
        self.client.token=lambda:next(tokens)
        for _ in range(2): self.client.call('Item_get',path_params={'id':'one'})
        self.assertEqual(['Bearer first','Bearer second'],[call[1] for call in Handler.calls])

    def test_origins_must_not_embed_credentials(self):
        with self.assertRaises(runtime.ContractViolation): runtime.Transport('https://user:secret@example.invalid')


SDL='scalar Decimal type Query { item(id: ID!): Item } type Item { id: ID!, amount: Decimal!, optional: String }'
OPERATIONS={'Read':'query Read($id: ID!) { item(id: $id) { id amount optional } }'}
SCALARS={'Decimal':{'input':{'type':['number','string']},'output':{'type':'string','pattern':r'^-?[0-9]+(?:\.[0-9]+)?$'}}}


class GraphQlConsumerTest(LocalServer):
    def setUp(self):
        super().setUp()
        self.client=runtime.GraphQlClient(SDL,OPERATIONS,SCALARS,self.origin)

    def test_nullable_values_and_string_decimals_are_accepted(self):
        result=self.client.execute('Read',variables={'id':'one'})
        self.assertIsNone(result.data['data']['item']['optional'])

    def test_nonnull_and_custom_scalar_contracts_are_enforced(self):
        for invalid in ({'id':None,'amount':'1.25','optional':None},
                        {'id':'one','amount':1.25,'optional':None}):
            Handler.graph={'data':{'item':invalid}}
            with self.assertRaises(runtime.ContractViolation): self.client.execute('Read',variables={'id':'one'})

    def test_nullable_error_response_is_preserved(self):
        Handler.graph={'data':{'item':None},'errors':[{'message':'Resource not found','extensions':{'code':'NOT_FOUND'}}]}
        result=self.client.execute('Read',variables={'id':'missing'})
        self.assertEqual('NOT_FOUND',result.data['errors'][0]['extensions']['code'])

    def test_invalid_variables_fail_before_network_io(self):
        with self.assertRaises(runtime.ContractViolation): self.client.execute('Read',variables={})
        self.assertEqual([],Handler.calls)

    def test_missing_and_unselected_fields_are_rejected(self):
        for value in ({'id':'one','amount':'1.25'},
                      {'id':'one','amount':'1.25','optional':None,'secret':'not-selected'}):
            Handler.graph={'data':{'item':value}}
            with self.assertRaises(runtime.ContractViolation): self.client.execute('Read',variables={'id':'one'})

    def test_operations_without_variables_are_supported(self):
        self.client.operations['Inline']='query Inline { item(id:"one") { id amount optional } }'
        self.assertEqual('one',self.client.execute('Inline').data['data']['item']['id'])
    def test_null_root_without_errors_is_invalid(self):
        Handler.graph={'data':None}
        with self.assertRaises(runtime.ContractViolation): self.client.execute('Read',variables={'id':'one'})


class GeneratedConsumerTest(LocalServer):
    def fixture(self,path):
        for filename in generator.contracts.API_FILES.values():
            target=path/'contracts/openapi/v1'/filename
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(json.dumps(contract(self.origin)),encoding='utf-8')
        graph=path/'contracts/graphql/v1'
        graph.mkdir(parents=True,exist_ok=True)
        (graph/'schema.graphqls').write_text(SDL,encoding='utf-8')
        (graph/'scalars.json').write_text(json.dumps(SCALARS),encoding='utf-8')
        operations=path/'clients/public-api/operations'
        operations.mkdir(parents=True,exist_ok=True)
        (operations/'read.graphql').write_text(OPERATIONS['Read'],encoding='utf-8')

    def test_generated_rest_operation_is_compiled_and_executes(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            self.fixture(path)
            self.assertEqual((6,1),generator.generate(path,path/'build'))
            client=module('unit_generated_api',path/'build/api_client.py').ProductApi(token='unit-token')
            self.assertEqual('one',client.item_get(path_params={'id':'one'}).data['id'])

    def test_prior_consumer_operations_are_compiled_against_the_new_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            self.fixture(path/'head')
            operations=path/'base/clients/public-api/operations'
            operations.mkdir(parents=True)
            (operations/'old.graphql').write_text('query Old { item(id:"one") { removed } }',encoding='utf-8')
            with self.assertRaises(ValueError): generator.generate(path/'head',path/'build',path/'base')

    def test_unknown_fields_in_current_consumer_operations_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            self.fixture(path)
            (path/'clients/public-api/operations/read.graphql').write_text('query Read { missing }',encoding='utf-8')
            with self.assertRaises(ValueError): generator.generate(path,path/'build')


if __name__=='__main__': unittest.main()
