"""Isolated development acceptance fixtures; never part of the generated client."""
from functools import partial
import importlib.util
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import urlencode,urlsplit
from urllib.request import Request,build_opener

from runtime import NoRedirect,Transport

ROOT=Path(__file__).resolve().parents[2]
DEFAULT_ORIGINS={
    'product-service':('PUBLIC_API_PRODUCT_URL','http://product-service.localhost'),
    'customer-service':('PUBLIC_API_CUSTOMER_URL','http://customer-service.localhost'),
    'order-service':('PUBLIC_API_ORDER_URL','http://order-service.localhost'),
    'notification-service':('PUBLIC_API_WEBHOOK_URL','http://notification.localhost'),
    'payment-service':('PUBLIC_API_PAYMENT_URL','http://payment-service.localhost'),
    'legacy-banking-adapter':('PUBLIC_API_BANKING_URL','http://banking.localhost'),
    'graphql':('PUBLIC_API_GRAPHQL_URL','http://graphql.localhost'),
    'graphql-prod':('PUBLIC_API_GRAPHQL_PROD_URL','http://graphql-prod.localhost'),
}


def development_origin(value):
    host=urlsplit(value).hostname or ''
    if host not in ('localhost','127.0.0.1','::1') and not host.endswith('.localhost'):
        raise RuntimeError('Acceptance tests require isolated localhost origins')
    return value.rstrip('/')


def origins():
    return {service:development_origin(os.environ.get(variable,default))
            for service,(variable,default) in DEFAULT_ORIGINS.items()}


class AuthTokens:
    def __init__(self):
        bypass=os.environ.get('NO_PROXY',os.environ.get('no_proxy',''))+',localhost,127.0.0.1,.localhost'
        os.environ['NO_PROXY']=os.environ['no_proxy']=bypass
        self.origin=development_origin(os.environ.get('KEYCLOAK_URL','http://keycloak.localhost'))
        self.cache={}
        self.fixture=json.loads((ROOT/'infrastructure/keycloak/import/omp-realm.json').read_text(encoding='utf-8'))
        self.opener=build_opener(NoRedirect())

    def token(self,role):
        previous=self.cache.get(role)
        if previous and previous[1]>time.monotonic(): return previous[0]
        prefix='E2E_'+role.upper()
        username=os.environ.get(prefix+'_USERNAME',role)
        user=next((user for user in self.fixture['users'] if user['username']==username),{})
        fixture_password=next((credential['value'] for credential in user.get('credentials',[])
                               if credential.get('type')=='password'),None)
        password=os.environ.get(prefix+'_PASSWORD',fixture_password)
        if not password: raise RuntimeError('Configure development credentials for '+role)
        body=urlencode({'grant_type':'password','client_id':os.environ.get('E2E_CLIENT_ID','postman'),
                        'username':username,'password':password}).encode()
        request=Request(self.origin+'/realms/omp/protocol/openid-connect/token',data=body,
                        headers={'Content-Type':'application/x-www-form-urlencoded'},method='POST')
        try:
            with self.opener.open(request,timeout=20) as response: token=json.loads(response.read())
            value=token['access_token']
            self.cache[role]=(value,time.monotonic()+max(1,int(token.get('expires_in',60))-30))
            return value
        except Exception:
            raise RuntimeError('Development issuer did not issue the required '+role+' token') from None

    def bearer(self,role):
        return partial(self.token,role)


def contracts_module():
    spec=importlib.util.spec_from_file_location('api_contracts',ROOT/'scripts/check_api_contracts.py')
    result=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def export_live(output):
    auth=AuthTokens()
    locations=origins()
    output=Path(output)
    output.mkdir(parents=True,exist_ok=True)
    api=contracts_module()
    for service,filename in api.API_FILES.items():
        result=Transport(locations[service],auth.bearer('admin'),timeout=60).send('GET','/v3/api-docs')
        if result.status!=200 or not isinstance(result.data,dict):
            raise RuntimeError('Producer documentation unavailable: '+service)
        api.validate_openapi(result.data)
        (output/filename).write_text(json.dumps(result.data,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    return len(api.API_FILES)


def metric_requests(origin,token,uri):
    result=Transport(origin,token).send('GET','/actuator/prometheus',headers={'Accept':'text/plain; version=0.0.4'})
    if result.status!=200: raise RuntimeError('Authenticated metrics unavailable for batch verification')
    total=0
    for line in result.raw.decode('utf-8').splitlines():
        if not line.startswith('http_server_requests_seconds_count{'): continue
        labels,number=line.rsplit('}',1)
        if 'uri="'+uri+'"' in labels and 'method="GET"' in labels and 'status="200"' in labels:
            total+=float(number.strip())
    return total
