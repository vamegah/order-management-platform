"""Source-bound SOAP acceptance against one explicitly disposable Compose project."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler, HTTPSHandler
from urllib.error import HTTPError
import argparse, base64, hashlib, json, os, re, secrets, socket, ssl, subprocess, sys, time, uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'clients/public-api'))
from support import AuthTokens
WS='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'
WU='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd'
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None

def wait_for(check,seconds=120):
    until=time.monotonic()+seconds
    while time.monotonic()<until:
        try:
            value=check()
            if value:return value
        except (OSError,ValueError,AssertionError):pass
        time.sleep(.5)
    raise AssertionError('SOAP acceptance convergence deadline exceeded')

def percentile(values,fraction):
    return sorted(values)[max(0,int(len(values)*fraction)-1)]

class Acceptance:
    def __init__(self,project):
        if not re.fullmatch(r'omp-soap-acceptance-[a-z0-9-]+',project):raise ValueError('Use an isolated SOAP acceptance project')
        self.project=project
        self.compose=['docker','compose','--project-name',project,'--env-file','.env.example','-f','docker-compose.yml','-f','docker-compose.e2e.yml','-f','docker-compose.soap-e2e.yml']
        self.report={'schemaVersion':1,'tlsProbeLocation':'disposable Docker app network','project':project,'phases':[],'passed':False,'cleanupPassed':False}
        self.opener=build_opener(ProxyHandler({}),NoRedirect())
        self.auth=AuthTokens()
        self.secrets_root=ROOT/'coverage/soap-fixtures/secrets'
        self.bank=self.secrets_root/'bank';self.adapter=self.secrets_root/'adapter'
        self.tls=ROOT/'coverage/soap-fixtures/tls/legacy-bank-service'
        self.originals={}
        self.sensitive=[]
        for directory in (self.bank,self.adapter):
            for file in directory.iterdir():
                if file.is_file():self.sensitive.append(file.read_text(encoding='utf-8').strip())
        self.out=ROOT/'coverage/soap-acceptance';self.out.mkdir(parents=True,exist_ok=True)
    def run(self,args,**kwargs):
        return subprocess.run(args,cwd=ROOT,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kwargs).stdout
    def dc(self,*args,**kwargs):return self.run([*self.compose,*args],**kwargs)
    def sql(self,query):
        return self.dc('exec','-T','postgres','sh','-c','psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v ON_ERROR_STOP=1',input=query.encode()).decode().strip()
    def control(self,mode='normal',delay=0,count=0):
        assert mode in ('normal','backend','slow','lost-reply') and 0<=delay<=10000 and 0<=count<=100
        self.sql(f"UPDATE bank_ledger.bank_simulator_control SET mode='{mode}',delay_ms={delay},remaining={count} WHERE id=1;")
    def http(self,path,body=None,key=None,role='admin'):
        headers={'Host':'banking.localhost','Accept':'application/json'}
        if role:headers['Authorization']='Bearer '+self.auth.token(role)
        if key:headers['Idempotency-Key']=key
        if body is not None:headers['Content-Type']='application/json'
        request=Request('http://127.0.0.1:18086'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method='GET' if body is None else 'POST')
        started=time.monotonic()
        try:response=self.opener.open(request,timeout=8)
        except HTTPError as error:response=error
        with response:
            raw=response.read();content_type=response.headers.get('Content-Type','')
            try:data=json.loads(raw)
            except ValueError:data=raw.decode(errors='replace')
            return response.status,data,(time.monotonic()-started)*1000,content_type
    def balance(self):
        status,data,_,_=self.http('/api/v1/banking/accounts/ACC-1/balance')
        assert status==200
        return data
    def transfer(self,key=None,amount='1.00',**values):
        body={'fromAccount':'ACC-1','toAccount':'ACC-2','amount':float(amount),'currency':'USD','reference':'soap-acceptance-'+uuid.uuid4().hex,**values}
        return self.http('/api/v1/banking/transfers',body,key)
    def metrics(self):
        with self.opener.open('http://127.0.0.1:19085/actuator/prometheus',timeout=5) as response:return response.read().decode()
    def metric(self,name,operation=None):
        return sum(float(line.rsplit(' ',1)[1]) for line in self.metrics().splitlines() if line.startswith(name+'{') and (operation is None or f'operation="{operation}"' in line))
    def state(self,state):
        return any(line.startswith('resilience4j_circuitbreaker_state{') and 'name="banking"' in line and f'state="{state}"' in line and line.rsplit(' ',1)[1]=='1.0' for line in self.metrics().splitlines())
    def ready(self):
        def recovered():
            value=self.balance()
            return value if self.state('closed') else None
        return wait_for(recovered)
    def restart(self,service,kill=False):
        assert service in ('legacy-bank-service','legacy-banking-adapter')
        if kill:self.dc('kill','-s','SIGKILL',service)
        self.dc('up','-d','--no-deps','--no-build',service) if kill else self.dc('restart',service)
        self.ready()
    def reset_circuit(self):self.control();self.restart('legacy-banking-adapter')
    def problem(self,result,status,code):
        actual,data,elapsed,content_type=result
        assert actual==status,(actual,status)
        assert 'application/problem+json' in content_type
        assert data['status']==status and data['type']=='urn:problem:'+code
        raw=json.dumps(data)
        assert not any(value and len(value)>=8 and value in raw for value in self.sensitive)
        assert not any(value in raw for value in ('Private simulator','Exception','stackTrace','org.springframework','password='))
        return elapsed
    def save(self,file):
        if file not in self.originals:self.originals[file]=file.read_bytes() if file.exists() else None
    def write(self,file,data):
        self.save(file);file.parent.mkdir(parents=True,exist_ok=True)
        temporary=file.with_name(file.name+'.acceptance-tmp');temporary.write_bytes(data if isinstance(data,bytes) else data.encode());os.chmod(temporary,0o640)
        if os.name!='nt':
            try:os.chown(temporary,-1,1000)
            except PermissionError:self.run(["sudo","-n","chgrp","1000",str(temporary)])
        temporary.replace(file)
    @contextmanager
    def phase(self,name):
        print('SOAP acceptance phase: '+name,flush=True)
        started=time.monotonic();record={'name':name,'passed':False,'elapsedSeconds':0};self.report['phases'].append(record);self.persist()
        try:yield record;record['passed']=True
        finally:record['elapsedSeconds']=round(time.monotonic()-started,3);self.persist()
    def persist(self):
        (self.out/'soap-acceptance.json').write_text(json.dumps(self.report,indent=2)+'\n',encoding='utf-8')
        suite=ET.Element('testsuite',name='SOAP deployed acceptance',tests=str(len(self.report['phases'])),failures=str(sum(not p['passed'] for p in self.report['phases'])),errors='0',skipped='0')
        for phase in self.report['phases']:
            case=ET.SubElement(suite,'testcase',classname='SoapDeployedAcceptance',name=phase['name'],time=str(phase['elapsedSeconds']))
            if not phase['passed']:ET.SubElement(case,'failure',message='Phase did not satisfy acceptance')
        ET.ElementTree(suite).write(self.out/'soap-acceptance.xml',encoding='utf-8',xml_declaration=True)
    def expect_backend_auth_failure(self):
        # A restarting facade briefly produces Traefik's own 502. First prove
        # ingress reaches the application's authentication filter without SOAP.
        wait_for(lambda:self.http('/api/v1/banking/accounts/ACC-1/balance',role=None)[0]==401)
        def failed():
            result=self.http('/api/v1/banking/accounts/ACC-1/balance')
            return result if result[0]==502 else None
        result=wait_for(failed)
        self.problem(result,502,'upstream-authentication')
        assert self.metric('banking_soap_attempts_total','balance')==1
    def credentials(self):
        user=(self.adapter/'bank.ws.username').read_text().strip() if (self.adapter/'bank.ws.username').exists() else 'platform-user'
        return user,(self.adapter/'bank.ws.password').read_text().strip()
    def envelope(self,*,password=None,username=None,offset=0,nonce=True,timestamp=True,digest=False):
        user,current=self.credentials();username=username or user;password=current if password is None else password
        created=datetime.now(timezone.utc)+timedelta(seconds=offset);expires=created+timedelta(seconds=60)
        stamp=lambda value:value.isoformat(timespec='milliseconds').replace('+00:00','Z')
        token=f'<wsse:UsernameToken wsu:Id="ut-{uuid.uuid4().hex}"><wsse:Username>{username}</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#Password{"Digest" if digest else "Text"}">{password}</wsse:Password>'
        if nonce:token+=f'<wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">{base64.b64encode(secrets.token_bytes(20)).decode()}</wsse:Nonce><wsu:Created>{stamp(created)}</wsu:Created>'
        token+='</wsse:UsernameToken>'
        time_header=f'<wsu:Timestamp wsu:Id="ts-{uuid.uuid4().hex}"><wsu:Created>{stamp(created)}</wsu:Created><wsu:Expires>{stamp(expires)}</wsu:Expires></wsu:Timestamp>' if timestamp else ''
        return f'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:wsse="{WS}" xmlns:wsu="{WU}"><s:Header><wsse:Security s:mustUnderstand="1">{time_header}{token}</wsse:Security></s:Header><s:Body><GetAccountBalanceRequest xmlns="http://legacybank.example.com/ws"><accountNumber>ACC-1</accountNumber></GetAccountBalanceRequest></s:Body></s:Envelope>'.encode()
    def native_probe(self,mode,ca,raw=b'',hostname='legacy-bank-service'):
        ca=Path(ca).resolve()
        assert ca.is_relative_to((ROOT/'coverage/soap-fixtures').resolve())
        arguments=['docker','run','--rm','-i','--network',self.project+'_app','--mount','type=bind,source='+str(ca)+',target=/bank-ca.crt,readonly','--mount','type=bind,source='+str(ROOT/'scripts/soap_tls_probe.py')+',target=/probe.py,readonly',os.environ.get('SOAP_ACCEPTANCE_PROBE_IMAGE','python:3.12-slim'),'python','/probe.py',mode,'--hostname',hostname]
        return json.loads(self.run(arguments,input=raw))
    def soap(self,raw,ca=None):
        result=self.native_probe('soap',ca or ROOT/'coverage/soap-fixtures/tls/ca/ca.crt',raw)
        return result['status'],base64.b64decode(result['body'])
    def assert_tls_rejected(self,ca,hostname='legacy-bank-service'):
        result=self.native_probe('reject',ca,hostname=hostname)
        assert result['rejected']
        if hostname!='legacy-bank-service':assert result['verifyCode'] in (62,64)
        else:assert result['verifyCode'] in (20,21)
    def validate_runtime(self):
        revision=self.run(['git','-c','safe.directory='+ROOT.as_posix(),'rev-parse','HEAD']).decode().strip()
        dirty=bool(self.run(['git','-c','safe.directory='+ROOT.as_posix(),'status','--porcelain']).strip())
        self.report.update(sourceRevision=revision,sourceDirty=dirty,startedAt=datetime.now(timezone.utc).isoformat())
        images={}
        for service in ('legacy-bank-service','legacy-banking-adapter'):
            identifier=self.dc('ps','-q',service).decode().strip();assert identifier
            item=json.loads(self.run(['docker','inspect',identifier]))[0]
            assert item['Config']['Labels']['com.docker.compose.project']==self.project
            assert item['Config']['Labels']['com.docker.compose.service']==service
            images[service]={'imageId':item['Image'],'revision':item['Config']['Labels'].get('org.opencontainers.image.revision'),'memoryBytes':item['HostConfig']['Memory'],'nanoCpus':item['HostConfig']['NanoCpus']}
            if os.environ.get('CI')=='true':assert images[service]['revision']==revision and not dirty
        self.report['runtime']=images
    def execute(self):
        self.validate_runtime();self.ready()
        with self.phase('packaged-route-auth-faults-and-durable-duplicates') as phase:
            self.problem(self.http('/api/v1/banking/accounts/ACC-1/balance',role=None),401,'unauthorized')
            self.problem(self.http('/api/v1/banking/accounts/ACC-1/balance',role='alice'),403,'forbidden')
            self.problem(self.transfer(amount='0'),400,'invalid-request')
            self.problem(self.transfer(amount='10001'),429,'daily-limit-exceeded')
            self.problem(self.transfer(fromAccount='ACC-999'),404,'account-not-found')
            self.problem(self.transfer(currency='EUR'),422,'unsupported-currency')
            self.problem(self.transfer(amount='10000',fromAccount='ACC-2',toAccount='ACC-1'),422,'insufficient-funds')
            key=uuid.uuid4().hex;body={'fromAccount':'ACC-1','toAccount':'ACC-2','amount':1.23,'currency':'USD','reference':'concurrent-duplicate'}
            before=float(self.balance()['balance'])
            with ThreadPoolExecutor(max_workers=6) as pool:results=list(pool.map(lambda _:self.http('/api/v1/banking/transfers',body,key),range(12)))
            phase['duplicateStatuses']={str(status):sum(item[0]==status for item in results) for status in set(item[0] for item in results)}
            assert all(item[0]==200 for item in results)
            expected=results[0][1];assert all(item[1]==expected for item in results)
            assert abs(float(self.balance()['balance'])-(before-1.23))<.001
            self.problem(self.http('/api/v1/banking/transfers',{**body,'amount':2},key),409,'idempotency-conflict')
            self.restart('legacy-banking-adapter');self.restart('legacy-bank-service')
            assert self.http('/api/v1/banking/transfers',body,key)[1]==expected
            assert self.soap(self.envelope())[0]==200
            probe_image=os.environ.get('SOAP_ACCEPTANCE_PROBE_IMAGE','python:3.12-slim')
            self.report['tlsProbeImageId']=json.loads(self.run(['docker','image','inspect',probe_image]))[0]['Id']
            assert self.http('/actuator/prometheus')[0]==404
            assert 'banking_soap_attempts_total' in self.metrics()
            phase.update(concurrentRequests=12,uniqueTransactions=1,restartsVerified=2)
        with self.phase('ws-security-timestamps-and-durable-replay-rejection') as phase:
            valid=self.envelope();assert self.soap(valid)[0]==200
            assert self.soap(valid)[0]!=200
            self.restart('legacy-bank-service');assert self.soap(valid)[0]!=200
            invalid=[self.envelope(password='invalid-password'),self.envelope(username='unknown-user'),self.envelope(offset=-180),self.envelope(offset=180),self.envelope(nonce=False),self.envelope(timestamp=False),self.envelope(digest=True)]
            invalid.append(re.sub(rb'<wsse:Nonce[^>]*>[^<]*</wsse:Nonce>',b'<wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">invalid_base64_nonce!</wsse:Nonce>',self.envelope()))
            missing=b'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><GetAccountBalanceRequest xmlns="http://legacybank.example.com/ws"><accountNumber>ACC-1</accountNumber></GetAccountBalanceRequest></s:Body></s:Envelope>'
            for message in [*invalid,missing]:assert self.soap(message)[0]!=200
            self.assert_tls_rejected(ROOT/'coverage/soap-fixtures/tls/ca/ca.crt','wrong-bank.example.invalid')
            original=(self.adapter/'bank.ws.password').read_bytes();bad_password=secrets.token_hex(24);self.sensitive.append(bad_password)
            self.write(self.adapter/'bank.ws.password',bad_password)
            self.dc('restart','legacy-banking-adapter');self.expect_backend_auth_failure()
            self.write(self.adapter/'bank.ws.password',original);self.restart('legacy-banking-adapter')
            phase.update(rejectedCredentialCases=len(invalid)+1,replayedMessagesRejected=2,hostnameMismatchRejected=True,adapterCredentialFaultStatus=502,authenticationAttempts=1)
        with self.phase('lost-reply-and-forced-process-recovery') as phase:
            key=uuid.uuid4().hex;body={'fromAccount':'ACC-1','toAccount':'ACC-2','amount':2.34,'currency':'USD','reference':'lost-response-'+key}
            before=float(self.balance()['balance']);attempts=self.metric('banking_soap_attempts_total','transfer')
            self.control('lost-reply',3000,1)
            result=self.http('/api/v1/banking/transfers',body,key);assert result[0]==200
            assert self.metric('banking_soap_attempts_total','transfer')-attempts==2
            assert abs(float(self.balance()['balance'])-(before-2.34))<.001
            key=uuid.uuid4().hex;body={**body,'reference':'forced-death-'+key};self.control('lost-reply',8000,2)
            with ThreadPoolExecutor(max_workers=1) as executor:
                pending=executor.submit(self.http,'/api/v1/banking/transfers',body,key)
                wait_for(lambda:self.sql("SELECT COUNT(*) FROM bank_ledger.bank_transfer WHERE reference='"+body['reference']+"';")=='1',10)
                self.restart('legacy-banking-adapter',kill=True)
                try:pending.result()
                except OSError:pass
            self.control();self.restart('legacy-bank-service')
            result=self.http('/api/v1/banking/transfers',body,key);assert result[0]==200
            assert self.sql("SELECT COUNT(*) FROM bank_ledger.bank_transfer WHERE reference='"+body['reference']+"';")=='1'
            assert abs(float(self.balance()['balance'])-(before-4.68))<.001
            key=uuid.uuid4().hex;body={**body,'reference':'bank-death-'+key};self.control('lost-reply',8000,2)
            with ThreadPoolExecutor(max_workers=1) as executor:
                pending=executor.submit(self.http,'/api/v1/banking/transfers',body,key)
                wait_for(lambda:self.sql("SELECT COUNT(*) FROM bank_ledger.bank_transfer WHERE reference='"+body['reference']+"';")=='1',10)
                self.restart('legacy-bank-service',kill=True)
                try:pending.result()
                except OSError:pass
            self.control();self.reset_circuit()
            result=self.http('/api/v1/banking/transfers',body,key);assert result[0]==200
            assert self.sql("SELECT COUNT(*) FROM bank_ledger.bank_transfer WHERE reference='"+body['reference']+"';")=='1'
            assert abs(float(self.balance()['balance'])-(before-7.02))<.001
            phase.update(lostReplyAttempts=2,forcedAdapterDeathRecovered=True,forcedBankDeathRecovered=True,duplicateMovements=0)
        with self.phase('retry-classification-breaker-transitions-and-deadlines') as phase:
            self.reset_circuit();before=self.metric('banking_soap_attempts_total','transfer')
            for _ in range(6):self.problem(self.transfer(currency='EUR'),422,'unsupported-currency')
            assert self.state('closed') and self.metric('banking_soap_attempts_total','transfer')-before==6
            self.control('backend',0,20)
            failed_calls=0
            for _ in range(5):
                if self.state('open'):break
                self.problem(self.http('/api/v1/banking/accounts/ACC-1/balance'),502,'upstream-error');failed_calls+=1
            assert 1<=failed_calls<=5 and self.state('open');before=self.metric('banking_soap_attempts_total','balance')
            elapsed=self.problem(self.http('/api/v1/banking/accounts/ACC-1/balance'),503,'upstream-unavailable');assert elapsed<500
            assert self.metric('banking_soap_attempts_total','balance')==before
            open_elapsed=elapsed
            self.control();time.sleep(15.5);assert self.http('/api/v1/banking/accounts/ACC-1/balance')[0]==200;assert self.state('half_open')
            for _ in range(2):assert self.http('/api/v1/banking/accounts/ACC-1/balance')[0]==200
            assert self.state('closed')
            self.control('slow',6000,2)
            elapsed=self.problem(self.http('/api/v1/banking/accounts/ACC-1/balance'),504,'upstream-timeout');assert elapsed<6500
            self.control();self.reset_circuit()
            phase.update(openRejectedMs=round(open_elapsed,2),failedCallsBeforeOpen=failed_calls,breakerStates=['closed','open','half_open','closed'],timeoutUpperBoundMs=6500)
        self.rotate_credentials()
        self.rotate_tls()
        with self.phase('bounded-load-metrics-and-redaction') as phase:
            self.reset_circuit()
            with ThreadPoolExecutor(max_workers=8) as executor:results=list(executor.map(lambda _:self.http('/api/v1/banking/accounts/ACC-1/balance'),range(100)))
            assert all(item[0]==200 for item in results)
            p95=percentile([item[2] for item in results],.95);assert p95<1500
            self.control('slow',5000,50)
            with ThreadPoolExecutor(max_workers=20) as executor:burst=list(executor.map(lambda _:self.http('/api/v1/banking/accounts/ACC-1/balance'),range(20)))
            assert all(item[0] in (503,504) and item[2]<6500 for item in burst)
            assert any(item[0]==503 for item in burst)
            self.control()
            log=self.dc('logs','--no-color','legacy-bank-service','legacy-banking-adapter').decode(errors='replace')
            assert not any(value and len(value)>=8 and value in log for value in self.sensitive)
            assert '<wsse:Password' not in log
            self.report['measured']={'healthyCalls':100,'concurrency':8,'p95Ms':round(p95,2),'maxMs':round(max(item[2] for item in results),2),'burstCalls':20,'maxBurstMs':round(max(item[2] for item in burst),2)}
            phase.update(self.report['measured'])
            metrics=self.metrics();assert 'banking_soap_rejected_total' in metrics and 'banking_bulkhead_available' in metrics
            (self.out/'metrics.prom').write_text(metrics,encoding='utf-8')
            self.reset_circuit()
        self.report['passed']=True
    def rotate_credentials(self):
        with self.phase('credential-overlap-rotation-and-retirement') as phase:
            old_user,old_password=self.credentials();new_user='platform-rotated';new_password=secrets.token_hex(24);self.sensitive.append(new_password)
            self.write(self.bank/'bank.security.previous-username',old_user);self.write(self.bank/'bank.security.previous-password',old_password)
            self.write(self.bank/'bank.security.username',new_user);self.write(self.bank/'bank.security.password',new_password)
            self.restart('legacy-bank-service');assert self.soap(self.envelope(username=old_user,password=old_password))[0]==200
            self.write(self.adapter/'bank.ws.username',new_user);self.write(self.adapter/'bank.ws.password',new_password)
            self.restart('legacy-banking-adapter');assert self.soap(self.envelope())[0]==200
            (self.bank/'bank.security.previous-username').unlink();(self.bank/'bank.security.previous-password').unlink()
            self.restart('legacy-bank-service');assert self.soap(self.envelope(username=old_user,password=old_password))[0]!=200
            assert self.soap(self.envelope())[0]==200
            phase.update(overlapAccepted=True,retiredCredentialRejected=True,newCredentialAccepted=True)
    def rotate_tls(self):
        with self.phase('ca-overlap-certificate-rotation-and-retirement') as phase:
            # Only the disposable fixture directory is changed; reports contain no key material.
            # OpenSSL/keytool are run in a short-lived JDK build-tools image with the fixture directory mounted.
            command=['docker','run','--rm','--user','0','--mount','type=bind,source='+str(ROOT)+',target=/workspace','-w','/workspace',os.environ.get('SOAP_ACCEPTANCE_TOOLS_IMAGE','eclipse-temurin:21-jdk'),'bash','scripts/rotate-soap-acceptance-tls.sh']
            for file in (self.tls/'tls.p12',self.tls/'trust/truststore.p12'):self.save(file)
            self.run([*command,'prepare'])
            self.restart('legacy-banking-adapter');assert self.soap(self.envelope())[0]==200
            self.run([*command,'retire'])
            self.dc('restart','legacy-banking-adapter');self.expect_backend_auth_failure()
            self.run([*command,'overlap'])
            self.restart('legacy-banking-adapter')
            self.run([*command,'switch'])
            self.restart('legacy-bank-service')
            new_ca=self.tls/'acceptance-rotation/ca/ca.crt'
            assert self.soap(self.envelope(),new_ca)[0]==200
            self.assert_tls_rejected(ROOT/'coverage/soap-fixtures/tls/ca/ca.crt')
            self.run([*command,'retire'])
            self.restart('legacy-banking-adapter');assert self.soap(self.envelope(),new_ca)[0]==200
            phase.update(overlapAccepted=True,newCaAccepted=True,retiredCaRejected=True,hostnameVerification=True,adapterUntrustedCaStatus=502,tlsFailureAttempts=1)
    def cleanup(self):
        try:
            self.control()
            for file,data in self.originals.items():
                if data is None:
                    if file.exists():file.unlink()
                else:file.write_bytes(data)
            # Restore original mounts before teardown; no credential material enters retained reports.
            rotation=self.tls/'acceptance-rotation'
            if rotation.exists():
                assert rotation.resolve().is_relative_to(self.tls.resolve())
                self.run(['docker','run','--rm','--user','0','--mount','type=bind,source='+str(ROOT)+',target=/workspace','-w','/workspace',os.environ.get('SOAP_ACCEPTANCE_TOOLS_IMAGE','eclipse-temurin:21-jdk'),'bash','scripts/rotate-soap-acceptance-tls.sh','cleanup'])
            if self.originals:
                self.dc('restart','legacy-bank-service','legacy-banking-adapter')
                self.ready()
            self.report['cleanupPassed']=True
        except Exception:self.report['cleanupPassed']=False
        self.report['finishedAt']=datetime.now(timezone.utc).isoformat();self.persist()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--project',required=True);args=parser.parse_args()
    acceptance=Acceptance(args.project)
    try:acceptance.execute()
    except Exception as error:
        # Exceptions may carry transport responses; retain only their class and failed phase.
        acceptance.report['errorClass']=type(error).__name__
        print('SOAP acceptance failed in '+acceptance.report['phases'][-1]['name'] if acceptance.report['phases'] else 'SOAP runtime validation failed',file=sys.stderr)
    finally:acceptance.cleanup()
    if not acceptance.report['passed'] or not acceptance.report['cleanupPassed']:return 1
    print('All seven deployed SOAP acceptance phases passed.');return 0
if __name__=='__main__':sys.exit(main())
