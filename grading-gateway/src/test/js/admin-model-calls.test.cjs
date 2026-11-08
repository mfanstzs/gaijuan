"use strict";
const test=require("node:test"),assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),vm=require("node:vm");
const source=fs.readFileSync(path.resolve(__dirname,"../../main/resources/admin/app.js"),"utf8");
const USER="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",REQUEST="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const summary=(changes={})=>({kind:"GRADING",requestId:REQUEST,userId:USER,username:"teacher_one",modelId:"example-model",profileId:"example-profile",policyRevision:4,state:"SUCCEEDED",chargedCredits:1,errorCode:null,createdAt:"2026-09-12T01:00:00Z",completedAt:"2026-09-12T01:00:01Z",requestSnapshotAvailable:true,responseAvailable:true,...changes});
const page=(changes={})=>({calls:[summary()],page:1,pageSize:20,total:41,totalPages:3,...changes});
function detail(changes={}) {
  const result={status:"SCORED",readability:"CLEAR",studentText:"学生写了 <img src=x onerror=alert(1)>",criteria:[
    {criterionId:"r1",awarded:0,evidence:"答题依据 <script>alert(1)</script>",reason:"未达到评分要求"},
    {criterionId:"r2",awarded:null,evidence:null,reason:"无法判定该项"}],issues:["需要人工核对"]};
  return {schemaVersion:1,call:summary(),requestSnapshotStatus:"RECORDED",request:{schemaVersion:1,kind:"GRADING",
    question:{id:"Q1",prompt:"题目正文",standardAnswer:"标准答案正文",maxScore:5,criteria:[{id:"r1",description:"第一项评分规则",allowedScores:[0,2]}]},
    rubric:{rawRubric:"完整评分规则正文",scoreStep:0.5},providerRequest:{model:"example-model",response_format:{type:"json_object"},messages:[
      {role:"system",content:[{type:"text",text:"实际系统提示词"}]},{role:"user",content:[{type:"text",text:"实际用户提示词"},{type:"image",sha256:"a".repeat(64),bytes:1234}]}]}},
    response:{result,usage:{inputTokens:100,outputTokens:50,totalTokens:150}},modelOutput:JSON.stringify(result),...changes};
}
class Element {
  constructor(tag="div") {this.tagName=tag.toUpperCase();this.children=[];this.events={};this.attributes={};this.dataset={};this.className="";this.value="";this.hidden=false;this.disabled=false;this.open=false;this._text="";this.classList={add:(...names)=>{this.className+=" "+names.join(" ");}};}
  get textContent(){return this._text+this.children.map(x=>x.textContent).join("");}
  set textContent(text){this._text=String(text);this.children=[];}
  set innerHTML(_){throw new Error("Unsafe HTML assignment");}
  append(...items){for(const item of items){if(item.tagName==="#DOCUMENT-FRAGMENT")this.children.push(...item.children);else this.children.push(item);}}
  prepend(item){this.children.unshift(item);}
  replaceChildren(...items){this.children=[];this._text="";this.append(...items);}
  setAttribute(key,value){this.attributes[key]=String(value);}
  removeAttribute(key){delete this.attributes[key];}
  addEventListener(event,callback){(this.events[event]??=[]).push(callback);}
  async fire(event){for(const callback of this.events[event]||[])await callback({preventDefault(){},target:this});}
}
function descendants(root){return [root,...root.children.flatMap(descendants)];}
async function settle(){for(let count=0;count<8;count++)await new Promise(resolve=>setImmediate(resolve));}
async function until(predicate){for(let count=0;count<40&&!predicate();count++)await settle();assert.ok(predicate(),"Expected bounded UI state was not reached");}
function harness(respond,{hash="#/model-calls"}={}) {
  const ids=new Map(["login-screen","login-message","login-form","login-button","username","password","shell","notice","account-name","page-title","page-content","logout-button"].map(id=>[id,new Element()]));
  const nav=Object.keys({users:1,models:1,"model-calls":1,audit:1,orders:1,packages:1}).map(section=>{const item=new Element("a");item.dataset.section=section;return item;});
  const window=new Element(),location={_hash:hash},requests=[],copied=[];
  Object.defineProperty(location,"hash",{get(){return this._hash;},set(value){this._hash=value;queueMicrotask(()=>void window.fire("hashchange"));}});
  const document={title:"",getElementById:id=>ids.get(id),createElement:tag=>new Element(tag),createDocumentFragment:()=>new Element("#document-fragment"),querySelectorAll:selector=>selector==="nav [data-section]"?nav:[]};
  const context={document,window,location,Node:Element,URLSearchParams,TextDecoder,Uint8Array,AbortController,crypto:require("node:crypto").webcrypto,
    navigator:{clipboard:{writeText:async value=>{copied.push(value);}}},
    setTimeout:(callback,ms)=>{const timer=setTimeout(callback,ms);timer.unref();return timer;},clearTimeout,
    fetch:async(url,options)=>{
      if(requests.length>=50)throw new Error("Request loop guard");requests.push({url,options});
      const body=url==="/v1/auth/login"?{token:"synthetic-admin-token",expiresAt:new Date(Date.now()+3600000).toISOString(),account:{role:"ADMIN",username:"operator"}}:await respond(new URL(url,"http://127.0.0.1:8787"),options);
      return new Response(JSON.stringify(body),{status:200});
    }};
  vm.runInNewContext(source,context,{filename:"admin/app.js",timeout:1000});
  return {ids,requests,copied,location,context,
    login:async()=>{ids.get("username").value="operator";ids.get("password").value="synthetic test password";await ids.get("login-form").fire("submit");await settle();},
    go:async value=>{location.hash=value;await settle();},
    text:()=>ids.get("page-content").textContent,
    all:tag=>descendants(ids.get("page-content")).filter(item=>item.tagName===tag.toUpperCase()),
    dispose:async()=>{await window.fire("pagehide");}};
}
const detailHash=()=>"#/model-calls/"+REQUEST+"?kind=GRADING&userId="+USER;

test("list uses authenticated paged API and renders untrusted account text without HTML",async t=>{
  const h=harness(async()=>page({calls:[summary({username:"<img src=x onerror=alert(1)>"}),summary({state:"FAILED",responseAvailable:false,modelOutputAvailable:true})]}));t.after(h.dispose);await h.login();
  const request=h.requests.find(item=>item.url.startsWith("/v1/admin/model-calls?"));
  assert.equal(new URL(request.url,"http://localhost").searchParams.get("page"),"1");
  assert.equal(request.options.headers.Authorization,"Bearer synthetic-admin-token");
  assert.match(h.text(),/<img src=x onerror=alert\(1\)>/);assert.equal(h.all("img").length,0);assert.equal(h.all("script").length,0);
  assert.match(h.text(),/已完成/);assert.match(h.text(),/1 次/);assert.match(h.text(),/共 41 条/);assert.match(h.text(),/模型原文已保存/);
  assert.ok(h.all("a").some(item=>item.href==="#/users/"+USER));
  assert.ok(h.all("a").some(item=>item.href.includes("/model-calls/"+REQUEST)&&item.href.includes("kind=GRADING")));
});

test("detail shows score zero separately from missing scores and uses readable input/output cards",async t=>{
  const h=harness(async()=>detail(),{hash:detailHash()});t.after(h.dispose);await h.login();
  assert.ok(h.all("td").some(item=>item.textContent==="0"));assert.ok(h.all("td").some(item=>item.textContent==="未取得评分"));
  for(const text of ["已通过服务端校验","学生答题内容","答题依据","第一项评分规则","标准答案正文","完整评分规则正文","实际系统提示词","实际用户提示词","仅保存元数据"])assert.ok(h.text().includes(text),text);
  assert.equal(h.all("img").length,0);assert.equal(h.all("script").length,0);assert.equal(h.all("pre").length,0);
  assert.ok(h.all("details").every(item=>!item.open));assert.equal(h.ids.get("page-content").attributes["aria-busy"],undefined);
});

test("legacy failure explicitly lacks input, keeps raw failure text safe, and never invents zero or completion time",async t=>{
  const h=harness(async()=>detail({call:summary({state:"FAILED",errorCode:"MODEL_FAILED",completedAt:null,chargedCredits:0}),request:null,response:null,requestSnapshotStatus:"NOT_RECORDED_LEGACY",modelOutput:"模型无法处理 <img src=x onerror=alert(1)>"}),{hash:detailHash()});t.after(h.dispose);await h.login();
  for(const text of ["调用失败","MODEL_FAILED","旧记录未保存输入快照","未保存实际提示词","尚无完成时间","0 次","模型无法处理 <img"])assert.ok(h.text().includes(text),text);
  assert.ok(!h.text().includes("已通过服务端校验"));assert.ok(!h.text().includes("1970"));assert.equal(h.all("img").length,0);
});

test("rubric generation shows reference answer, score step and generated criterion rules",async t=>{
  const response={scoreStep:0.5,criteria:[{id:"criterion1",rule:"判断是否包含关键词",allowedScores:[0,0.5,1]}]};
  const h=harness(async()=>detail({call:summary({kind:"RUBRIC_GENERATION"}),request:{generation:{subject:"数学",referenceAnswer:"参考答案",maxScore:1},providerRequest:{messages:[]}},response,modelOutput:JSON.stringify(response)}),{hash:detailHash().replace("kind=GRADING","kind=RUBRIC_GENERATION")});t.after(h.dispose);await h.login();
  for(const text of ["生成评分规则的输入","数学","参考答案","判断是否包含关键词","0、0.5、1","分值步长"])assert.ok(h.text().includes(text),text);
  assert.ok(!h.text().includes("超出精确显示范围"));
});

test("account filtering and paging preserve filters and reset the page on search",async t=>{
  const h=harness(async url=>page({page:Number(url.searchParams.get("page"))}));t.after(h.dispose);await h.login();
  const form=h.all("form")[0],inputs=descendants(form).filter(item=>item.tagName==="INPUT");inputs[0].value="teacher_one";inputs[1].value=USER;
  await form.fire("submit");await settle();
  let url=new URL(h.requests.at(-1).url,"http://localhost");assert.equal(url.searchParams.get("username"),"teacher_one");assert.equal(url.searchParams.get("userId"),USER);assert.equal(url.searchParams.get("page"),"1");
  await h.all("button").find(item=>item.textContent==="下一页").fire("click");await settle();
  url=new URL(h.requests.at(-1).url,"http://localhost");assert.equal(url.searchParams.get("page"),"2");assert.equal(url.searchParams.get("username"),"teacher_one");
  await h.all("button").find(item=>item.textContent==="清除").fire("click");await settle();url=new URL(h.requests.at(-1).url,"http://localhost");assert.equal(url.searchParams.get("userId"),null);assert.equal(url.searchParams.get("username"),null);assert.equal(url.searchParams.get("page"),"1");
});

test("empty list keeps useful filters and disables both page directions",async t=>{
  const h=harness(async()=>page({calls:[],total:0,totalPages:0}));t.after(h.dispose);await h.login();
  assert.match(h.text(),/暂无模型调用记录/);assert.equal(h.all("form").length,1);
  for(const label of ["上一页","下一页"])assert.ok(h.all("button").find(item=>item.textContent===label).disabled);
});

test("navigation aborts the previous list and ignores a late response even if fetch ignores abort",async t=>{
  let resolveList;
  const h=harness(async url=>url.pathname.endsWith(REQUEST)?detail():new Promise(resolve=>{resolveList=resolve;}));t.after(h.dispose);
  const loggingIn=h.login();await until(()=>!!resolveList);
  const oldRequest=h.requests.find(item=>item.url.startsWith("/v1/admin/model-calls?"));
  await h.go(detailHash());assert.ok(oldRequest.options.signal.aborted);assert.match(h.text(),/调用概况/);
  resolveList(page({calls:[summary({username:"stale_account"})]}));await loggingIn;assert.match(h.text(),/调用概况/);assert.ok(!h.text().includes("stale_account"));
});

test("mismatched account detail is rejected before any returned private content is rendered",async t=>{
  const h=harness(async()=>detail({call:summary({userId:"cccccccc-cccc-cccc-cccc-cccccccccccc",username:"other_account"})}),{hash:detailHash()});t.after(h.dispose);await h.login();
  assert.match(h.text(),/调用详情与所选记录不一致/);assert.ok(!h.text().includes("other_account"));assert.ok(!h.text().includes("标准答案正文"));
});

test("raw JSON is generated on expansion and can be explicitly copied",async t=>{
  const saved=detail(),h=harness(async()=>saved,{hash:detailHash()});t.after(h.dispose);await h.login();
  assert.equal(h.all("pre").length,0);const panel=h.all("details").find(item=>item.textContent==="展开完整记录 JSON");panel.open=true;await panel.fire("toggle");
  assert.equal(h.all("pre").length,1);await descendants(panel).find(item=>item.tagName==="BUTTON").fire("click");
  assert.equal(JSON.parse(h.copied[0]).call.requestId,REQUEST);assert.match(h.ids.get("notice").textContent,/已复制/);
});

test("detail response budget permits saved input plus output above the normal API cap",async t=>{
  const saved=detail(),padding="x".repeat(560000);saved.request.providerRequest.messages[0].content[0].text=padding;saved.modelOutput=JSON.stringify({status:"UNABLE",criteria:[],studentText:padding});
  const h=harness(async()=>saved,{hash:detailHash()});t.after(h.dispose);await h.login();assert.match(h.text(),/调用概况/);assert.ok(!h.text().includes("服务响应超出限制"));
  const list=harness(async()=>({...page(),extra:"x".repeat(1048577)}));t.after(list.dispose);await list.login();assert.match(list.text(),/服务响应超出限制/);
});
