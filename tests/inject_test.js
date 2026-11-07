const fs=require('fs'); const sent=[];
global.window=global; global.GhostBridge={isGhost:()=>true,onBlocked(){},onMedia(){},onError(){}};
global.document={cookie:'',addEventListener(){}}; global.navigator={sendBeacon:(u)=>{sent.push('B '+u);return true;}};
global.Response=class{constructor(b,o){this.status=200}}; global.Event=class{constructor(t){this.type=t}};
global.fetch=(u,i)=>{sent.push(u);return Promise.resolve(new Response(''))};
class XHR{open(m,u){}addEventListener(){}dispatchEvent(){}send(b){sent.push('XHR '+b)}} global.XMLHttpRequest=XHR;
global.FormData=class{}; 
eval(fs.readFileSync(process.argv[2],'utf8'));
(async()=>{
 await fetch('/api/graphql',{method:'POST',body:'fb_api_req_friendly_name=PolarisStoriesV3SeenMutation'});
 await fetch('/api/v1/stories/reel/seen/',{method:'POST',body:'x=1'});
 const x=new XMLHttpRequest(); x.open('POST','/api/graphql'); x.send('fb_api_req_friendly_name=PolarisAPIReelSeenMutation');
 await fetch('/api/graphql',{method:'POST',body:'fb_api_req_friendly_name=PolarisFeedQuery'});
 await new Promise(r=>setTimeout(r,30));
 const leaked=sent.filter(s=>/seen/i.test(s));
 console.log(leaked.length===0?'PASS website mode blocks seen':'FAIL leaked '+leaked);
 console.log(sent.length===1?'PASS normal requests still work':'FAIL normal requests: '+sent.length);
})();
