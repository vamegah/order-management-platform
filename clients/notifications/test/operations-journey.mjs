import assert from 'node:assert/strict';
import { randomBytes, createHmac, timingSafeEqual, createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createServer, request as httpRequest } from 'node:http';
import { once } from 'node:events';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@stomp/stompjs';
import WebSocket from 'ws';

const root=resolve(fileURLToPath(new URL('../../../',import.meta.url)));
const project=process.env.COMPOSE_PROJECT_NAME??'';
assert.match(project,/^omp-operations-[a-z0-9]{6,24}$/);
assert.equal(process.env.OMP_ACCEPTANCE_FIXTURES,'true');
const docker=process.env.E2E_DOCKER_COMMAND??'docker';
const run=args=>execFileSync(docker,args,{cwd:root,encoding:'utf8',timeout:30000,maxBuffer:4*1024*1024});
const containers=JSON.parse(run(['inspect',...run(['ps','-q','--filter','label=com.docker.compose.project='+project]).trim().split(/\s+/)]));
const catalogue=new Map();
for(const item of containers){assert.equal(item.Config.Labels['com.omp.acceptance'],'operations');catalogue.set(item.Config.Labels['com.docker.compose.service'],item);}
for(const name of ['traefik','keycloak','postgres','kafka','redis','product-service','customer-service','order-service','inventory-service','payment-service','notification-service','graphql-gateway','otel-collector','tempo'])assert.ok(catalogue.has(name),'Required journey service: '+name);
function origin(name,port){const bindings=catalogue.get(name).NetworkSettings.Ports[port+'/tcp'];assert.ok(bindings?.length,`Missing loopback binding: ${name}:${port}`);assert.equal(bindings[0].HostIp,'127.0.0.1');return 'http://127.0.0.1:'+bindings[0].HostPort;}
const ingress=origin('traefik',80),issuer=origin('keycloak',8080),tempo=origin('tempo',3200),collector=origin('otel-collector',4318);
const wsOrigin=origin('notification-service',8084).replace('http:','ws:')+'/ws/websocket';
const fixture=JSON.parse(readFileSync(resolve(root,'infrastructure/keycloak/import/omp-realm.json'),'utf8'));
const customer='00000000-0000-0000-0000-000000000101';
const output=resolve(root,'coverage/operations');mkdirSync(output,{recursive:true});
const git=(...args)=>execFileSync('git',['-c','safe.directory='+root,...args],{cwd:root,encoding:'utf8'}).trim();
const harnessSha256=createHash('sha256').update(readFileSync(fileURLToPath(import.meta.url))).digest('hex');
const report={harnessSha256,passed:false,cleanupPassed:false,sourceRevision:git('rev-parse','HEAD'),sourceDirty:!!git('status','--porcelain'),scope:'isolated real checkout protocol journey',phases:[]};
const sourceNames=execFileSync('git',['-c','safe.directory='+root,'ls-files','--cached','--others','--exclude-standard','-z'],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean).sort();
report.sourceTreeSha256=createHash('sha256').update(JSON.stringify(Object.fromEntries(sourceNames.map(name=>[name,createHash('sha256').update(readFileSync(resolve(root,name))).digest('hex')])))).digest('hex');
const info=JSON.parse(run(['info','--format','{{json .}}']));report.hardware=Object.fromEntries(['NCPU','MemTotal','OSType','Architecture','ServerVersion'].map(key=>[key,info[key]]));
report.ownedImages={};for(const name of ['product-service','customer-service','order-service','inventory-service','payment-service','notification-service','graphql-gateway']){const item=catalogue.get(name);assert.equal(item.Config.Labels['org.opencontainers.image.revision'],report.sourceRevision,'Journey image source: '+name);report.ownedImages[name]=item.Image;}
const secrets=[],receipts=[],hints=[];let webhookSecret,client,subscription,order;
const trace=randomBytes(16).toString('hex'),parent=randomBytes(8).toString('hex'),started=Date.now();
const traceparent=`00-${trace}-${parent}-01`;
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(predicate,seconds=90){const end=Date.now()+seconds*1000;while(Date.now()<end){const value=await predicate();if(value)return value;await wait(250);}throw new Error('Journey objective did not converge');}
async function request(url,options={}){
  // Native HTTP preserves virtual-host routing; Node fetch deliberately replaces Host.
  return new Promise((resolve,reject)=>{
    const body=options.body===undefined?undefined:String(options.body);
    const req=httpRequest(url,{method:options.method??'GET',headers:{...options.headers,...(body===undefined?{}:{'Content-Length':Buffer.byteLength(body)})},signal:AbortSignal.timeout(options.timeoutMs??10000)},res=>{
      const chunks=[];let size=0;res.on('data',chunk=>{size+=chunk.length;if(size>4194304)res.destroy(new Error('Bounded response exceeded'));else chunks.push(chunk);});
      res.on('error',reject);res.on('end',()=>resolve(new Response([204,304].includes(res.statusCode)?null:Buffer.concat(chunks),{status:res.statusCode,headers:res.headers})));
    });req.on('error',reject);req.end(body);
  });
}
async function token(who){const user=fixture.users.find(u=>u.username===who);assert.ok(user);const password=user.credentials.find(c=>c.type==='password').value;secrets.push(password);const response=await request(issuer+'/realms/omp/protocol/openid-connect/token',{method:'POST',headers:{Host:'keycloak.localhost','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'password',client_id:'postman',username:who,password})});assert.equal(response.status,200);const data=await response.json();secrets.push(data.access_token);return data.access_token;}
let alice,admin;
async function api(host,path,method='GET',body,bearer=alice,traced=false,timeoutMs=10000){const response=await request(ingress+path,{method,timeoutMs,headers:{Host:host,Authorization:'Bearer '+bearer,'Content-Type':'application/json',...(traced?{traceparent}:{})},body:body===undefined?undefined:JSON.stringify(body)});const text=await response.text();return {response,data:text?JSON.parse(text):undefined};}
const server=createServer({maxHeaderSize:8192},async(req,res)=>{let size=0;const chunks=[];try{assert.equal(req.method,'POST');assert.equal(req.url,'/hooks');for await(const chunk of req){size+=chunk.length;if(size>262144)throw Error('oversize');chunks.push(chunk);}const raw=Buffer.concat(chunks);const stamp=String(req.headers['x-timestamp']);assert.ok(webhookSecret&&Math.abs(Date.now()/1000-Number(stamp))<300);const expected=createHmac('sha256',webhookSecret).update(stamp+'.').update(raw).digest('hex');const actual=String(req.headers['x-signature-256']??'').replace(/^sha256=/,'');assert.equal(actual.length,expected.length);assert.ok(timingSafeEqual(Buffer.from(actual),Buffer.from(expected)));if(receipts.length<64)receipts.push({body:JSON.parse(raw),traceparent:req.headers.traceparent});res.writeHead(204).end();}catch{res.writeHead(401).end();}});
server.maxConnections=8;server.requestTimeout=5000;server.headersTimeout=5000;
function spansOf(value,result=[]){if(!value||typeof value!=='object')return result;if(value.name&&(value.spanId||value.spanID))result.push(value);for(const child of Object.values(value))if(child&&typeof child==='object'){if(Array.isArray(child))for(const item of child)spansOf(item,result);else spansOf(child,result);}return result;}
function serviceNames(value,result=new Set()){if(!value||typeof value!=='object')return result;if(value.key==='service.name')result.add(value.value?.stringValue);for(const child of Object.values(value))if(child&&typeof child==='object'){if(Array.isArray(child))for(const item of child)serviceNames(item,result);else serviceNames(child,result);}return result;}
try{
  await until(async()=>{try{return (await request(tempo+'/ready')).status===200;}catch{return false;}},30);report.tempoReady=true;
  alice=await token('alice');admin=await token('admin');
  console.log(JSON.stringify({stage:'issuer-and-telemetry-ready'}));
  const warmup=await api('order-service.localhost','/api/v1/orders','POST',{customerId:customer,shippingAddress:{line1:'isolated warmup',city:'Test',postalCode:'10001',country:'US'},lines:[{productId:'00000000-0000-0000-0000-000000000204',quantity:1}],notes:'warmup-'+trace},alice,false,90000);
  assert.equal(warmup.response.status,201);order=warmup.data.id;
  const warmupCleanup=await api('order-service.localhost','/api/v1/orders/'+order+'/cancel','POST',{reason:'isolated warmup cleanup'},alice,false,30000);assert.ok([200,202].includes(warmupCleanup.response.status));order=undefined;report.warmupCompleted=true;
  server.listen(0,'0.0.0.0');await once(server,'listening');
  const target='http://host.docker.internal:'+server.address().port+'/hooks';
  const registration=await api('notification.localhost','/api/v1/webhook-subscriptions','POST',{name:'operations-journey',targetUrl:target,eventTypes:['ORDER_CREATED']});
  assert.equal(registration.response.status,201);subscription=registration.data.id;
  webhookSecret=registration.data.secret;assert.ok(webhookSecret);secrets.push(webhookSecret);
  let connected=false;
  client=new Client({webSocketFactory:()=>new WebSocket(wsOrigin),connectHeaders:{Authorization:'Bearer '+alice},reconnectDelay:0,connectionTimeout:15000,heartbeatIncoming:10000,heartbeatOutgoing:10000,debug:()=>{}});
  client.onConnect=()=>{client.subscribe('/user/queue/notifications',frame=>{if(hints.length<64)hints.push(JSON.parse(frame.body));});connected=true;};
  let rejected=false;client.onStompError=()=>{rejected=true;};client.activate();await until(()=>{assert.ok(!rejected,'Owned subscription was rejected');return connected;},20);
  await until(async()=>{const response=await request(origin('notification-service',8085)+'/actuator/prometheus',{headers:{Authorization:'Bearer '+admin}});assert.equal(response.status,200);const line=(await response.text()).split('\n').find(l=>l.startsWith('websocket_subscriptions_active'));return line&&Number(line.trim().split(/\s+/).at(-1))>=1;},20);
  console.log(JSON.stringify({stage:'owned-listeners-ready'}));
  const creation=await api('graphql.localhost','/graphql','POST',{query:'mutation OpsCheckout($input:CreateOrderInput!){createOrder(input:$input){id customer{id} lines{product{id}}}}',variables:{input:{customerId:customer,shippingAddress:{line1:'private-operations-address',city:'Test',postalCode:'10001',country:'US'},lines:[{productId:'00000000-0000-0000-0000-000000000204',quantity:1}],notes:'private-operations-notes'}}},alice,true);
  assert.equal(creation.response.status,200);assert.ok(!creation.data.errors,JSON.stringify(creation.data.errors?.map(e=>e.extensions?.code)));order=creation.data.data.createOrder.id;assert.equal(creation.data.data.createOrder.customer.id,customer);
  const rest=await api('order-service.localhost','/api/v1/orders/'+order,'GET',undefined,alice,true);assert.equal(rest.response.status,200);assert.ok(rest.data.paymentId&&rest.data.lines.every(l=>l.reservationId));
  console.log(JSON.stringify({stage:'checkout-accepted'}));
  const receipt=await until(()=>receipts.find(r=>r.body.data?.orderId===order));await until(()=>hints.some(h=>JSON.stringify(h).includes(order)));
  report.traceCorrelation={expectedTraceId:trace,deliveredTraceId:receipt.traceparent?.split('-')[1]};
  assert.match(order,/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  report.traceCorrelation.persistedOrderTraceparent=run(['exec',catalogue.get('postgres').Id,'psql','-XAtq','--set=ON_ERROR_STOP=1','-U','omp_admin','-d','omp','-c',`SELECT payload->>'traceparent' FROM orders.outbox_event WHERE aggregate_id='${order}' AND event_type IN ('ORDER_CREATED','order.created') LIMIT 1`]).trim();

  if(report.traceCorrelation.deliveredTraceId!==trace){
    await wait(20000);report.propagationDiagnostics=[];
    for(const id of new Set([trace,report.traceCorrelation.deliveredTraceId])){
      const response=await request(tempo+'/api/traces/'+id);
      const data=response.status===200?await response.json():null;
      report.propagationDiagnostics.push({traceId:id,status:response.status,services:data?[...serviceNames(data)]:[],spans:data?spansOf(data).map(span=>({name:span.name,spanId:span.spanId??span.spanID,parentSpanId:span.parentSpanId??span.parentSpanID})):[]});
    }
  }
  assert.match(receipt.traceparent,new RegExp('^00-'+trace+'-[0-9a-f]{16}-01$'));
  report.phases.push({name:'graphql-rest-grpc-kafka-websocket-webhook',passed:true,durableReferences:true,ownedHint:true,signedWebhook:true,traceparentForwarded:true});
  // The client observation includes an actual bounded settlement window, ensuring deterministic slow-trace retention.
  await wait(Math.max(0,1100-(Date.now()-started)));
  const emitted=await request(collector+'/v1/traces',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({resourceSpans:[{resource:{attributes:[{key:'service.name',value:{stringValue:'operations-acceptance-client'}}]},scopeSpans:[{scope:{name:'operations-journey'},spans:[{traceId:trace,spanId:parent,name:'checkout.settlement',kind:3,startTimeUnixNano:String(BigInt(started)*1000000n),endTimeUnixNano:String(BigInt(Date.now())*1000000n),status:{code:1}}]}]}]})});assert.equal(emitted.status,200);
  const required=['graphql-gateway','order-service','product-service','customer-service','inventory-service','payment-service','notification-service'];
  const operations=['grpc.client','grpc.server','outbox.publish','event.ingest','websocket.dispatch','webhook.deliver','checkout.settlement'];
  const traceData=await until(async()=>{const response=await request(tempo+'/api/traces/'+trace);if(response.status===404)return false;assert.equal(response.status,200);const data=await response.json(),names=serviceNames(data),spans=spansOf(data);return required.every(s=>names.has(s))&&operations.every(o=>spans.some(s=>s.name.startsWith(o)))?data:false;});
  const encoded=JSON.stringify(traceData);for(const value of [...secrets,'private-operations-address','private-operations-notes'])assert.ok(!encoded.includes(value),'Sensitive telemetry was retained');
  const spans=spansOf(traceData);const hex=v=>/^[0-9a-f]+$/i.test(v??'')?v.toLowerCase():Buffer.from(v??'','base64').toString('hex');for(const span of spans)assert.equal(hex(span.traceId??span.traceID),trace);
  report.phases.push({name:'complete-retained-private-trace',passed:true,traceId:trace,services:required,operations,spanCount:spans.length,sensitiveValuesAbsent:true});
  const metric=await request(ingress+'/actuator/prometheus',{headers:{Host:'graphql.localhost',Authorization:'Bearer '+admin,Accept:'application/openmetrics-text; version=1.0.0; charset=utf-8'}});assert.equal(metric.status,200);const metricText=await metric.text();assert.ok(metricText.includes('http_server_requests_seconds_bucket')&&metricText.includes(trace),'Real HTTP latency exemplar is absent');
  const logServices=[];for(const service of ['order-service','notification-service']){const logs=run(['logs','--since','5m',catalogue.get(service).Id]);const found=logs.split('\n').some(line=>{try{const row=JSON.parse(line);return (row.traceId??row.trace_id)===trace;}catch{return false;}});assert.ok(found,'Structured log correlation absent: '+service);logServices.push(service);}
  report.phases.push({name:'operator-exemplar-and-log-correlation',passed:true,exemplarTraceId:trace,structuredLogServices:logServices});report.passed=true;
} catch(error){report.errorClass=error.name;report.failureStage=report.phases.length;throw error;}
finally{
  let cleaned=true;
  try{if(order&&alice){const result=await api('order-service.localhost','/api/v1/orders/'+order+'/cancel','POST',{reason:'isolated journey cleanup'});assert.ok([200,202].includes(result.response.status));}if(subscription&&alice){const result=await api('notification.localhost','/api/v1/webhook-subscriptions/'+subscription,'DELETE');assert.ok([200,204].includes(result.response.status));}}catch{cleaned=false;report.passed=false;}
  if(client)await client.deactivate({force:true});server.closeAllConnections();await new Promise(done=>server.close(done));report.cleanupPassed=cleaned;
  writeFileSync(resolve(output,'protocol-journey.json'),JSON.stringify(report,null,2));
}
assert.ok(report.passed&&report.cleanupPassed);console.log(JSON.stringify({passed:true,phases:report.phases.length,traceId:trace}));
