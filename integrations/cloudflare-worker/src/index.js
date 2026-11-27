const MODEL = '@cf/black-forest-labs/flux-1-schnell';
const MAX_REQUEST = 16 * 1024;
const MAX_IMAGE = 8 * 1024 * 1024;
const MAX_PROMPT = 2048;
const COMMON = {'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'};
const json = (code, status, headers = {}) => new Response(JSON.stringify({error: code, code}), {
  status, headers: {...COMMON, 'Content-Type': 'application/json', ...headers}
});

// Compare fixed-length digests using the Workers Web Crypto extension in production.
// Refuse to run if that extension is unavailable; tests inject a comparison function.
async function authorized(request, secret, equal) {
  const supplied = request.headers.get('Authorization') || '';
  if (!supplied.startsWith('Bearer ') || supplied.length > 512) return false;
  const encoder = new TextEncoder();
  const [a, b] = await Promise.all([supplied, `Bearer ${secret}`].map(value =>
    crypto.subtle.digest('SHA-256', encoder.encode(value))));
  return equal(a, b);
}

async function readBody(request) {
  const reader = request.body?.getReader();
  if (!reader) throw new SyntaxError();
  const decoder = new TextDecoder('utf-8', {fatal: true});
  let total = 0, text = '';
  try {
    for (;;) {
      const {done, value} = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_REQUEST) {
        await reader.cancel();
        throw new RangeError();
      }
      text += decoder.decode(value, {stream: true});
    }
    return JSON.parse(text + decoder.decode());
  } finally { reader.releaseLock(); }
}

function mime(bytes) {
  if (bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 &&
      bytes[bytes.length - 2] === 255 && bytes[bytes.length - 1] === 217) return 'image/jpeg';
  if (bytes.length >= 8 && [137,80,78,71,13,10,26,10].every((b,i) => bytes[i] === b)) return 'image/png';
  if (bytes.length >= 12 && String.fromCharCode(...bytes.subarray(0,4)) === 'RIFF' &&
      String.fromCharCode(...bytes.subarray(8,12)) === 'WEBP') return 'image/webp';
  return null;
}

function decodeImage(result) {
  const encoded = result?.image;
  if (typeof encoded !== 'string' || !encoded || encoded.length > Math.ceil(MAX_IMAGE / 3) * 4) return null;
  try {
    const bytes = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
    const type = mime(bytes);
    return type && bytes.length <= MAX_IMAGE ? {bytes, type} : null;
  } catch { return null; }
}

export function createWorker(equal = (a,b) => crypto.subtle.timingSafeEqual(a,b)) {
  return {async fetch(request, env) {
    // Only the tested model adapter is enabled. Unknown deployment configuration fails closed.
    if (typeof env.API_KEY !== 'string' || !/^[a-fA-F0-9]{64}$/.test(env.API_KEY)) return json('CONFIGURATION_ERROR',503);
    try {
      if (!await authorized(request, env.API_KEY, equal)) return json('UNAUTHORIZED',401);
    } catch { return json('CONFIGURATION_ERROR',503); }
    if (new URL(request.url).pathname !== '/') return json('NOT_FOUND',404);
    if (request.method !== 'POST') return json('METHOD_NOT_ALLOWED',405,{Allow:'POST'});
    if ((request.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase() !== 'application/json') {
      return json('UNSUPPORTED_MEDIA_TYPE',415);
    }
    let body;
    try { body = await readBody(request); }
    catch (e) { return e instanceof RangeError ? json('REQUEST_TOO_LARGE',413) : json('INVALID_JSON',400); }
    const prompt = typeof body?.prompt === 'string' ? body.prompt.trim() : '';
    if (!prompt || prompt.length > MAX_PROMPT) return json('INVALID_PROMPT',400);
    const model = env.IMAGE_MODEL;
    if (model !== MODEL || typeof env['image-ai']?.run !== 'function' || typeof env.IMAGE_LIMIT?.limit !== 'function') {
      return json('CONFIGURATION_ERROR',503);
    }
    try {
      if (!(await env.IMAGE_LIMIT.limit({key:'visualize'})).success) return json('RATE_LIMITED',429,{'Retry-After':'60'});
    } catch { return json('RATE_LIMITER_UNAVAILABLE',503); }
    let result;
    try { result = await env['image-ai'].run(model,{prompt,steps:4}); }
    catch { return json('PROVIDER_ERROR',502); }
    const image = decodeImage(result);
    if (!image) return json('INVALID_IMAGE_RESPONSE',502);
    return new Response(image.bytes,{status:200,headers:{...COMMON,'Content-Type':image.type}});
  }};
}
export default createWorker();
