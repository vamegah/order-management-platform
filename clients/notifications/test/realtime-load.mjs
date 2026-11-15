import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { setDefaultResultOrder } from 'node:dns';
setDefaultResultOrder('ipv4first');
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { Client } from '@stomp/stompjs';
import WebSocket from 'ws';
import { NotificationClient } from '../dist/index.js';

// Every destructive control checks the exact Compose label of a disposable project.
const root=resolve(fileURLToPath(new URL('../../../',import.meta.url)));
const project=process.env.COMPOSE_PROJECT_NAME??'';
assert.match(project,/^omp-events-acceptance-[a-z0-9]{8,24}$/,'An isolated events acceptance project is required');
assert.equal(process.env.E2E_EVENTS_CONTROLS,'true','Explicit disposable controls are required');
const docker=process.env.E2E_DOCKER_COMMAND??'docker';
const files=['docker-compose.yml','docker-compose.e2e.yml','docker-compose.events-e2e.yml'];
const compose=['compose','--project-name',project,'--env-file','.env.example',...files.flatMap(f=>['-f',f])];
const run=(args,options={})=>execFileSync(docker,args,{cwd:root,encoding:'utf8',timeout:180000,maxBuffer:4*1024*1024,...options});
function container(service) {
  assert.ok(['kafka','redis','postgres','notification-service','notification-service-2','order-service','inventory-service','payment-service'].includes(service));
  const id=run([...compose,'ps','-q',service]).trim();assert.match(id,/^[0-9a-f]{12,64}$/);
  const item=JSON.parse(run(['inspect',id]))[0];
  assert.equal(item.Config.Labels['com.docker.compose.project'],project);
  assert.equal(item.Config.Labels['com.docker.compose.service'],service);
  return id;
}
function sql(query) {
  const id=container('postgres');
  return run(['exec',id,'psql','-X','-U','omp_admin','-d','omp','-At','--set=ON_ERROR_STOP=1','-c',query]).trim();
}
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(predicate,timeout=60000) {
  const end=Date.now()+timeout;
  while(!await predicate()) {if(Date.now()>end) throw new Error('Acceptance condition timed out');await wait(100);}
}
const issuer=process.env.KEYCLOAK_URL??'http://keycloak.localhost';
const orders=process.env.ORDER_SERVICE_URL??'http://order-service.localhost';
const brokers=(process.env.E2E_EVENTS_BROKERS??'ws://127.0.0.1:18084/ws/websocket,ws://127.0.0.1:18087/ws/websocket').split(',');
assert.equal(brokers.length,2);
for(const value of [issuer,orders,...brokers]) {
  const url=new URL(value);
  assert.ok(['localhost','127.0.0.1','[::1]'].includes(url.hostname)||url.hostname.endsWith('.localhost'),'Acceptance endpoints must use loopback routing');
  assert.ok(!url.username&&!url.password&&!url.search&&!url.hash,'Acceptance URLs cannot contain credentials');
}

const management=['http://127.0.0.1:18085','http://127.0.0.1:18088'];
const slo=JSON.parse(readFileSync(resolve(root,'docs/event-delivery-slos.json')));
const sourceRevision=process.env.GITHUB_SHA??execFileSync('git',['-c','safe.directory='+root,'rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();
const workingTreeDirty=execFileSync('git',['-c','safe.directory='+root,'status','--porcelain'],{cwd:root,encoding:'utf8'}).trim().length>0;
const harnessSha256=createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex');
const buildFile=resolve(root,'coverage/events-local/service-jars.json');
const localBuild=existsSync(buildFile)?JSON.parse(readFileSync(buildFile)):null;
const report={project,sourceRevision,workingTreeDirty,harnessSha256,localBuild,startedAt:new Date().toISOString(),objectives:slo,phases:[],resources:[],unauthorizedDeliveries:0};
const clients=[],supported=[],paused=new Set();
const aliceCustomer='00000000-0000-0000-0000-000000000101';
const tokenCache=new Map();
const tokenRequests=new Map();
async function httpRequest(label,url,options) {
  try{return await fetch(url,options);}
  catch(error){throw new Error(label+' request failed: '+error.name);}
}
async function credentials(who='alice',short=false) {
  const key=who+short;const cached=tokenCache.get(key);
  if(cached && cached.expiresAt-Date.now()>30000)return cached;
  if(tokenRequests.has(key))return tokenRequests.get(key);
  const request=(async()=>{
    const response=await httpRequest('Issuer '+who,issuer+'/realms/omp/protocol/openid-connect/token',{
      method:'POST',signal:AbortSignal.timeout(15000),body:new URLSearchParams({grant_type:'password',client_id:short?'notification-short-lived-acceptance':'postman',username:who,password:process.env['E2E_'+who.toUpperCase()+'_PASSWORD']??who+'-dev-password'})});
    assert.equal(response.status,200,'Real issuer credential acquisition');
    const body=await response.json();const claims=JSON.parse(Buffer.from(body.access_token.split('.')[1],'base64url'));
    const identity={accessToken:body.access_token,subject:claims.sub,expiresAt:claims.exp*1000};
    if(!short) tokenCache.set(key,identity);return identity;
  })();
  tokenRequests.set(key,request);
  try{return await request;}finally{tokenRequests.delete(key);}
}

async function api(path,who='alice',method='GET',body) {
  const identity=await credentials(who);
  return httpRequest('Order API',orders+'/api/v1/orders'+path,{method,signal:AbortSignal.timeout(20000),headers:{Authorization:'Bearer '+identity.accessToken,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
}
async function recovery(path,who='admin',method='GET',body) {
  const identity=await credentials(who);const base=new URL(brokers[0]);base.protocol=base.protocol==='wss:'?'https:':'http:';
  return httpRequest('Recovery API',new URL('/api/v1/event-recovery'+path,base),{method,signal:AbortSignal.timeout(15000),headers:{Authorization:'Bearer '+identity.accessToken,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
}
async function create() {
  const response=await api('','alice','POST',{customerId:aliceCustomer,shippingAddress:{line1:'1 Event Acceptance Way',city:'Oakland',postalCode:'94607',country:'US'},lines:[{productId:'00000000-0000-0000-0000-000000000204',quantity:1}],notes:'events-acceptance-'+randomUUID()});
  assert.equal(response.status,201,'Create independently owned order');return response.json();
}
async function connect(url,destination,{who='alice',slow=false,onMessage=()=>{},short=false}={}) {
  const record={connected:false,connections:0,received:new Set(),duplicates:0,socket:null,closed:0,closeCodes:{},heartbeatTimeouts:0};
  const identity=await credentials(who,short);
  record.client=new Client({webSocketFactory:()=>{record.socket=new WebSocket(url,{perMessageDeflate:!slow});return record.socket;},connectHeaders:{Authorization:'Bearer '+identity.accessToken},reconnectDelay:slow||short?0:500+Math.floor(Math.random()*1500),connectionTimeout:30000,heartbeatIncoming:slow?0:10000,heartbeatOutgoing:10000,debug:text=>{if(text.startsWith("did not receive server activity"))record.heartbeatTimeouts++;}});
  record.client.beforeConnect=async()=>{record.client.connectHeaders={Authorization:'Bearer '+(await credentials(who,short)).accessToken};};
  record.client.onConnect=()=>{record.connected=true;record.connections++;record.client.subscribe(destination,frame=>onMessage(JSON.parse(frame.body),record));if(slow)setTimeout(()=>record.socket?._socket?.pause(),100);};
  record.client.onWebSocketClose=event=>{record.connected=false;record.closed++;record.closeCodes[event.code]=(record.closeCodes[event.code]??0)+1;};
  record.client.onStompError=()=>{record.connected=false;record.rejected=true;};
  clients.push(record);record.client.activate();
  try{await until(()=>record.connected||record.rejected,45000);}catch{throw new Error('Client connection failed after '+record.connections+' handshakes and '+record.closed+' closes');}
  assert.equal(record.rejected,undefined,'Authorized subscription is accepted');return record;
}
async function subscriptions() {
  const identity=await credentials('admin');
  const counts=await Promise.all(management.map(async url=>{
    const response=await httpRequest('Replica metrics '+url,url+'/actuator/prometheus',{headers:{Authorization:'Bearer '+identity.accessToken},signal:AbortSignal.timeout(5000)});
    assert.equal(response.status,200);const metrics=await response.text();
    const line=metrics.split('\n').find(s=>s.startsWith('websocket_subscriptions_active'));
    if(!line)throw new Error('Active-subscription metric is required');return Number(line.trim().split(/\s+/).at(-1));
  }));return counts.reduce((a,b)=>a+b,0);
}
function replicaState() {
  return ['notification-service','notification-service-2'].map(service=>{
    const item=JSON.parse(run(['inspect',container(service)]))[0];
    return {service,restarts:item.RestartCount,oomKilled:item.State.OOMKilled};
  });
}
async function phase(name,action) {
  const start=Date.now();console.log('Starting '+name);
  try {const measurements=await action();report.phases.push({name,passed:true,durationMs:Date.now()-start,...measurements});}
  catch(error) {report.phases.push({name,passed:false,durationMs:Date.now()-start,error:error.message.slice(0,300)});throw error;}
}
function pause(service) {const id=container(service);run(['pause',id]);paused.add(service);}
function resume(service) {const id=container(service);run(['unpause',id]);paused.delete(service);}
function producer() {
  const child=spawn(docker,['exec','-i',container('kafka'),'/opt/kafka/bin/kafka-console-producer.sh','--bootstrap-server','kafka:9092','--topic','orders.events.order','--property','parse.key=true','--property','key.separator=\t','--producer-property','acks=all'],{cwd:root,stdio:['pipe','ignore','pipe']});
  let failure='';child.stderr.on('data',b=>{failure=(failure+b.toString()).slice(-2000);});
  const done=new Promise((resolve,reject)=>{child.on('error',reject);child.on('exit',code=>code===0?resolve():reject(new Error('Load producer exited with code '+code)));});
  return {emit(orderId,event){child.stdin.write(orderId+'\t'+JSON.stringify(event)+'\n');},async end(){child.stdin.end();await done;}};
}
function event(orderId,id= randomUUID(),padding='') {
  const data={eventId:id,eventType:'ORDER_CREATED',schemaVersion:1,occurredAt:new Date().toISOString(),orderId,customerId:aliceCustomer,status:'DRAFT',orderNumber:padding||'LOAD-'+id};
  return {...data,aggregateId:orderId,data};
}
let order,slowOrder;
try {
  await phase('broker outage retains all three producer outboxes',async()=>{
    pause('kafka');
    try {
      order=await create();const placed=await api('/'+order.id+'/place','alice','POST',{});assert.equal(placed.status,200,'Checkout succeeds independently of broker availability');
      assert.match(order.id,/^[0-9a-f-]{36}$/);
      for(const schema of ['orders','inventory','payments']) assert.ok(Number(sql(`SELECT count(*) FROM ${schema}.outbox_event WHERE status='PENDING' AND payload::text LIKE '%${order.id}%'`))>0,schema+' retains committed events');
      for(const service of ['order-service','inventory-service','payment-service']) run(['restart','--time','0',container(service)]);
    } finally {resume('kafka');}
    run([...compose,'up','-d','--no-deps','--no-build','--no-recreate','--wait','--wait-timeout','180','order-service','inventory-service','payment-service']);
    await until(()=>['orders','inventory','payments'].every(schema=>Number(sql(`SELECT count(*) FROM ${schema}.outbox_event WHERE status<>'PUBLISHED' AND payload::text LIKE '%${order.id}%'`))===0),60000);
    const ids=sql(`SELECT payload->>'eventId' FROM orders.outbox_event WHERE payload::text LIKE '%${order.id}%' UNION ALL SELECT payload->>'eventId' FROM inventory.outbox_event WHERE payload::text LIKE '%${order.id}%' UNION ALL SELECT payload->>'eventId' FROM payments.outbox_event WHERE payload::text LIKE '%${order.id}%'`).split('\n').filter(Boolean);
    for(const id of ids)assert.match(id,/^[0-9a-f-]{36}$/);
    await until(()=>Number(sql(`SELECT count(*) FROM notifications.domain_event_inbox WHERE consumer_id='live' AND event_id IN (${ids.map(x=>"'"+x+"'").join(',')})`))===ids.length);
    for(const schema of ['orders','inventory','payments']) {
      const row=sql(`SELECT id FROM ${schema}.outbox_event WHERE payload::text LIKE '%${order.id}%' ORDER BY sequence_id LIMIT 1`);
      assert.match(row,/^[0-9a-f-]{36}$/);
      sql(`UPDATE ${schema}.outbox_event SET status='FAILED',published_at=NULL WHERE id='${row}'`);
      const replayArgs=['exec','-i','-e','PGOPTIONS=-c search_path='+schema+',public',container('postgres'),'psql','-X','-U','omp_admin','-d','omp','--set=ON_ERROR_STOP=1','--set=id='+row,'--set=reason=disposable broker recovery acceptance','-f','-'];
      run(replayArgs,{input:readFileSync(resolve(root,'scripts/outbox-replay.sql'))});
      assert.equal(Number(sql(`SELECT count(*) FROM ${schema}.outbox_recovery_audit WHERE outbox_id='${row}'`)),1);
    }
    // Recreate an acknowledged-but-not-committed outbox outcome; deduplication survives redelivery.
    container('notification-service');run([...compose,'restart','notification-service']);
    sql(`UPDATE orders.outbox_event SET status='PENDING',published_at=NULL WHERE payload::text LIKE '%${order.id}%'`);
    await until(()=>Number(sql(`SELECT count(*) FROM orders.outbox_event WHERE status<>'PUBLISHED' AND payload::text LIKE '%${order.id}%'`))===0);
    await until(async()=>{try{return (await fetch(management[0]+'/actuator/health',{signal:AbortSignal.timeout(2000)})).ok;}catch{return false;}},180000);
    return {committedEventIds:ids.length,producers:['orders','inventory','payments'],redeliveryDeduplicated:true};
  });
  await phase('poison quarantine administrator replay and duplicate fencing',async()=>{
    const marker='redacted-fixture-'+randomUUID();const invalid={untrusted:marker};const hash=createHash('sha256').update(JSON.stringify(invalid)).digest('hex');
    const source=producer();const validId=randomUUID();source.emit(order.id,invalid);source.emit(order.id,event(order.id,validId));await source.end();
    let records=[];
    await until(async()=>{
      const response=await recovery('/quarantine?limit=200');assert.equal(response.status,200);const body=await response.json();assert.ok(!JSON.stringify(body).includes(marker));records=body.filter(r=>r.payload_hash===hash);return records.length===2;
    });
    await until(()=>Number(sql(`SELECT count(*) FROM notifications.domain_event_inbox WHERE consumer_id='live' AND event_id='${validId}'`))===1);
    assert.equal((await recovery('/quarantine','bob')).status,403);
    const recovered=event(order.id);recovered.eventType='ORDER_PLACED';recovered.status='PLACED';recovered.data.eventType='ORDER_PLACED';recovered.data.status='PLACED';
    for(const record of records) {
      const body={reason:'Reviewed disposable source fixture',correctedPayload:JSON.stringify(recovered)};
      assert.equal((await recovery('/quarantine/'+record.id+'/replay','bob','POST',body)).status,403);
      const results=await Promise.all([recovery('/quarantine/'+record.id+'/replay','admin','POST',body),recovery('/quarantine/'+record.id+'/replay','admin','POST',body)]);
      assert.deepEqual(results.map(r=>r.status).sort(),[200,409]);
      assert.equal(Number(sql(`SELECT count(*) FROM notifications.event_recovery_audit WHERE resource_id='${record.id}'`)),1);
      const inspected=await (await recovery('/quarantine/'+record.id)).json();assert.ok(inspected.replayed_at);assert.ok(!JSON.stringify(inspected).includes(marker));
    }
    assert.equal((await (await api('/'+order.id)).json()).status,'PLACED');
    return {quarantinedConsumers:records.length,concurrentReplayStatuses:[200,409],unauthorizedReplayStatus:403,canonicalStateUnchanged:true};
  });
  slowOrder=await create();
  const latency=[],published=new Map(),privateOwners=[];
  await phase('1000 clients across two replicas and private isolation',async()=>{
    assert.equal(await subscriptions(),0,'No subscriptions can remain from earlier recovery phases');
    tokenCache.delete('alicefalse');await credentials('alice');
    const count=slo.topology.healthyClients;assert.ok(count>=1000,"At least 1000 healthy clients are required");
    for(let offset=0;offset<count;offset+=25) {
      await Promise.all(Array.from({length:Math.min(25,count-offset)},(_,i)=>connect(brokers[(offset+i)%2],'/topic/orders/'+order.id,{onMessage:(hint,c)=>{
        const id=hint.data.eventId;if(!published.has(id))return;
        if(c.received.has(id))c.duplicates++;else {c.received.add(id);latency.push(Date.now()-published.get(id));}
      }})));
      if((offset+25)%100===0)console.log("Connected "+(offset+25)+" healthy clients");
    }
    for(let i=0;i<slo.topology.foreignPrivateClients;i++) await connect(brokers[i%2],'/user/queue/notifications',{who:'bob',onMessage:()=>{report.unauthorizedDeliveries++;}});
    for(let i=0;i<slo.topology.positivePrivateClients;i++)privateOwners.push(await connect(brokers[i%2],'/user/queue/notifications',{onMessage:(hint,c)=>{const id=hint.data.eventId;if(published.has(id))c.received.add(id);}}));
    await until(async()=>await subscriptions()>=count+slo.topology.foreignPrivateClients+privateOwners.length);
    assert.ok(clients.filter(c=>c.connected).length>=count);return {healthyClients:count,replicas:2};
  });
  const healthy=clients.slice(0,slo.topology.healthyClients);
  await phase('sustained throughput latency gaps and duplicates',async()=>{
    const source=producer();const warmup=randomUUID();source.emit(order.id,event(order.id,warmup));
    await until(()=>Number(sql(`SELECT count(*) FROM notifications.domain_event_inbox WHERE consumer_id='live' AND event_id='${warmup}'`))===1);
    const started=Date.now();
    for(let i=0;i<slo.topology.sustainedSeconds*slo.topology.eventsPerSecond;i++) {
      const id=randomUUID();published.set(id,Date.now());source.emit(order.id,event(order.id,id));await wait(1000/slo.topology.eventsPerSecond);
    }
    await source.end();let allReceived=true;try{await until(()=>[...healthy,...privateOwners].every(c=>c.received.size===published.size),15000);}catch(error){if(error.message!=='Acceptance condition timed out')throw error;allReceived=false;}
    latency.sort((a,b)=>a-b);const p95=latency[Math.floor(latency.length*.95)],p99=latency[Math.floor(latency.length*.99)];
    const duplicates=healthy.reduce((n,c)=>n+c.duplicates,0),expected=healthy.length*published.size,received=healthy.reduce((n,c)=>n+c.received.size,0);
    report.steady={events:published.size,expected,received,gaps:expected-received,duplicates,p95Ms:p95,p99Ms:p99,allReceived,minPerClient:Math.min(...healthy.map(c=>c.received.size)),maxPerClient:Math.max(...healthy.map(c=>c.received.size)),socketCloses:healthy.reduce((n,c)=>n+c.closed,0),heartbeatTimeouts:healthy.reduce((n,c)=>n+c.heartbeatTimeouts,0),privateReceived:privateOwners.reduce((n,c)=>n+c.received.size,0)};
    assert.equal(received,expected);assert.equal(report.steady.privateReceived,privateOwners.length*published.size);assert.equal(report.unauthorizedDeliveries,0);
    assert.ok(p95<=slo.objectives.steadyP95LatencyMs,'p95 latency '+p95+'ms exceeds objective');assert.ok(p99<=slo.objectives.steadyP99LatencyMs,'p99 latency '+p99+'ms exceeds objective');
    assert.ok(duplicates/expected<=slo.objectives.steadyDuplicateRatioMax);
    report.resources.push(JSON.parse(run(['info','--format','{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}}}'])));
    report.resources.push({dockerStats:run(['stats','--no-stream','--format','{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}',container('notification-service'),container('notification-service-2')]).trim().split('\n')});
    return {events:published.size,expected,received,gaps:expected-received,duplicates,p95Ms:p95,p99Ms:p99,deliveriesPerSecond:received/((Date.now()-started)/1000)};
  });
  await phase('foreign order and payment subscriptions are rejected',async()=>{
    const snapshot=await (await api('/'+order.id)).json();const paymentId=snapshot.paymentId;
    assert.match(paymentId,/^[0-9a-f-]{36}$/);
    const before=await subscriptions();
    for(const url of brokers)await connect(url,'/topic/payments/'+paymentId);
    await until(async()=>await subscriptions()>=before+2);
    for(const url of brokers)for(const destination of ['/topic/orders/'+order.id,'/topic/payments/'+paymentId]) {
      const identity=await credentials('bob');let denied=false;
      const c=new Client({webSocketFactory:()=>new WebSocket(url),connectHeaders:{Authorization:'Bearer '+identity.accessToken},reconnectDelay:0,debug:()=>{},onConnect:()=>c.subscribe(destination,()=>{report.unauthorizedDeliveries++;}),onStompError:()=>{denied=true;},onWebSocketClose:()=>{denied=true;}});
      c.activate();try {await until(()=>denied,10000);}finally {await c.deactivate({force:true});}
    }
    assert.equal(report.unauthorizedDeliveries,0);return {foreignDestinationsRejected:4};
  });
  await phase('idle expiry and supported-client credential refresh',async()=>{
    const idle=await connect(brokers[0],'/topic/orders/'+order.id,{short:true});await until(()=>idle.closed>0,12000);
    let acquired=0;const c=new NotificationClient({url:brokers[1],allowInsecureLocalhost:true,getIdentity:async()=>{acquired++;return credentials('alice',true);},resync:async()=>{},minRetryDelayMs:50,maxRetryDelayMs:250,resyncIntervalMs:1000});
    c.subscribe('/topic/orders/'+order.id,()=>{});supported.push(c);c.connect();await until(()=>acquired>=2 && c.state==='live',20000);
    await c.disconnect();return {idleSocketClosed:true,credentialAcquisitions:acquired};
  });
  await phase('slow readers are disconnected within bounded buffers',async()=>{
    await until(async()=>await subscriptions()===clients.filter(c=>c.connected).length);
    const before=await subscriptions();const slow=[];const processes=replicaState();
    tokenCache.delete("alicefalse");await credentials("alice");
    for(let i=0;i<slo.topology.slowReaders;i++)slow.push(await connect(brokers[i%2],'/topic/orders/'+slowOrder.id,{slow:true}));
    await until(async()=>await subscriptions()>=before+slow.length);
    const source=producer();for(let i=0;i<slo.topology.slowReaderMessages;i++)source.emit(slowOrder.id,event(slowOrder.id,randomUUID(),randomBytes(slo.topology.slowReaderPayloadChars*3/4).toString('base64')));await source.end();
    await wait(slo.topology.slowReaderPauseMs);
    for(const c of slow)c.socket?._socket?.resume();
    await until(()=>slow.some(c=>c.closed>0),30000);
    const after=replicaState();
    for(let i=0;i<after.length;i++){assert.equal(after[i].restarts,processes[i].restarts,'Slow readers cannot restart a replica');assert.equal(after[i].oomKilled,false);}
    await Promise.all(slow.map(c=>c.client.deactivate({force:true})));
    assert.ok(slow.every(c=>c.heartbeatTimeouts===0),'Slow readers must not be closed by their client heartbeat monitor');
    return {slowReaders:slow.length,messages:slo.topology.slowReaderMessages,payloadChars:slo.topology.slowReaderPayloadChars,compression:false,disconnectionObserved:true,replicas:replicaState()};
  });
  await phase('reconnect storm converges without authorization changes',async()=>{
    const start=Date.now();for(const c of healthy){c.expectedConnections=c.connections+1;c.client.forceDisconnect();}
    await until(()=>healthy.every(c=>c.connected&&c.connections>=c.expectedConnections),slo.objectives.reconnectConvergenceMs);
    await until(async()=>await subscriptions()>=healthy.length+slo.topology.foreignPrivateClients+slo.topology.positivePrivateClients+2);
    return {clients:healthy.length,reconnectedMs:Date.now()-start};
  });
  await phase('Redis disruption replica replacement and canonical convergence',async()=>{
    pause('redis');try {const cancelled=await api('/'+order.id+'/cancel','alice','POST',{reason:'real-time recovery acceptance'});assert.equal(cancelled.status,200);await wait(5000);}finally{resume('redis');}
    container('notification-service-2');run([...compose,'up','-d','--no-deps','--no-build','--force-recreate','--wait','--wait-timeout','180','notification-service-2']);
    const start=Date.now();await until(()=>healthy.every(c=>c.connected),slo.objectives.replacementConvergenceAfterHealthyMs);
    let converged=0;
    for(let offset=0;offset<healthy.length;offset+=25)await Promise.all(healthy.slice(offset,offset+25).map(async c=>{const response=await api('/'+order.id);assert.equal(response.status,200);c.canonical=(await response.json()).status;assert.equal(c.canonical,'CANCELLED');converged++;}));
    assert.ok(Date.now()-start<=slo.objectives.replacementConvergenceAfterHealthyMs);
    assert.equal(report.unauthorizedDeliveries,0);return {convergedClients:converged,convergenceMs:Date.now()-start,unauthorizedDeliveries:0};
  });
} finally {
  for(const service of [...paused])try{resume(service);}catch{}
  await Promise.allSettled(clients.map(c=>{c.socket?._socket?.resume();return c.client.deactivate({force:true});}));
  await Promise.allSettled(supported.map(c=>c.disconnect()));
  if(order)try{await api('/'+order.id+'/cancel','alice','POST',{reason:'acceptance cleanup'});}catch{}
  if(slowOrder)try{await api('/'+slowOrder.id+'/cancel','alice','POST',{reason:'slow-reader cleanup'});}catch{}
  report.cleanupPassed=false;
  if(report.phases.length===9&&report.phases.every(p=>p.passed)) {try {await until(async()=>await subscriptions()===0,15000);report.cleanupPassed=true;}catch {report.cleanupError='Subscriptions remained after all clients disconnected';}}
  report.completedAt=new Date().toISOString();report.passed=report.phases.length===9&&report.phases.every(p=>p.passed)&&report.unauthorizedDeliveries===0&&report.cleanupPassed;
  const output=resolve(root,'coverage/events-acceptance');mkdirSync(output,{recursive:true});writeFileSync(resolve(output,'realtime-load.json'),JSON.stringify(report,null,2)+'\n');
  const escape=s=>s.replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
  writeFileSync(resolve(output,'realtime-load.xml'),`<testsuite name="RealtimeDurabilityAcceptance" tests="${report.phases.length}" failures="${report.phases.filter(p=>!p.passed).length}" errors="0" skipped="0">${report.phases.map(p=>`<testcase name="${escape(p.name)}" time="${p.durationMs/1000}">${p.passed?'':`<failure message="${escape(p.error)}"/>`}</testcase>`).join('')}</testsuite>`);
}
assert.equal(report.passed,true,'All nine durability, security and load phases must execute and pass');
console.log(JSON.stringify({passed:true,phases:report.phases.length,unauthorizedDeliveries:report.unauthorizedDeliveries}));
