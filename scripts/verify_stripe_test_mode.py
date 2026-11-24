"""Explicit opt-in Stripe test sandbox acceptance; credentials stay in mounted files."""
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.parse import urlencode, urlsplit
from urllib.error import HTTPError
import argparse, hashlib, json, os, re, subprocess, sys, time, tomllib, uuid
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'clients/public-api'))
from support import AuthTokens
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True)
    parser.add_argument('--docker',default='docker')
    parser.add_argument('--api-key-file',required=True,type=Path)
    parser.add_argument('--cli-config',required=True,type=Path)
    parser.add_argument('--probe-intent',required=True)
    parser.add_argument('--payment',default='http://127.0.0.1:18080')
    parser.add_argument('--output',type=Path,default=ROOT/'coverage/stripe-test-mode.json')
    args=parser.parse_args()
    parsed=urlsplit(args.payment)
    if parsed.scheme!='http' or parsed.hostname not in ('127.0.0.1','localhost') or parsed.username:raise RuntimeError('Isolated loopback payment service required')
    if not re.fullmatch(r'omp-payments-acceptance-[a-z0-9]{6,24}',args.project):raise RuntimeError('Disposable acceptance project required')
    def docker(*values):return subprocess.check_output([args.docker,*values],text=True,stderr=subprocess.PIPE,timeout=90).strip()
    def container(service):
        identifiers=docker('ps','--filter','label=com.docker.compose.project='+args.project,'--filter','label=com.docker.compose.service='+service,'--format','{{.ID}}').splitlines()
        if len(identifiers)!=1:raise RuntimeError('Owned test container missing')
        return identifiers[0]
    database=container('postgres');payment_container=container('payment-service')
    runtime=json.loads(docker('inspect',payment_container))[0]
    environment=dict(item.split('=',1) for item in runtime['Config']['Env'] if '=' in item)
    if environment.get('PAYMENT_PROVIDER')!='stripe' or environment.get('PAYMENT_STRIPE_API_BASE_URL')!='https://api.stripe.com/v1' or environment.get('PAYMENT_STRIPE_SIMULATOR_ENABLED')!='false':raise RuntimeError('Real Stripe test service configuration required')
    key=args.api_key_file.read_text(encoding='utf-8').strip()
    configuration=tomllib.loads(args.cli_config.read_text(encoding='utf-8-sig'))
    profile=configuration.get('default',{})
    if profile.get('test_mode_api_key')!=key or not args.probe_intent.startswith('pi_'):raise RuntimeError('CLI test sandbox credential proof required')
    opener=build_opener(ProxyHandler({}),NoRedirect())
    def request(origin,path,method='GET',body=None,bearer=None,expected=(200,)):
        data=json.dumps(body,separators=(',',':')).encode() if body is not None else None
        headers={'Authorization':'Bearer '+bearer} if bearer else {}
        if data is not None:headers['Content-Type']='application/json'
        req=Request(origin+path,data=data,headers=headers,method=method)
        try:
            with opener.open(req,timeout=45) as response:status=response.status;result=json.loads(response.read(1048576))
        except HTTPError as error:status=error.code;result=json.loads(error.read(1048576))
        if status not in expected:raise RuntimeError('Acceptance HTTP status '+str(status))
        return result
    probe=request('https://api.stripe.com','/v1/payment_intents/'+args.probe_intent,bearer=key)
    if probe.get('livemode') is not False:raise RuntimeError('Live provider mode forbidden')
    os.environ.setdefault('KEYCLOAK_URL','http://127.0.0.1:18089')
    auth=AuthTokens()
    def local(path,method='GET',body=None,expected=(200,)):
        return request(args.payment,path,method,body,auth.token('alice'),expected)
    def wait(predicate):
        end=time.monotonic()+90
        while time.monotonic()<end:
            if predicate():return
            time.sleep(.5)
        raise RuntimeError('Provider completion deadline exceeded')
    report={'sourceRevision':subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),'rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'testModeVerifiedByApi':True,'provider':'stripe','passed':False,'runtimeImage':runtime['Image'],'checks':[]}
    def charge(alias):
        method=request('https://api.stripe.com','/v1/payment_methods/'+alias,bearer=key)
        if method.get('livemode') is not False:raise RuntimeError('Live payment method forbidden')
        body={'orderId':str(uuid.uuid4()),'amountMinor':100,'currency':'USD','idempotencyKey':str(uuid.uuid4()),'paymentMethodId':method['id']}
        return local('/api/v1/payments','POST',body,(200,202,402,502)),body
    try:
        paid,body=charge('pm_card_visa')
        if paid['result']!='confirmed':raise RuntimeError('Stripe success not confirmed')
        bound=request('https://api.stripe.com','/v1/payment_intents/'+paid['providerReference'],bearer=key)
        if bound.get('livemode') is not False or bound.get('status')!='succeeded' or bound.get('amount')!=100 or bound.get('metadata',{}).get('payment_id')!=paid['paymentId']:raise RuntimeError('Provider capture does not match the local payment')
        replay=local('/api/v1/payments','POST',body)
        if replay['paymentId']!=paid['paymentId'] or replay['providerReference']!=paid['providerReference']:raise RuntimeError('Provider replay identity changed')
        report['checks'].append({'name':'capture-and-idempotent-replay','passed':True})
        declined,_=charge('pm_card_chargeDeclined')
        if declined['result']!='declined':raise RuntimeError('Stripe decline not applied')
        report['checks'].append({'name':'test-token-decline','passed':True})
        action,_=charge('pm_card_threeDSecure2Required')
        if action['result']!='requires_action' or action.get('nextAction',{}).get('type')!='use_stripe_sdk' or not action['nextAction'].get('clientSecret'):raise RuntimeError('Stripe SDK next action missing')
        pending=local('/api/v1/payments/'+action['paymentId'])
        if pending['status']!='PENDING':raise RuntimeError('Action-required payment accepted prematurely')
        report['checks'].append({'name':'real-provider-sca-next-action-pending','passed':True})
        refund=local('/api/v1/payments/'+paid['paymentId']+'/refunds','POST',{'amountMinor':100,'reason':'requested','idempotencyKey':str(uuid.uuid4())},(202,))
        wait(lambda:local('/api/v1/payments/'+paid['paymentId']+'/refunds/'+refund['id'])['status']=='COMPLETED')
        if local('/api/v1/payments/'+paid['paymentId'])['status']!='REFUNDED':raise RuntimeError('Stripe refund aggregate not completed')
        report['checks'].append({'name':'real-provider-asynchronous-refund','passed':True})
        reference=paid['providerReference']
        if not re.fullmatch(r'pi_[A-Za-z0-9_]{1,240}',reference):raise RuntimeError('Invalid provider proof reference')
        def callbacks():
            query="SELECT count(*) FROM payments.provider_event WHERE provider='stripe' AND payment_ref='"+reference+"' AND status='PROCESSED' AND body_sha256 IS NOT NULL AND event_type IN ('payment_intent.succeeded','refund.created','refund.updated')"
            return int(docker('exec',database,'sh','-c','exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"','stripe-test-proof',query))
        wait(lambda:callbacks()>=2)
        report['checks'].append({'name':'real-signed-provider-callback-receipts','passed':True,'processedReceipts':callbacks()})
        report['passed']=True
    except Exception as failure:
        report['failureType']=type(failure).__name__
    finally:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
