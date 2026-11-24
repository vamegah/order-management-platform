"""Disposable, source-bound provider and webhook acceptance. No financial/live credentials."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from socketserver import ThreadingMixIn
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
import argparse, base64, hashlib, hmac, json, os, re, subprocess, sys, threading, time, uuid
import xml.etree.ElementTree as ET
import traceback

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'clients/public-api'))
from support import AuthTokens
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None

def percentile(values,p):
    if not values:return 0
    return sorted(values)[min(len(values)-1,max(0,int(len(values)*p)-1))]

def wait_for(predicate,seconds=90):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            result=predicate()
            if result:return result
        except (URLError,ConnectionError,TimeoutError,AssertionError):pass
        time.sleep(.2)
    raise AssertionError('Acceptance convergence deadline exceeded')

class Subscriber:
    def __init__(self):
        self.lock=threading.Lock();self.secrets=[];self.status=204;self.delay=0
        self.deliveries={};self.attempts={};self.order_times={};self.latencies={};self.rejected=0;self.old_key_acceptances=0;self.lose_ack_once=False;self.lost_ack_ids=set();self.failure_gate=threading.Event();self.failure_gate.set()
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length=int(self.headers.get('Content-Length','0'))
                if length>262144:self.send_error(413);return
                raw=self.rfile.read(length)
                try:
                    timestamp=self.headers['X-Timestamp'];identifier=self.headers['X-Webhook-Delivery-Id']
                    signatures=self.headers.get('X-Webhook-Signatures','').split(',')
                    valid=False;old_valid=False
                    with owner.lock: keys=list(owner.secrets)
                    for index,key in enumerate(keys):
                        expected=hmac.new(key.encode(),(timestamp+'.').encode()+raw,hashlib.sha256).hexdigest()
                        matched=any(hmac.compare_digest('v1='+expected,s.strip()) for s in signatures)
                        valid|=matched;old_valid|=matched and index==0
                    assert valid and abs(int(time.time())-int(timestamp))<=300
                    data=json.loads(raw);assert data['schemaVersion']==1 and data['deliveryId']==identifier
                    with owner.lock:
                        owner.attempts[identifier]=owner.attempts.get(identifier,0)+1
                        status,delay=owner.status,owner.delay
                        drop_ack=owner.lose_ack_once and identifier not in owner.lost_ack_ids
                        if drop_ack:owner.lost_ack_ids.add(identifier)
                        if 200<=status<300:
                            if identifier not in owner.deliveries:
                                owner.deliveries[identifier]=data
                                order=data.get('data',{}).get('orderId')
                                if order in owner.order_times:owner.latencies[order]=(time.monotonic()-owner.order_times[order])*1000
                                if old_valid:owner.old_key_acceptances+=1
                    if status==503:owner.failure_gate.wait(8)
                    if delay:time.sleep(delay)
                    if drop_ack:self.close_connection=True;return
                    self.send_response(status);self.send_header('Content-Length','0');self.end_headers()
                except Exception:
                    with owner.lock:owner.rejected+=1
                    self.send_error(401)
            def log_message(self,*args):pass
        class BoundedServer(ThreadingMixIn,HTTPServer):
            daemon_threads=True
            def __init__(self,*args):self.slots=threading.BoundedSemaphore(16);super().__init__(*args)
            def process_request(self,request,address):
                if not self.slots.acquire(False):self.shutdown_request(request);return
                super().process_request(request,address)
            def process_request_thread(self,request,address):
                try:super().process_request_thread(request,address)
                finally:self.slots.release()
        self.server=BoundedServer(('0.0.0.0',0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def close(self):self.failure_gate.set();self.server.shutdown();self.server.server_close();self.thread.join(5)
    def orders(self):
        with self.lock:return {d.get('data',{}).get('orderId') for d in self.deliveries.values()}

class Acceptance:
    def __init__(self,args):
        self.args=args;self.profile=json.loads((ROOT/'docs/webhook-delivery-slos.json').read_text());self.report={'sourceRevision':subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),'rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'profileSha256':hashlib.sha256((ROOT/'docs/webhook-delivery-slos.json').read_bytes()).hexdigest(),'phases':[],'passed':False}
        tracked=subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),'ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
        manifest={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in sorted(set(filter(None,tracked))) if (ROOT/name).is_file()}
        self.report['sourceInputsSha256']=hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest()
        self.report['sourceDirty']=bool(subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),'status','--porcelain'],cwd=ROOT).strip())
        self.opener=build_opener(ProxyHandler({}),NoRedirect());self.auth=AuthTokens();self.subscriber=Subscriber();self.subscription=None;self.seed=None
        for origin in [args.payment,args.notification,args.provider]:
            parsed=urlsplit(origin)
            if parsed.scheme!='http' or parsed.hostname not in ('localhost','127.0.0.1') or parsed.username:raise RuntimeError('Only isolated loopback origins are permitted')
        if not re.fullmatch(r'omp-payments-acceptance-[a-z0-9]{6,24}',args.project):raise RuntimeError('Disposable acceptance project name required')
        self.containers={}
        for service in ('postgres','kafka','payment-service','notification-service','payment-provider-simulator'):
            ids=self.compose('ps','-q',service).strip().splitlines()
            if len(ids)!=1:raise RuntimeError('Expected one isolated '+service+' container')
            item=json.loads(self.docker('inspect',ids[0]))[0]
            if item['Config']['Labels'].get('com.docker.compose.project')!=args.project:raise RuntimeError('Container ownership mismatch')
            self.containers[service]=ids[0]
        self.report['runtime']={'docker':json.loads(self.docker('info','--format','{{json .}}'))['NCPU'],'memoryBytes':json.loads(self.docker('info','--format','{{json .}}'))['MemTotal'],'images':{s:json.loads(self.docker('inspect',i))[0]['Image'] for s,i in self.containers.items()}}
    def docker(self,*args):return subprocess.check_output([self.args.docker,*args],cwd=ROOT,text=True,stderr=subprocess.PIPE,timeout=180)
    def compose(self,*args):return self.docker('compose','--env-file','.env.example','-p',self.args.project,'-f','docker-compose.yml','-f','docker-compose.e2e.yml','-f','docker-compose.payments-e2e.yml',*args)
    def call(self,origin,path,method='GET',body=None,role='alice',headers=None,expected=(200,),raw=None,timeout=8):
        outgoing=dict(headers or {})
        if role:outgoing['Authorization']='Bearer '+self.auth.token(role)
        data=raw if raw is not None else (json.dumps(body,separators=(',',':'),ensure_ascii=False).encode() if body is not None else None)
        if data is not None:outgoing['Content-Type']='application/json'
        req=Request(origin+path,data=data,headers=outgoing,method=method);start=time.monotonic()
        try:
            with self.opener.open(req,timeout=timeout) as response:status=response.status;payload=response.read(1048576)
        except HTTPError as error:status=error.code;payload=error.read(1048576)
        assert status in expected,'Unexpected HTTP status '+str(status)+' for '+path.split('?')[0]
        return (json.loads(payload) if payload else None),(time.monotonic()-start)*1000
    def backlog(self):
        query="SELECT json_build_object('outboxPending',(SELECT count(*) FROM payments.outbox_event WHERE status IN ('PENDING','FAILED')),'outboxOldestSeconds',(SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM payments.outbox_event WHERE status IN ('PENDING','FAILED')),'dispatchPending',(SELECT count(*) FROM notifications.notification_dispatch WHERE state<>'PUBLISHED'),'dispatchOldestSeconds',(SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM notifications.notification_dispatch WHERE state<>'PUBLISHED'),'inboxPending',(SELECT count(*) FROM payments.provider_event WHERE status='RECEIVED'))"
        return json.loads(self.docker('exec',self.containers['postgres'],'sh','-c','exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"','payment-acceptance-sql',query))
    def control(self,**values):return self.call(self.args.provider,'/__test/controls','PUT',values,None,{'Authorization':'Bearer sk_test_omp_simulator'})[0]
    def payment(self,method='pm_success',order=None):
        order=order or str(uuid.uuid4());body={'orderId':order,'amountMinor':1500,'currency':'USD','idempotencyKey':str(uuid.uuid4()),'paymentMethodId':method}
        result,_=self.call(self.args.payment,'/api/v1/payments','POST',body,expected=(200,202,402,502))
        return result,body
    def read(self,identifier):return self.call(self.args.payment,'/api/v1/payments/'+identifier)[0]
    def signed(self,body,timestamp=None):
        raw=json.dumps(body,separators=(',',':'),ensure_ascii=False).encode();timestamp=int(time.time()) if timestamp is None else timestamp
        signature=hmac.new(b'dev-simulator-secret-not-for-production',(str(timestamp)+'.').encode()+raw,hashlib.sha256).hexdigest()
        return raw,{'Stripe-Signature':'t='+str(timestamp)+',v1='+signature}
    def callback(self,payment):
        return {'id':'evt_'+uuid.uuid4().hex,'type':'payment_intent.processing','data':{'object':{'id':payment['providerReference'],'amount':payment['amountMinor'],'currency':payment['currency'],'metadata':{'payment_id':payment['id'],'order_id':payment['orderId'],'customer_id':payment['customerId']}}}}
    def lifecycle(self):
        registration={'name':'payment-webhook-acceptance','targetUrl':'http://host.docker.internal:'+str(self.subscriber.server.server_port)+'/hooks','eventTypes':['payment.confirmed','payment.refunded']}
        sub,_=self.call(self.args.notification,'/api/v1/webhook-subscriptions','POST',registration,expected=(201,));self.subscription=sub['id'];self.subscriber.secrets=[sub['secret']]
        success,body=self.payment();assert success['result']=='confirmed';identifier=success['paymentId'];self.seed=self.read(identifier)
        replay,_=self.call(self.args.payment,'/api/v1/payments','POST',body);assert replay['paymentId']==identifier
        declined,_=self.payment('pm_decline');assert declined['result']=='declined'
        action,_=self.payment('pm_action');assert action['result']=='requires_action' and action['nextAction']['type']=='use_stripe_sdk'
        assert self.read(action['paymentId'])['status']=='PENDING'
        self.call(self.args.provider,'/__test/payment_intents/'+action['providerReference']+'/complete','POST',{'success':True},None,{'Authorization':'Bearer sk_test_omp_simulator'})
        wait_for(lambda:self.read(action['paymentId'])['status']=='CONFIRMED')
        failed,_=self.payment('pm_action');self.call(self.args.provider,'/__test/payment_intents/'+failed['providerReference']+'/complete','POST',{'success':False},None,{'Authorization':'Bearer sk_test_omp_simulator'})
        wait_for(lambda:self.read(failed['paymentId'])['status']=='FAILED')
        delayed,_=self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds','POST',{'amountMinor':400,'reason':'requested','idempotencyKey':str(uuid.uuid4())},expected=(202,))
        wait_for(lambda:self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds/'+delayed['id'])[0]['refund']['status']=='COMPLETED')
        assert self.read(identifier)['status']=='PARTIALLY_REFUNDED'
        timeout_result,timeout_body=self.payment('pm_timeout')
        wait_for(lambda:self.read(timeout_result['paymentId'])['status']=='CONFIRMED')
        retried,_=self.call(self.args.payment,'/api/v1/payments','POST',timeout_body)
        assert retried['paymentId']==timeout_result['paymentId'] and retried['result']=='confirmed'
        self.control(duplicateCallbacks=2,reverseCallbacks=True,delayMs=100)
        processing,_=self.payment('pm_processing');wait_for(lambda:self.read(processing['paymentId'])['status']=='CONFIRMED')
        self.control(duplicateCallbacks=0,reverseCallbacks=False,delayMs=2000)
        def partial(_):
            key=str(uuid.uuid4())
            result,_=self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds','POST',{'amountMinor':600,'reason':'concurrent','idempotencyKey':key},expected=(202,409))
            return result
        with ThreadPoolExecutor(max_workers=2) as pool:parts=list(pool.map(partial,range(2)))
        admitted=[r for r in parts if r and r.get('status')=='PENDING'];assert len(admitted)==1
        wait_for(lambda:self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds/'+admitted[0]['id'])[0]['refund']['status']=='COMPLETED')
        self.control(refundOutcome='failed',delayMs=100)
        failed_body={'amountMinor':100,'reason':'permanent-failure','idempotencyKey':str(uuid.uuid4())}
        failed_refund,_=self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds','POST',failed_body,expected=(202,422))
        wait_for(lambda:self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds/'+failed_refund['id'])[0]['refund']['status']=='FAILED')
        same,_=self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds','POST',failed_body,expected=(422,));assert same['id']==failed_refund['id']
        self.control(refundOutcome='succeeded',delayMs=100)
        final,_=self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds','POST',{'amountMinor':500,'reason':'remaining','idempotencyKey':str(uuid.uuid4())},expected=(202,200))
        wait_for(lambda:self.read(identifier)['status']=='REFUNDED')
        self.call(self.args.payment,'/api/v1/payments/'+identifier,role='bob',expected=(403,404))
        wait_for(lambda:body['orderId'] in self.subscriber.orders())
        return {'chargeReplayStable':True,'scaSuccessFailure':True,'delayedRefund':True,'foreignPaymentDenied':True,'timeoutReplay':True,'duplicateOutOfOrderCallbacks':True,'concurrentRefundFence':True,'permanentRefundFailure':True,'capturedAmountConserved':True}
    def security(self):
        event=self.callback(self.seed);raw,headers=self.signed(event)
        path='/api/v1/payments/webhooks/stripe'
        for payload,head,status in [(raw,{},401),(raw+b' ',headers,401),(raw,self.signed(event,1)[1],401),(b'x'*262145,headers,413)]:
            self.call(self.args.payment,path,'POST',role=None,headers=head,expected=(status,),raw=payload)
        self.call(self.args.payment,'/api/v1/payments/webhooks/noop','POST',role=None,headers=headers,expected=(404,),raw=raw)
        assert self.call(self.args.payment,path,'POST',role=None,headers=headers,raw=raw)[0]['status']=='accepted'
        assert self.call(self.args.payment,path,'POST',role=None,headers=headers,raw=raw)[0]['status']=='duplicate'
        for url in ['https://127.0.0.1/','https://169.254.169.254/latest/meta-data/','https://[::1]/','https://user:pass@example.com/']:
            self.call(self.args.notification,'/api/v1/webhook-subscriptions','POST',{'name':'denied','targetUrl':url,'eventTypes':['payment.confirmed']},expected=(400,))
        return {'signatureCases':5,'duplicateSafe':True,'forbiddenDestinations':4}
    def ingress(self):
        workload=self.profile['workload'];latencies=[];start=time.monotonic()
        def send(index):
            due=start+index/workload['ingressPerSecond'];time.sleep(max(0,due-time.monotonic()))
            raw,headers=self.signed(self.callback(self.seed));return self.call(self.args.payment,'/api/v1/payments/webhooks/stripe','POST',role=None,headers=headers,raw=raw)[1]
        with ThreadPoolExecutor(max_workers=16) as pool:latencies=list(pool.map(send,range(workload['ingressEvents'])))
        p95,p99=percentile(latencies,.95),percentile(latencies,.99);objectives=self.profile['objectives']
        assert p95<=objectives['ackP95Ms'] and p99<=objectives['ackP99Ms'],'Acknowledgment latency objective missed'
        wait_for(lambda:self.backlog()['inboxPending']==0)
        return {'events':len(latencies),'p95Ms':round(p95),'p99Ms':round(p99),'backlogAfterDrain':self.backlog()}
    def delivery(self):
        workload=self.profile['workload'];orders=[str(uuid.uuid4()) for _ in range(workload['chargeEvents'])]
        provider_before=self.call(self.args.provider,'/__test/stats',role=None,headers={'Authorization':'Bearer sk_test_omp_simulator'})[0]['payments'];start=time.monotonic()
        transient_replies=[]
        def charge(item):
            index,order=item;due=start+index/workload['chargesPerSecond'];time.sleep(max(0,due-time.monotonic()))
            with self.subscriber.lock:self.subscriber.order_times[order]=due
            result,body=self.payment(order=order)
            assert result['result'] in ('confirmed','pending','error'),'Healthy provider returned a terminal failure'
            identifier=result['paymentId']
            if result['result']!='confirmed':
                with self.subscriber.lock:transient_replies.append(identifier)
                replay,_=self.call(self.args.payment,'/api/v1/payments','POST',body,expected=(200,202,502))
                assert replay['paymentId']==identifier,'Unresolved reply retry changed the payment identity'
            return identifier
        with ThreadPoolExecutor(max_workers=16) as pool:identifiers=list(pool.map(charge,enumerate(orders)))
        wait_for(lambda:set(orders)<=self.subscriber.orders())
        assert all(self.read(identifier)['status']=='CONFIRMED' for identifier in identifiers),'Canonical payment did not converge'
        with self.subscriber.lock:
            latencies=[self.subscriber.latencies[o] for o in orders]
            ids=[identifier for identifier,d in self.subscriber.deliveries.items() if d.get('data',{}).get('orderId') in orders]
            duplicates=sum(max(0,self.subscriber.attempts[i]-1) for i in ids)
        provider_after=self.call(self.args.provider,'/__test/stats',role=None,headers={'Authorization':'Bearer sk_test_omp_simulator'})[0]['payments']
        assert provider_after-provider_before==len(orders),'Provider monetary identity conservation failed'
        p95,p99=percentile(latencies,.95),percentile(latencies,.99);objectives=self.profile['objectives']
        self.observed={'events':len(orders),'received':len(ids),'duplicates':duplicates,'p95Ms':round(p95),'p99Ms':round(p99),'transientProviderReplies':len(transient_replies),'canonicalConfirmed':len(identifiers),'providerPaymentsCreated':provider_after-provider_before}
        assert len(ids)==len(orders) and duplicates==0,'Healthy delivery conservation failed'
        assert p95<=objectives['deliveryP95Ms'] and p99<=objectives['deliveryP99Ms'],'Delivery latency objective missed'
        return {'events':len(orders),'received':len(ids),'duplicates':duplicates,'p95Ms':round(p95),'p99Ms':round(p99),'durationSeconds':round(time.monotonic()-start,2),'transientProviderReplies':len(transient_replies),'canonicalConfirmed':len(identifiers),'providerPaymentsCreated':provider_after-provider_before}
    def rotation(self):
        old=self.subscriber.secrets[0]
        rotated,_=self.call(self.args.notification,'/api/v1/webhook-subscriptions/'+self.subscription+'/rotate-secret','POST')
        with self.subscriber.lock:self.subscriber.secrets=[old,rotated['secret']];before=self.subscriber.old_key_acceptances
        _,body=self.payment();wait_for(lambda:body['orderId'] in self.subscriber.orders())
        assert self.subscriber.old_key_acceptances>before,'Previous signing key did not overlap'
        self.call(self.args.notification,'/api/v1/webhook-subscriptions/'+self.subscription+'/rotate-secret','POST',expected=(409,))
        self.call(self.args.notification,'/api/v1/webhook-subscriptions/'+self.subscription+'/rotate-secret','POST',role='bob',expected=(404,))
        return {'oldAndNewAccepted':True,'concurrentRotationFenced':True,'foreignRotationDenied':True}
    def failure_replay(self):
        self.subscriber.delay=.3
        slow_start=time.monotonic()
        with ThreadPoolExecutor(max_workers=5) as pool:slow=list(pool.map(lambda _:self.payment(),range(20)))
        slow_orders={body['orderId'] for _,body in slow}
        wait_for(lambda:slow_orders<=self.subscriber.orders())
        slow_seconds=time.monotonic()-slow_start;self.subscriber.delay=0
        self.subscriber.lose_ack_once=True
        _,lost_body=self.payment();wait_for(lambda:lost_body['orderId'] in self.subscriber.orders())
        self.subscriber.lose_ack_once=False
        with self.subscriber.lock:
            lost_id=next(identifier for identifier,data in self.subscriber.deliveries.items() if data.get('data',{}).get('orderId')==lost_body['orderId'])
        wait_for(lambda:self.subscriber.attempts.get(lost_id,0)>=2,90)
        assert sum(d.get('data',{}).get('orderId')==lost_body['orderId'] for d in self.subscriber.deliveries.values())==1
        count=self.profile['workload']['failedSubscriberEvents'];self.subscriber.status=503;self.subscriber.failure_gate.clear()
        try:
            def admit(_):
                result,_=self.call(self.args.notification,'/api/v1/webhook-subscriptions/'+self.subscription+'/test','POST',expected=(202,))
                return result['deliveryId']
            with ThreadPoolExecutor(max_workers=8) as pool:delivery_ids=set(pool.map(admit,range(count)))
        finally:self.subscriber.failure_gate.set()
        assert len(delivery_ids)==count,'Test delivery admission did not conserve identities'
        def entries():
            page=self.call(self.args.notification,'/api/v1/webhook-dlq?size=200',role='admin')[0]
            return [x for x in page['content'] if x['subscriptionId']==self.subscription]
        wait_for(lambda:len(entries())>=count,90);dead=entries();assert len(dead)==count
        self.call(self.args.notification,'/api/v1/webhook-dlq/'+dead[0]['deliveryId']+'/replay','POST',role='alice',expected=(403,))
        self.subscriber.status=204;self.call(self.args.notification,'/api/v1/webhook-subscriptions/'+self.subscription+'/enable','POST')
        start=time.monotonic()
        with ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(lambda e:self.call(self.args.notification,'/api/v1/webhook-dlq/'+e['deliveryId']+'/replay','POST',role='admin'),dead))
        wait_for(lambda:delivery_ids<=set(self.subscriber.deliveries),self.profile['objectives']['replaySeconds'])
        assert time.monotonic()-start<=self.profile['objectives']['replaySeconds']
        self.call(self.args.notification,'/api/v1/webhook-dlq/'+dead[0]['deliveryId']+'/replay','POST',role='admin',expected=(409,))
        return {'deadLetters':len(dead),'recovered':len(delivery_ids),'replaySeconds':round(time.monotonic()-start,2),'unauthorizedReplayDenied':True,'replayDrainPerSecond':round(len(delivery_ids)/max(.001,time.monotonic()-start),2),'admissionSource':'owner-authorized test deliveries before circuit opening','slowSubscriberEvents':len(slow_orders),'slowSubscriberSeconds':round(slow_seconds,2),'lostAcknowledgmentStableIdentity':True,'backlogAfterRecovery':self.backlog()}
    @contextmanager
    def paused(self,service):
        identifier=self.containers[service];self.docker('pause',identifier)
        try:yield
        finally:self.docker('unpause',identifier)
    def recovery(self):
        self.control(dropCallbacks=True,delayMs=100)
        result,body=self.payment();identifier=result['paymentId'];assert result['result']=='confirmed'
        refund,_=self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds','POST',{'amountMinor':500,'reason':'recovery','idempotencyKey':str(uuid.uuid4())},expected=(202,))
        self.docker('restart',self.containers['payment-provider-simulator'])
        wait_for(lambda:self.call(self.args.provider,'/__test/stats',role=None,headers={'Authorization':'Bearer sk_test_omp_simulator'})[0] is not None)
        wait_for(lambda:self.call(self.args.payment,'/api/v1/payments/'+identifier+'/refunds/'+refund['id'])[0]['refund']['status']=='COMPLETED')
        self.control(dropCallbacks=False)
        raw,headers=self.signed(self.callback(self.seed))
        with self.paused('postgres'):
            _,failed_ack=self.call(self.args.payment,'/api/v1/payments/webhooks/stripe','POST',role=None,headers=headers,raw=raw,expected=(503,),timeout=8)
            assert failed_ack<=self.profile['objectives']['durableFailureAckMaxMs']
        wait_for(lambda:self.read(identifier)['status']=='PARTIALLY_REFUNDED')
        self.call(self.args.payment,'/api/v1/payments/webhooks/stripe','POST',role=None,headers=headers,raw=raw)
        self.call(self.args.payment,'/api/v1/payments/webhooks/stripe','POST',role=None,headers=headers,raw=raw)
        with self.paused('kafka'):
            with ThreadPoolExecutor(max_workers=5) as pool:backlogged=list(pool.map(lambda _:self.payment(),range(20)))
            assert all(result['result']=='confirmed' for result,_ in backlogged)
            time.sleep(self.profile['workload']['databaseFaultSeconds'])
            backlog=self.backlog();assert backlog['outboxPending']>=20
        orders={body['orderId'] for _,body in backlogged};drain_start=time.monotonic()
        self.docker('restart',self.containers['notification-service'])
        wait_for(lambda:orders<=self.subscriber.orders(),self.profile['objectives']['recoverySeconds'])
        wait_for(lambda:self.backlog()['outboxPending']==0 and self.backlog()['dispatchPending']==0)
        drain_seconds=time.monotonic()-drain_start
        assert drain_seconds<=self.profile['objectives']['recoverySeconds']
        return {'providerRestartRefundLookup':True,'databaseRetryReceipt':True,'databaseFailureAckMs':round(failed_ack),'brokerBacklogPreserved':len(orders),'notificationRestart':True,'faultBacklog':backlog,'drainSeconds':round(drain_seconds,2),'drainEventsPerSecond':round(len(orders)/max(.001,drain_seconds),2),'backlogAfterRecovery':self.backlog()}
    def run(self):
        functions=[self.lifecycle,self.security,self.ingress,self.delivery,self.rotation,self.failure_replay,self.recovery]
        try:
            for name,function in zip(self.profile['requiredPhases'],functions):
                start=time.monotonic()
                self.observed={}
                try:measurements=function();passed=True
                except Exception as failure:
                    frame=traceback.extract_tb(failure.__traceback__)[-1]
                    measurements={**self.observed,'failureType':type(failure).__name__,'failureLocation':Path(frame.filename).name+':'+str(frame.lineno)}
                    if isinstance(failure,AssertionError):measurements['assertion']=str(failure)[:300]
                    passed=False
                self.report['phases'].append({'name':name,'passed':passed,'seconds':round(time.monotonic()-start,2),'measurements':measurements})
                self.save();print(json.dumps({'phase':name,'passed':passed,'measurements':measurements}),flush=True)
                if not passed:break
            self.report['passed']=len(self.report['phases'])==len(functions) and all(p['passed'] for p in self.report['phases']) and self.subscriber.rejected==0
        finally:
            for service,identifier in self.containers.items():
                state=json.loads(self.docker('inspect',identifier))[0]['State']
                if state.get('Paused'):self.docker('unpause',identifier)
            try:
                if self.subscription:self.call(self.args.notification,'/api/v1/webhook-subscriptions/'+self.subscription,'DELETE',expected=(204,))
                self.report['cleanupPassed']=True
            except Exception:self.report['cleanupPassed']=False;self.report['passed']=False
            self.subscriber.close();self.save()
        return self.report['passed']
    def save(self):
        self.args.output.mkdir(parents=True,exist_ok=True)
        (self.args.output/'payment-webhooks.json').write_text(json.dumps(self.report,indent=2)+'\n',encoding='utf-8')
        suite=ET.Element('testsuite',name='payment-webhook-acceptance',tests=str(len(self.report['phases'])),failures=str(sum(not p['passed'] for p in self.report['phases'])),errors='0',skipped='0')
        for p in self.report['phases']:
            case=ET.SubElement(suite,'testcase',classname='deployed.payment.webhooks',name=p['name'],time=str(p['seconds']))
            if not p['passed']:ET.SubElement(case,'failure',message='Acceptance objective failed; inspect safe measurements')
        ET.ElementTree(suite).write(self.args.output/'payment-webhooks.xml',encoding='utf-8',xml_declaration=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True);parser.add_argument('--docker',default='docker');parser.add_argument('--payment',default='http://127.0.0.1:18080');parser.add_argument('--notification',default='http://127.0.0.1:18084');parser.add_argument('--provider',default='http://127.0.0.1:18090');parser.add_argument('--output',type=Path,default=ROOT/'coverage/payment-webhooks')
    args=parser.parse_args();os.environ.setdefault('KEYCLOAK_URL','http://127.0.0.1:18089')
    success=Acceptance(args).run();print(json.dumps({'passed':success,'report':str(args.output/'payment-webhooks.json')}));return 0 if success else 1
if __name__=='__main__':raise SystemExit(main())
