const fs = require('node:fs');
const os = require('node:os');
const cp = require('node:child_process');
const path = require('node:path');
const { createRequire } = require('node:module');
const localRequire = createRequire(process.cwd() + '/package.json');
const WebSocket = localRequire('ws');
const outputDir = fs.mkdtempSync(path.join(os.tmpdir(), 'robomart-assessment-output-'));
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'robomart-assessment-chrome-'));
const chrome = cp.spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', ['--headless=new', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank']);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const requests = [];
const products = Array.from({length:12}, (_,i) => ({id:i+1,sku:'ASSESS-'+i,name:'Assessment product '+(i+1),price:49.99,rating:4,brand:'Fixture',stockQuantity:30,categoryName:'Electronics',categoryId:1,primaryImageUrl:'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="lightblue"/></svg>'}));
const now = Math.floor(Date.now()/1000);
function oidc(admin) {
 const claims = {sub:'assessment-only',preferred_username:'Assessment',given_name:'Assessment',realm_access:{roles:admin?['ADMIN']:['CUSTOMER']},exp:now+3600,iat:now};
 return {id_token:'fixture',session_state:'fixture',access_token:Buffer.from('{}').toString('base64url')+'.'+Buffer.from(JSON.stringify(claims)).toString('base64url')+'.fixture',token_type:'Bearer',scope:'openid profile email',profile:claims,expires_at:now+3600};
}
async function run() {
 const endpoint = await new Promise((resolve,reject) => {
  let out=''; chrome.stderr.on('data', d => {out+=d; const m=out.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(m)resolve(m[1]);});
  chrome.on('exit', c => reject(new Error('Chrome exit '+c+' '+out)));
 });
 const ws = new WebSocket(endpoint); await new Promise(r=>ws.on('open',r));
 let id=0, session, pending=new Map();
 const send=(method,params={},sid=session)=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params,...(sid?{sessionId:sid}:{})}));});
 ws.on('message', async raw=>{try {
  const m=JSON.parse(raw);
  if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}return;}
  if(m.method==='Fetch.requestPaused') {
   const {requestId,request}=m.params;const u=new URL(request.url);
   if(u.port==='15173'||u.port==='15174'||u.protocol==='data:') {await send('Fetch.continueRequest',{requestId},m.sessionId);return;}
   requests.push({method:request.method,url:request.url});
   if(u.port==='8080') {
    let data={data:[],traceId:'assessment-fixture'};
    if(u.pathname.includes('/products')) data={data:products,pagination:{page:Number(u.searchParams.get('page')||0),size:Number(u.searchParams.get('size')||20),totalElements:250,totalPages:13},traceId:'assessment-fixture'};
    if(u.pathname==='/api/v1/cart') data={data:{items:[{productId:1,productName:products[0].name,price:49.99,quantity:1,subtotal:49.99}],totalItems:1,totalPrice:49.99},traceId:'assessment-fixture'};
    if(u.pathname==='/api/v1/admin/orders') data={data:[{id:1,userId:'assessment-only',status:'CONFIRMED',totalAmount:49.99,itemCount:1,createdAt:new Date().toISOString()}],pagination:{page:Number(u.searchParams.get('page')||0),size:Number(u.searchParams.get('size')||25),totalElements:250,totalPages:10},traceId:'assessment-fixture'};
    await send('Fetch.fulfillRequest',{requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'application/json'},{name:'Access-Control-Allow-Origin',value:'*'},{name:'Access-Control-Allow-Headers',value:'*'},{name:'Access-Control-Allow-Methods',value:'GET,POST,PUT,DELETE,OPTIONS'}],body:Buffer.from(JSON.stringify(data)).toString('base64')},m.sessionId);
   } else await send('Fetch.failRequest',{requestId,errorReason:'BlockedByClient'},m.sessionId);
  }
 }catch(e){if(!e.message.includes('Invalid InterceptionId')) console.error('CDP event',e.message);}});
 const {targetId}=await send('Target.createTarget',{url:'about:blank'},null);
 session=(await send('Target.attachToTarget',{targetId,flatten:true},null)).sessionId;
 await send('Page.enable');await send('Runtime.enable');await send('Fetch.enable',{patterns:[{urlPattern:'*'}]});
 await send('Page.addScriptToEvaluateOnNewDocument',{source:`if(location.port==='15174')sessionStorage.setItem('oidc.user:http://localhost:8180/realms/robomart:robo-mart-admin',${JSON.stringify(JSON.stringify(oidc(true)))});`});
 const ev=async expression=>{const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
 const go=async(url,width)=>{await send('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:width<500});await send('Page.navigate',{url});for(let i=0;i<50;i++){await sleep(100);if(await ev('document.querySelector("h1")?.textContent || ""'))break;}await sleep(250);};
 const metrics=()=>ev(`(()=>{const r=s=>{const e=document.querySelector(s);if(!e)return null;const b=e.getBoundingClientRect();return {x:b.x,width:b.width,height:b.height,columns:getComputedStyle(e).gridTemplateColumns}};return {path:location.pathname,viewport:innerWidth,documentWidth:document.documentElement.scrollWidth,h1:document.querySelector('h1')?.textContent,grid:r('.home__grid'),sidebar:r('.admin-sidebar'),main:r('.admin-content'),icons:[...document.querySelectorAll('.pi')].slice(0,3).map(e=>({class:e.className,font:getComputedStyle(e,':before').fontFamily,content:getComputedStyle(e,':before').content}))}})()`);
 const shot=async name=>{const s=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});fs.writeFileSync(path.join(outputDir, 'robomart-assessment-'+name+'.png'),Buffer.from(s.data,'base64'));};
 const output={browser:await send('Browser.getVersion',{},null),fixture:'All API/identity traffic intercepted; no real backend or Keycloak accessed',checks:[]};
 await go('http://127.0.0.1:15173/',1440);output.checks.push({name:'customer-desktop',...await metrics()});
 await ev(`document.querySelector('.product-card button').focus()`);
 output.checks.push({name:'focused-cart-button',...await ev(`({active:document.activeElement?.textContent,opacity:getComputedStyle(document.querySelector('.product-card__overlay')).opacity})`)});
 await ev(`document.querySelector('.product-card button').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`);await sleep(200);
 output.checks.push({name:'cart-button-enter-bubble',path:await ev('location.pathname')});
 await go('http://127.0.0.1:15173/',390);output.checks.push({name:'customer-mobile',...await metrics()});await shot('customer-mobile');
 await ev(fs.readFileSync(localRequire.resolve('axe-core/axe.min.js'),'utf8'));
 output.checks.push({name:'customer-mobile-axe',violations:await ev(`axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}}).then(r=>r.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.length,example:v.nodes[0].target})))`)});
 await go('http://127.0.0.1:15174/admin/orders',1440);output.checks.push({name:'admin-desktop',...await metrics()});
 await ev(fs.readFileSync(localRequire.resolve('axe-core/axe.min.js'),'utf8'));
 output.checks.push({name:'admin-desktop-axe',violations:await ev(`axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}}).then(r=>r.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.length,example:v.nodes[0].target})))`)});
 await go('http://127.0.0.1:15174/admin/orders',390);output.checks.push({name:'admin-mobile',...await metrics()});await shot('admin-mobile');
 await ev(`document.querySelector('[aria-label="Collapse sidebar"]').click()`);await sleep(250);
 output.checks.push({name:'admin-collapsed-links',links:await ev(`[...document.querySelectorAll('.admin-nav-item')].map(e=>({text:e.textContent.trim(),ariaLabel:e.getAttribute('aria-label')}))`)});
 await go('http://127.0.0.1:15173/',1440);
 await ev(`sessionStorage.setItem('oidc.user:http://localhost:8180/realms/robomart:robo-mart-frontend',${JSON.stringify(JSON.stringify(oidc(false)))})`);
 await go('http://127.0.0.1:15173/orders/1?assessment=true#details',1440);
 output.checks.push({name:'customer-stored-session-deep-link',...await metrics()});
 output.requests=requests;fs.writeFileSync(path.join(outputDir, 'robomart-assessment-browser.json'),JSON.stringify(output,null,2));console.log(JSON.stringify(output,null,2));
 ws.close();
}
run().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{chrome.kill();await sleep(500);fs.rmSync(profile,{recursive:true,force:true,maxRetries:5,retryDelay:100});});
