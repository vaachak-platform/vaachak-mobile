import {test} from 'node:test';
import assert from 'node:assert/strict';
import {timingSafeEqual} from 'node:crypto';
import {createWorker} from '../src/index.js';
const key = 'a'.repeat(64);
const model = '@cf/black-forest-labs/flux-1-schnell';
const worker = createWorker((a,b) => timingSafeEqual(Buffer.from(a), Buffer.from(b)));
// Signature-only synthetic JPEG bytes; not proof of a decodable image.
const image = Buffer.from([255,216,255,224,255,217]);
function fixture(overrides={}) {
  const calls = [];
  return {calls, env: {API_KEY:key, IMAGE_MODEL:model, IMAGE_LIMIT:{limit: async () => ({success:true})},
    'image-ai':{run:async (...args) => {calls.push(args); return {image:image.toString('base64')};}}, ...overrides}};
}
function req({method='POST',path='/',auth=`Bearer ${key}`,type='application/json',body=JSON.stringify({prompt:'cottage'})}={}) {
  const headers = {};
  if (auth !== null) headers.Authorization = auth;
  if (type !== null) headers['Content-Type'] = type;
  return new Request('https://example.invalid'+path,{method,headers,...(method==='GET'?{}:{body})});
}
for (const [name,options,overrides,status,code] of [
  ['no auth',{auth:null},{},401,'UNAUTHORIZED'],
  ['wrong auth',{auth:'Bearer wrong'},{},401,'UNAUTHORIZED'],
  ['missing secret',{}, {API_KEY:undefined},503,'CONFIGURATION_ERROR'],
  ['invalid secret',{}, {API_KEY:'undefined'},503,'CONFIGURATION_ERROR'],
  ['wrong path',{path:'/other'},{},404,'NOT_FOUND'],
  ['wrong method',{method:'GET'},{},405,'METHOD_NOT_ALLOWED'],
  ['wrong content type',{type:'text/plain'},{},415,'UNSUPPORTED_MEDIA_TYPE'],
  ['bad json',{body:'{'},{},400,'INVALID_JSON'],
  ['empty prompt',{body:'{"prompt":"  "}'},{},400,'INVALID_PROMPT'],
  ['wrong prompt type',{body:'{"prompt":12}'},{},400,'INVALID_PROMPT'],
  ['long prompt',{body:JSON.stringify({prompt:'a'.repeat(2049)})},{},400,'INVALID_PROMPT'],
  ['large body',{body:JSON.stringify({prompt:'a'.repeat(20000)})},{},413,'REQUEST_TOO_LARGE'],
  ['unknown model',{}, {IMAGE_MODEL:'unqualified'},503,'CONFIGURATION_ERROR'],
  ['missing AI',{}, {'image-ai':undefined},503,'CONFIGURATION_ERROR'],
  ['missing rate limit',{}, {IMAGE_LIMIT:undefined},503,'CONFIGURATION_ERROR'],
  ['limited',{}, {IMAGE_LIMIT:{limit:async()=>({success:false})}},429,'RATE_LIMITED'],
  ['rate service down',{}, {IMAGE_LIMIT:{limit:async()=>{throw Error('private data')}}},503,'RATE_LIMITER_UNAVAILABLE']
]) test(name,async()=>{const {env,calls}=fixture(overrides);const r=await worker.fetch(req(options),env);assert.equal(r.status,status);assert.equal((await r.json()).code,code);assert.equal(calls.length,0);assert.equal(r.headers.get('Cache-Control'),'no-store');});
test('legacy extra request parameters ignored; model and steps are server-owned',async()=>{
  const {env,calls}=fixture(); const r=await worker.fetch(req({body:JSON.stringify({prompt:' cottage ',width:384,height:512,model:'wrong',steps:999})}),env);
  assert.equal(r.status,200); assert.equal(r.headers.get('Content-Type'),'image/jpeg'); assert.equal(r.headers.get('X-Content-Type-Options'),'nosniff');
  assert.deepEqual(Buffer.from(await r.arrayBuffer()),image); assert.deepEqual(calls,[[model,{prompt:'cottage',steps:4}]]);
});
for(const [name,result] of [['empty',{}],['malformed',{image:'!@#'}],['not image',{image:btoa('private text')}],['too large',{image:'A'.repeat(12*1024*1024)}]]) test(name,async()=>{
  const {env}=fixture({'image-ai':{run:async()=>result}}); const r=await worker.fetch(req(),env);assert.equal(r.status,502);assert.equal((await r.json()).code,'INVALID_IMAGE_RESPONSE');
});
test('provider exception is sanitized',async()=>{const {env}=fixture({'image-ai':{run:async()=>{throw Error(key+'private prompt')}}});const r=await worker.fetch(req(),env);assert.equal(r.status,502);const body=await r.text();assert.ok(!body.includes(key));assert.ok(!body.includes('private prompt'));});
