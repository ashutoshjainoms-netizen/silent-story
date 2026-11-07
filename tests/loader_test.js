// Simulated browser for loader.js
const fs = require('fs');
const sent = [];          // requests that actually reached "Instagram"
let reported = null;
global.window = global;
global.GVLoader = { onItems: j => { reported = JSON.parse(j); } };
global.document = { addEventListener(){}, querySelectorAll(){ return []; } };
global.addEventListener = () => {};
global.navigator = { sendBeacon: () => { sent.push('BEACON'); return true; } };
global.Response = class { constructor(b,o){ this.b=b; this.status=(o||{}).status||200; } clone(){ return this; } text(){ return Promise.resolve(this.b); } };
const sample = JSON.stringify({ data: { xdt_api__v1__feed__reels_media: { reels_media: [
  { user: { username: 'angad_chhajed.07', pk: '67986116974' }, items: [
    { pk: '111', taken_at: 1730900000, image_versions2: { candidates: [ {width:640,height:1136,url:'https://cdn/img_small.jpg'}, {width:1080,height:1920,url:'https://cdn/img_big.jpg'} ] } },
    { pk: '222', taken_at: 1730900100, image_versions2: { candidates: [ {width:1080,height:1920,url:'https://cdn/cover.jpg'} ] },
      video_versions: [ {width:480,height:854,url:'https://cdn/v_low.mp4'}, {width:720,height:1280,url:'https://cdn/v_hd.mp4'} ] } ] },
  { user: { username: 'someone_else' }, items: [ { pk: '333', taken_at: 1730900200, image_versions2: { candidates: [ {width:1,height:1,url:'https://cdn/other.jpg'} ] } } ] }
]}}});
global.fetch = (url, init) => { sent.push(url + ' ' + ((init&&init.body)||'')); return Promise.resolve(new Response('for (;;);' + sample)); };
class XHR { open(m,u){ this.u=u; } addEventListener(t,f){ this['on_'+t]=f; } dispatchEvent(e){ (this.ev=this.ev||[]).push(e.type); } send(b){ sent.push('XHR '+this.u+' '+b); } }
global.XMLHttpRequest = XHR; global.Event = class { constructor(t){ this.type=t; } };
global.setInterval = () => 0;
eval(fs.readFileSync(process.argv[2], 'utf8'));

(async () => {
  let pass = 0, fail = 0;
  const check = (name, ok) => { console.log((ok?'PASS ':'FAIL ') + name); ok?pass++:fail++; };
  // 1. seen mutations (both styles) must never reach Instagram
  sent.length = 0;
  await window.fetch('/api/graphql', { method:'POST', body:'fb_api_req_friendly_name=PolarisStoriesV3SeenMutation&variables=%7B%7D' });
  await window.fetch('/api/v1/stories/reel/seen/', { method:'POST', body:'reelMediaId=1' });
  const p = new URLSearchParams(); p.append('fb_api_req_friendly_name','usePolarisStoriesV3SeenDirectMutation');
  await window.fetch('/graphql/query', { method:'POST', body: p });
  const x = new XMLHttpRequest(); x.open('POST','/api/v1/media/seen/'); x.send('reels=1');
  const x2 = new XMLHttpRequest(); x2.open('POST','/api/graphql'); x2.send('fb_api_req_friendly_name=PolarisAPIReelSeenMutation');
  navigator.sendBeacon('/ajax/bz', 'x');
  await new Promise(r => setTimeout(r, 50));
  check('no seen request or beacon reached Instagram (' + sent.length + ' leaked)', sent.length === 0);
  check('blocked XHR still looks successful to Instagram code', (x.ev||[]).includes('load') && x.status === 200);
  // 2. normal story query passes through and media is picked up
  sent.length = 0;
  await window.fetch('/api/graphql', { method:'POST', body:'fb_api_req_friendly_name=PolarisStoriesV3ReelPageGalleryQuery&variables=%7B%7D' });
  check('normal story request is allowed', sent.length === 1);
  await new Promise(r => setTimeout(r, 1000));
  check('items reported to app', Array.isArray(reported));
  const mine = (reported||[]).filter(i => i.user === 'angad_chhajed.07');
  check('2 stories found for target user', mine.length === 2);
  check('photo uses highest resolution', (mine.find(i=>i.pk==='111')||{}).url === 'https://cdn/img_big.jpg');
  const v = mine.find(i=>i.pk==='222')||{};
  check('video detected and HD video chosen', v.video === true && v.url === 'https://cdn/v_hd.mp4');
  check('other user labelled separately', (reported||[]).some(i => i.user === 'someone_else'));
  console.log(`\n${pass} passed, ${fail} failed`); process.exit(fail ? 1 : 0);
})();
