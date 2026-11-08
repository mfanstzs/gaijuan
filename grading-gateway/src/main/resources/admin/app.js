(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const titles = {dashboard:"经营概览",users:"用户管理",models:"模型设置","model-calls":"模型调用记录",audit:"操作审计",orders:"订单",packages:"次数套餐"};
  const labels = {ADMIN:"管理员",USER:"普通用户",ADMIN_ADJUSTMENT:"管理员调次",MODEL_DISPATCH:"模型调用",RUBRIC_GENERATION:"生成评分规则",CREDITS:"调整次数",MODEL_POLICY:"模型切换",RUBRIC_POLICY:"规则模型切换",PACKAGE:"套餐变更",CREATE:"创建",UPDATE:"修改",DELETE:"删除"};
  const messages = {
    AUTH_REQUIRED:"登录已过期，请重新登录。",INVALID_CREDENTIALS:"用户名或密码不正确。",
    FORBIDDEN:"此账号没有这项操作的权限。",NOT_FOUND:"记录不存在，请刷新列表。",
    RATE_LIMITED:"操作过于频繁，请稍后再试。",INVALID_REQUEST:"输入不符合要求，请检查后重试。",
    INSUFFICIENT_CREDITS:"减少次数后余额不能为负数。",IDEMPOTENCY_CONFLICT:"这笔操作与先前记录不一致，请核对操作审计。",
    MODEL_POLICY_CHANGED:"模型设置已被更新。请刷新后重新选择。",
    MODEL_UNAVAILABLE:"此模型尚未验证，或服务端当前不可用。",
    STORE_UNAVAILABLE:"记录暂时无法访问。写入操作尚未确认，请保留原操作并核对记录。",
    INTERNAL_ERROR:"服务暂时不可用。写入操作尚未确认，请稍后核对记录。"
  };
  let session = null, authEpoch = 0, expiryTimer = null, pageEpoch = 0, pageAbort = null;
  let pendingCredit = null, pendingPolicy = null, pendingRubricPolicy = null, mutating = false;
  class Stale extends Error {}
  function node(tag, text, css) {
    const item = document.createElement(tag);
    if (text !== undefined && text !== null) item.textContent = String(text);
    if (css) item.className = css;
    return item;
  }
  function button(text, action, css) {
    const item = node("button", text, css); item.type = "button";
    if (action) item.addEventListener("click", action);
    return item;
  }
  function link(text, href, css) { const item = node("a",text,css); item.href=href; return item; }
  function badge(text, css) { return node("span",text,"badge " + (css || "")); }
  function number(value) { return value == null ? "—" : Number.isSafeInteger(Number(value)) ? Number(value).toLocaleString("zh-CN") : "超出精确显示范围"; }
  function date(value) {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? "—" : parsed.toLocaleString("zh-CN",{hour12:false});
  }
  function time(value) { const item=node("time",date(value)); item.dateTime=value||""; return item; }
  function notice(text, style="") {
    $("notice").textContent=text; $("notice").className="notice " + style; $("notice").hidden=!text;
  }
  function loginMessage(text) { $("login-message").textContent=text; $("login-message").hidden=!text; }
  function endSession(message) {
    const unconfirmed=!!pendingCredit||!!pendingPolicy||!!pendingRubricPolicy;
    pendingCredit=null;pendingPolicy=null;pendingRubricPolicy=null;
    session=null; authEpoch++; clearTimeout(expiryTimer); expiryTimer=null;
    if(pageAbort) pageAbort.abort(); pageEpoch++;
    $("shell").hidden=true; $("login-screen").hidden=false; $("page-content").replaceChildren();
    $("account-name").textContent=""; $("password").value=""; notice("");
    loginMessage((message || "")+(unconfirmed?" 若有尚未确认的操作，请重新登录后先核对操作审计。":"")); $("login-button").disabled=false;
  }
  async function api(path, {method="GET",body,authenticated=true,signal,maxResponseBytes=1048576}={}) {
    const token=authenticated ? session?.token : null, epoch=authEpoch;
    if(authenticated && !token) throw new Stale();
    const controller=new AbortController(), abort=()=>controller.abort();
    const timer=setTimeout(abort,15000);
    if(signal) { if(signal.aborted) abort(); else signal.addEventListener("abort",abort,{once:true}); }
    try {
      const headers={Accept:"application/json"}; if(token) headers.Authorization="Bearer " + token;
      if(body!==undefined) headers["Content-Type"]="application/json";
      const response=await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),
        credentials:"omit",redirect:"error",cache:"no-store",signal:controller.signal});
      let data=null;
      if(response.status!==204) {
        const reader=response.body.getReader(), chunks=[]; let length=0;
        try {
          while(true) { const part=await reader.read(); if(part.done) break; length+=part.value.length;
            if(length>maxResponseBytes) { controller.abort(); throw new Error("服务响应超出限制，操作尚未确认。"); }
            chunks.push(part.value);
          }
        } finally { reader.releaseLock(); }
        const bytes=new Uint8Array(length); let offset=0;
        for(const chunk of chunks) { bytes.set(chunk,offset); offset+=chunk.length; }
        data=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes));
      }
      if(epoch!==authEpoch || (authenticated && token!==session?.token)) throw new Stale();
      if(!response.ok) {
        const code=data?.error?.code;
        if(authenticated && response.status===401) { endSession(messages.AUTH_REQUIRED); throw new Stale(); }
        const failure=new Error(messages[code] || "请求未完成，请稍后重试并核对记录。");
        failure.code=code;
        failure.rejected=["INVALID_REQUEST","INSUFFICIENT_CREDITS","FORBIDDEN","AUTH_REQUIRED","MODEL_POLICY_CHANGED","MODEL_UNAVAILABLE","RATE_LIMITED","NOT_FOUND"].includes(code);
        throw failure;
      }
      return data;
    } catch(failure) {
      if(signal?.aborted || epoch!==authEpoch || failure instanceof Stale) throw new Stale();
      if(failure.name==="AbortError" || failure instanceof TypeError || failure instanceof SyntaxError)
        throw new Error("网络响应尚未确认。请重试读取；写入操作请保留原内容重查，不要重复创建。");
      throw failure;
    } finally { clearTimeout(timer); signal?.removeEventListener("abort",abort); }
  }
  function route() {
    const raw=location.hash.replace(/^#/,"") || "/dashboard", mark=raw.indexOf("?");
    const path=mark<0?raw:raw.substring(0,mark), query=new URLSearchParams(mark<0?"":raw.substring(mark+1));
    const parts=path.split("/").filter(Boolean), section=titles[parts[0]]?parts[0]:"users";
    const id=parts[1] && /^[0-9a-f-]{36}$/i.test(parts[1]) ? parts[1] : null;
    return {section,id,path:["users","model-calls"].includes(section)&&id?"/"+section+"/"+id:"/"+section,query};
  }
  function navigate(path, values={}) {
    const query=new URLSearchParams();
    for(const [key,value] of Object.entries(values)) if(value!==null&&value!==undefined&&value!=="") query.set(key,String(value));
    const hash="#" + path + (query.size ? "?" + query : "");
    if(location.hash===hash) void render(); else location.hash=hash;
  }
  function pagingQuery(r) {
    return {page:r.query.get("page")||"1",pageSize:r.query.get("pageSize")||"20"};
  }
  function params(values) { return new URLSearchParams(values).toString(); }
  function pageLink(r, changes) {
    const values=Object.fromEntries(r.query); navigate(r.path,{...values,...changes});
  }
  function header(description, refresh=true) {
    const area=node("div",undefined,"page-heading"); area.append(node("p",description));
    if(refresh) area.append(button("刷新",()=>void render(),"quiet"));
    return area;
  }
  function card(title) {
    const item=node("section",undefined,"card");
    if(title) { const head=node("div",undefined,"card-head"); head.append(node("h2",title)); item.append(head); }
    return item;
  }
  function empty(title,description) {
    const item=node("div",undefined,"empty"); item.append(node("strong",title),node("p",description||"")); return item;
  }
  function table(headers, rows, emptyText="暂无记录", emptyDetail="记录产生后会显示在这里。") {
    const wrap=node("div",undefined,"table-wrap");
    if(!rows.length) { wrap.append(empty(emptyText,emptyDetail)); return wrap; }
    const grid=node("table"), head=node("thead"), tr=node("tr"), body=node("tbody");
    for(const title of headers) tr.append(node("th",title)); head.append(tr); grid.append(head);
    for(const cells of rows) {
      const row=node("tr");
      for(const value of cells) {
        const cell=node("td"); if(value instanceof Node) cell.append(value); else cell.textContent=value==null?"—":String(value);
        row.append(cell);
      }
      body.append(row);
    }
    grid.append(body); wrap.append(grid); return wrap;
  }
  function entity(name,id,href) {
    const value=node("div"); value.append(href?link(name,href,"entity-name"):node("span",name,"entity-name"));
    if(id) value.append(node("span",id,"small-id")); return value;
  }
  function delta(value) { return node("span",(value>0?"+":"")+number(value),"number " + (value>0?"positive":"negative")); }
  function pager(data,r) {
    const area=node("div",undefined,"pagination"), left=node("span","共 "+number(data.total)+" 条");
    const controls=node("div",undefined,"page-buttons"), size=node("select"); size.setAttribute("aria-label","每页条数");
    for(const value of [20,50,100]) { const option=node("option",value+" 条/页"); option.value=value; option.selected=data.pageSize===value; size.append(option); }
    if(![20,50,100].includes(data.pageSize)) { const option=node("option",data.pageSize+" 条/页"); option.value=data.pageSize; option.selected=true; size.prepend(option); }
    size.addEventListener("change",()=>pageLink(r,{page:1,pageSize:size.value}));
    const previous=button("上一页",()=>pageLink(r,{page:data.page-1}));
    const next=button("下一页",()=>pageLink(r,{page:data.page+1}));
    previous.disabled=data.page<=1; next.disabled=data.totalPages===0||data.page>=data.totalPages;
    controls.append(size,previous,node("span",data.totalPages?data.page+" / "+data.totalPages:"0 / 0"),next);
    area.append(left,controls); return area;
  }
  function searchBar(r,label="搜索用户名") {
    const form=node("form",undefined,"toolbar"), field=node("label",label,"search-field"), input=node("input");
    input.type="search"; input.value=r.query.get("q")||""; input.placeholder="输入用户名搜索"; input.maxLength=32; input.pattern="[A-Za-z0-9_.\\-]*";
    field.append(input); const submit=button("搜索"); submit.type="submit";
    form.append(field,submit,button("清除",()=>navigate(r.path,{page:1,pageSize:r.query.get("pageSize")||20})));
    form.addEventListener("submit",event=>{event.preventDefault();pageLink(r,{q:input.value.trim(),page:1});});
    return form;
  }
  function filterBar(r,key,label,values) {
    const area=node("div",undefined,"toolbar"), field=node("label",label,"filter-field"), select=node("select");
    for(const [value,text] of values) { const option=node("option",text); option.value=value; option.selected=(r.query.get(key)||"ALL")===value; select.append(option); }
    select.addEventListener("change",()=>pageLink(r,{[key]:select.value,page:1})); field.append(select); area.append(field); return area;
  }
  function field(labelText,options={}) {
    const wrap=node("label",labelText), input=node(options.textarea?"textarea":"input");
    input.id=options.id||""; if(options.type) input.type=options.type;
    if(options.maxLength) input.maxLength=options.maxLength;
    if(options.required) input.required=true;
    if(options.readOnly) input.readOnly=true;
    if(options.value!==undefined) input.value=options.value;
    if(options.placeholder) input.placeholder=options.placeholder;
    wrap.append(input); return {wrap,input};
  }
  const svgNamespace="http://www.w3.org/2000/svg";
  function svgNode(tag,attributes={},textValue) {
    const item=document.createElementNS(svgNamespace,tag);
    for(const [name,value] of Object.entries(attributes)) item.setAttribute(name,String(value));
    if(textValue!==undefined) item.textContent=String(textValue);
    return item;
  }
  function chartNumber(value) {
    const parsed=Number(value);return Number.isFinite(parsed)&&parsed>=0?parsed:0;
  }
  function metricCard(title,value,detail,css="") {
    const item=node("section",undefined,"metric dashboard-metric "+css);
    item.append(node("span",title),node("strong",value),node("p",detail,"metric-detail"));return item;
  }
  function lineChart(days,series,label) {
    const shell=node("div",undefined,"chart-shell"),svg=svgNode("svg",{viewBox:"0 0 760 270",role:"img","aria-label":label});
    const left=48,right=22,top=25,bottom=42,width=760-left-right,height=270-top-bottom;
    const maximum=Math.max(1,...days.flatMap(day=>series.map(item=>chartNumber(day[item.key]))));
    for(let step=0;step<=4;step++) {
      const y=top+height*step/4,value=Math.round(maximum*(4-step)/4);
      svg.append(svgNode("line",{x1:left,y1:y,x2:760-right,y2:y,class:"chart-grid"}),svgNode("text",{x:left-8,y:y+4,class:"chart-axis","text-anchor":"end"},number(value)));
    }
    const x=index=>days.length===1?left+width/2:left+width*index/(days.length-1);
    const y=value=>top+height-(chartNumber(value)/maximum)*height;
    days.forEach((day,index)=>svg.append(svgNode("text",{x:x(index),y:255,class:"chart-axis","text-anchor":"middle"},String(day.date).slice(5))));
    for(const item of series) {
      const points=days.map((day,index)=>x(index)+","+y(day[item.key])).join(" ");
      svg.append(svgNode("polyline",{points,fill:"none",stroke:item.color,"stroke-width":3,"stroke-linejoin":"round","stroke-linecap":"round"}));
      days.forEach((day,index)=>{const point=svgNode("circle",{cx:x(index),cy:y(day[item.key]),r:3.5,fill:item.color});point.append(svgNode("title",{},day.date+" · "+item.name+" "+number(day[item.key])));svg.append(point);});
    }
    const legend=node("div",undefined,"chart-legend");
    for(const item of series) {const entry=node("span");entry.append(node("i",undefined,"legend-dot "+item.css),node("span",item.name));legend.append(entry);}
    shell.append(svg,legend);return shell;
  }
  function barChart(days,key,label,color) {
    const shell=node("div",undefined,"chart-shell"),svg=svgNode("svg",{viewBox:"0 0 760 270",role:"img","aria-label":label});
    const left=48,right=22,top=25,bottom=42,width=760-left-right,height=270-top-bottom;
    const maximum=Math.max(1,...days.map(day=>chartNumber(day[key]))),slot=width/Math.max(1,days.length),bar=Math.min(54,slot*.58);
    for(let step=0;step<=4;step++) {const y=top+height*step/4,value=Math.round(maximum*(4-step)/4);svg.append(svgNode("line",{x1:left,y1:y,x2:760-right,y2:y,class:"chart-grid"}),svgNode("text",{x:left-8,y:y+4,class:"chart-axis","text-anchor":"end"},number(value)));}
    days.forEach((day,index)=>{const value=chartNumber(day[key]),heightValue=value/maximum*height,x=left+slot*index+(slot-bar)/2,y=top+height-heightValue;
      const rect=svgNode("rect",{x,y,width:bar,height:Math.max(value?2:0,heightValue),rx:5,fill:color});rect.append(svgNode("title",{},day.date+" · "+number(value)));svg.append(rect,svgNode("text",{x:x+bar/2,y:255,class:"chart-axis","text-anchor":"middle"},String(day.date).slice(5)));});
    shell.append(svg);return shell;
  }
  async function dashboardPage(r,signal) {
    const data=await api("/v1/admin/dashboard",{signal});
    if(data?.periodDays!==7||!Array.isArray(data.daily)||data.daily.length!==7||!data.summary||!Array.isArray(data.models)) throw new Error("经营数据格式不完整，请重新读取。");
    const summary=data.summary,terminal=chartNumber(summary.succeededCalls)+chartNumber(summary.failedCalls);
    const success=terminal?((chartNumber(summary.succeededCalls)/terminal)*100).toFixed(1)+"%":"—";
    const fragment=document.createDocumentFragment();
    fragment.append(header("近 7 天经营和已派发模型调用情况。统计直接来自账户、次数流水、订单与模型请求数据库，按 UTC 自然日汇总。"));
    const metrics=node("div",undefined,"metric-grid dashboard-metrics");
    metrics.append(metricCard("注册用户",number(summary.totalUsers),"仅统计普通用户"),metricCard("用户剩余次数",number(summary.availableCredits),"当前所有普通用户余额"),
      metricCard("近 7 天已派发调用",number(summary.modelCalls),"已扣次的阅卷与规则生成调用合计","blue"),metricCard("终态成功率",success,"成功 ÷（成功 + 失败）","green"),
      metricCard("近 7 天实际入账次数",number(summary.creditsAdded),"来自已落库的正向次数流水","gold"),metricCard("近 7 天订单",number(summary.orders),"当前订单仍为待支付状态"));
    fragment.append(metrics);
    const charts=node("div",undefined,"dashboard-charts"),calls=card("模型调用趋势"),registrations=card("用户注册趋势"),credits=card("次数入账趋势");
    calls.append(node("div","仅统计已经越过派发边界并扣次的模型请求；准备中和明确未派发的请求不冒充调用。","chart-note"),lineChart(data.daily,[{key:"modelCalls",name:"已派发",color:"#245dd4",css:"call-total"},{key:"succeededCalls",name:"成功",color:"#19715a",css:"call-success"}],"近七天已派发模型调用与成功调用趋势"));
    registrations.append(node("div","每日新增普通用户，不包含管理员账户。","chart-note"),barChart(data.daily,"registrations","近七天用户注册趋势","#6e8ed8"));
    credits.append(node("div","只统计已经写入用户次数流水的正向变动，不把待支付订单当作收入。","chart-note"),barChart(data.daily,"creditsAdded","近七天实际入账次数趋势","#d9982f"));
    charts.append(calls,registrations,credits);fragment.append(charts);
    const facts=node("div",undefined,"two-column"),modelCard=card("近 7 天模型调用分布"),quality=card("数据状态");
    modelCard.append(table(["模型","调用","成功","失败","未决","实际扣次"],data.models.map(model=>[entity(model.modelId),number(model.calls),number(model.succeededCalls),number(model.failedCalls),number(model.unresolvedCalls),number(model.chargedCredits)]),"暂无模型调用","产生真实阅卷或规则生成请求后，会按模型汇总显示。"));
    const qualityBody=node("div",undefined,"card-body dashboard-facts");
    for(const [name,value,detail] of [["成功调用",summary.succeededCalls,"已保存成功终态"],["失败调用",summary.failedCalls,"已派发后的失败或结果未知"],["未决调用",summary.unresolvedCalls,"已派发、尚未得到终态"],["模型实际扣次",summary.chargedCredits,"以请求 charged 字段汇总"],["待支付订单金额",summary.orderAmountMinor==null?"—":"¥"+(chartNumber(summary.orderAmountMinor)/100).toFixed(2),summary.unpricedOrders?"存在 "+number(summary.unpricedOrders)+" 笔未定价订单，金额不作不完整汇总":"全部订单均有明确价格；仍不计入已收收入"],["未定价订单",summary.unpricedOrders,"未定价金额不按 0 元处理"]]) {
      const row=node("div",undefined,"fact-row");row.append(node("span",name),node("strong",typeof value==="number"?number(value):value),node("small",detail));qualityBody.append(row);
    }
    quality.append(qualityBody);facts.append(modelCard,quality);fragment.append(facts,node("p","数据生成时间："+date(data.generatedAt)+"（接口时区 "+data.timezone+"）","dashboard-generated"));return fragment;
  }
  async function usersPage(r,signal) {
    const data=await api("/v1/admin/users?"+params({...pagingQuery(r),q:r.query.get("q")||""}),{signal});
    const fragment=document.createDocumentFragment(), list=card("用户列表");
    fragment.append(header("查询用户账户，查看余额、调整次数与调用流水。"));
    list.append(searchBar(r),table(["用户","角色","剩余次数","注册时间","操作"],data.users.map(user=>[
      entity(user.username,user.id,"#/users/"+user.id),badge(labels[user.role]||user.role,user.role==="ADMIN"?"blue":""),
      node("span",number(user.credits),"number"),time(user.createdAt),link("查看详情","#/users/"+user.id)
    ]),"没有找到用户",r.query.get("q")?"换一个用户名，或清除搜索条件。":"新注册的用户将显示在这里。"),pager(data,r));
    fragment.append(list); return fragment;
  }
  function creditForm(user) {
    const section=card("调整调用次数"), form=node("form",undefined,"card-body");
    const prior=pendingCredit?.targetUserId===user.id?pendingCredit:null;
    form.append(node("p","对当前用户增加或减少调用次数。","muted"));
    const count=field("调整次数",{id:"credit-delta",type:"number",required:true,value:prior?.delta??"",placeholder:"正数增加，负数减少"});
    count.input.step="1"; count.input.min="-1000000000"; count.input.max="1000000000";
    const reason=field("调整原因",{id:"credit-reason",textarea:true,required:true,maxLength:500,value:prior?.reason||"",placeholder:"填写可供核对的具体原因"});
    const hint=node("div",prior?"上次操作尚未确认。原内容已保留，点击重查会沿用同一笔操作。":"调整后余额不能为负数。每笔调整都会记录管理员和原因。","hint");
    const submit=button(prior?"重查本次调整":"确认调整",null,"primary"); submit.type="submit";
    form.append(count.wrap,reason.wrap,hint,submit);
    form.addEventListener("submit",async event=>{
      event.preventDefault(); if(mutating) return;
      const amount=Number(count.input.value), why=reason.input.value.trim();
      if(!Number.isSafeInteger(amount)||amount===0||Math.abs(amount)>1000000000||!why) { notice("请填写非零整数次数和调整原因。","error");return; }
      if(pendingCredit&&(pendingCredit.targetUserId!==user.id||pendingCredit.delta!==amount||pendingCredit.reason!==why)) { notice("有一笔调整尚未确认，请先返回原用户详情重查该笔操作。","warn");return; }
      pendingCredit??={requestId:crypto.randomUUID(),targetUserId:user.id,delta:amount,reason:why};
      const epoch=authEpoch; mutating=true; submit.disabled=true; submit.textContent="正在确认…";
      try {
        const result=await api("/v1/admin/credits",{method:"POST",body:pendingCredit});
        if(epoch!==authEpoch) return;
        pendingCredit=null; notice("调整已确认。"+user.username+" 当前剩余 "+number(result.account.credits)+" 次。");
        await render();
      } catch(failure) {
        if(failure instanceof Stale) return;
        if(failure.rejected) pendingCredit=null;
        notice(failure.message,pendingCredit?"warn":"error");
        hint.textContent=pendingCredit?"本次操作尚未确认。请保留原内容点击重查，或前往操作审计核对。":"请核对输入后重新提交。";
      } finally { mutating=false; submit.disabled=false; submit.textContent=pendingCredit?"重查本次调整":"确认调整"; }
    });
    section.append(form); return section;
  }
  async function userPage(r,signal) {
    const [user,ledger]=await Promise.all([
      api("/v1/admin/users/"+r.id,{signal}),
      api("/v1/admin/ledger?"+params({...pagingQuery(r),userId:r.id,kind:r.query.get("kind")||"ALL"}),{signal})
    ]);
    const fragment=document.createDocumentFragment(); fragment.append(link("← 返回用户列表","#/users","back-link"),header("用户详情 · "+user.username));
    const metrics=node("div",undefined,"metric-grid");
    for(const [title,value,css] of [["剩余调用次数",number(user.credits),""],["用户角色",labels[user.role]||user.role,"word"],["注册时间",date(user.createdAt),"word"]]) {
      const metric=node("div",undefined,"metric"); metric.append(node("span",title),node("strong",value,css)); metrics.append(metric);
    }
    fragment.append(metrics,link("查看该用户的模型调用记录","#/model-calls?"+params({userId:user.id}),"back-link"));
    const columns=node("div",undefined,"two-column"), list=card("调用次数流水");
    list.append(filterBar(r,"kind","流水类型",[["ALL","全部流水"],["ADMIN_ADJUSTMENT","管理员调次"],["MODEL_DISPATCH","阅卷模型调用"],["RUBRIC_GENERATION","生成评分规则"]]),
      table(["时间","类型","变动","变动后余额","原因"],ledger.entries.map(entry=>[
        time(entry.createdAt),badge(labels[entry.kind]||entry.kind),delta(entry.delta),number(entry.balanceAfter),
        ["MODEL_DISPATCH","RUBRIC_GENERATION"].includes(entry.kind)?"模型请求已发起":entry.reason
      ]),"暂无次数流水","调次或发起模型调用后，记录会显示在这里。"),pager(ledger,r));
    columns.append(list,creditForm(user)); fragment.append(columns); return fragment;
  }
  async function ordersPage(r,signal) {
    const data=await api("/v1/admin/orders?"+params({...pagingQuery(r),q:r.query.get("q")||""}),{signal});
    const fragment=document.createDocumentFragment(), list=card("订单记录");
    fragment.append(header("查看用户创建的次数套餐订单。当前支付未接入，待支付订单不会增加用户余额。"));
    list.append(searchBar(r),table(["订单 / 用户","套餐","次数","金额","状态","创建时间"],data.orders.map(order=>[
      entity(order.username,order.id,"#/users/"+order.userId),order.packageName,number(order.credits),
      order.priceMinor==null?"未定价":"¥"+(order.priceMinor/100).toFixed(2),badge("待支付","warn"),time(order.createdAt)
    ]),"暂无订单",r.query.get("q")?"该用户名没有匹配的订单。":"用户创建套餐订单后会显示在这里。"),pager(data,r));
    fragment.append(list); return fragment;
  }
  const callKinds={GRADING:"阅卷评分",RUBRIC_GENERATION:"生成评分规则"};
  const callStates={PREPARED:"准备派发",DISPATCHING:"派发中",SUCCEEDED:"已完成",FAILED:"调用失败",NOT_DISPATCHED:"未派发",UNKNOWN:"结果未知"};
  function callState(call) {
    return badge(callStates[call.state]||call.state,call.state==="SUCCEEDED"?"good":["FAILED","UNKNOWN"].includes(call.state)?"danger":"warn");
  }
  function score(value) { return value===null||value===undefined||value===""?"未取得评分":String(value); }
  function callTime(value) { return value?time(value):node("span","—","muted"); }
  function longText(value,placeholder="未保存") {
    return node("p",value===null||value===undefined||value===""?placeholder:String(value),"record-text");
  }
  function textBlock(title,value,placeholder) {
    const block=node("div",undefined,"record-block"); block.append(node("h3",title),longText(value,placeholder)); return block;
  }
  function callFilters(r) {
    const form=node("form",undefined,"toolbar call-filters");
    const account=field("账号（精确匹配）",{type:"search",maxLength:32,value:r.query.get("username")||"",placeholder:"输入完整账号"});
    const user=field("用户 ID（精确匹配）",{maxLength:36,value:r.query.get("userId")||"",placeholder:"可从用户详情直接进入"});
    user.input.pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    account.wrap.className="search-field";user.wrap.className="search-field";
    const submit=button("筛选",null,"primary");submit.type="submit";
    form.append(account.wrap,user.wrap,submit,button("清除",()=>navigate("/model-calls",{page:1,pageSize:r.query.get("pageSize")||20})),node("p","同时填写账号与用户 ID 时，须同时匹配。","filter-help"));
    form.addEventListener("submit",event=>{event.preventDefault();pageLink(r,{username:account.input.value.trim(),userId:user.input.value.trim(),page:1});});
    return form;
  }
  function callDetailHref(call,r) {
    return "#/model-calls/"+encodeURIComponent(call.requestId)+"?"+params({kind:call.kind,userId:call.userId,
      listPage:r.query.get("page")||1,listSize:r.query.get("pageSize")||20,
      listUserId:r.query.get("userId")||"",listUsername:r.query.get("username")||""});
  }
  function callListHref(r) {
    const query=new URLSearchParams({page:r.query.get("listPage")||1,pageSize:r.query.get("listSize")||20});
    for(const [source,target] of [["listUserId","userId"],["listUsername","username"]]) if(r.query.get(source)) query.set(target,r.query.get(source));
    return "#/model-calls?"+query;
  }
  async function modelCallsPage(r,signal) {
    const query={...pagingQuery(r)};
    for(const key of ["userId","username"]) if(r.query.get(key)) query[key]=r.query.get(key);
    const data=await api("/v1/admin/model-calls?"+params(query),{signal});
    if(!Array.isArray(data.calls)) throw new Error("调用记录格式不完整，请重新读取。");
    const fragment=document.createDocumentFragment(),list=card("全部模型调用");
    fragment.append(header("按账号核对阅卷与评分规则生成请求。扣次以每条记录为准，已派发的失败请求也会扣次。"));
    list.append(callFilters(r),table(["时间","用户","调用用途","模型","状态","扣次","记录内容","操作"],data.calls.map(call=>{
      const saved=node("div",undefined,"call-snapshot");
      saved.append(node("span",call.requestSnapshotAvailable?"调用快照已保存":"未保存调用快照",call.requestSnapshotAvailable?"":"muted"),node("span",call.responseAvailable?"结构化返回已保存":call.modelOutputAvailable?"模型原文已保存":"暂无返回","muted"));
      return [callTime(call.createdAt),entity(call.username,call.userId,"#/users/"+encodeURIComponent(call.userId)),
        callKinds[call.kind]||call.kind,node("span",call.modelId||"尚未分配","call-model"),callState(call),
        node("span",number(call.chargedCredits)+" 次","number"),saved,link("查看详情",callDetailHref(call,r))];
    }),"暂无模型调用记录",query.userId||query.username?"没有匹配的调用，可核对完整账号或清除筛选。":"用户发起阅卷或生成评分规则后，记录会显示在这里。"),pager(data,r));
    fragment.append(list);return fragment;
  }
  function disclosure(title,value,json=false) {
    const details=node("details",undefined,"record-disclosure"),summary=node("summary",title);details.append(summary);
    let loaded=false;
    details.addEventListener("toggle",()=>{
      if(!details.open||loaded) return; loaded=true;
      const text=json?JSON.stringify(value,null,2):String(value),body=node("div",undefined,"record-disclosure-body"),pre=node("pre",text,"record-raw");
      const copy=button("复制",async()=>{
        const epoch=pageEpoch;
        try {
          if(!navigator.clipboard?.writeText) throw new Error();
          await navigator.clipboard.writeText(text);
          if(epoch===pageEpoch&&session) notice("已复制。");
        } catch { if(epoch===pageEpoch&&session) notice("浏览器未允许复制，请选中展开的文本后手动复制。","warn"); }
      },"quiet");
      body.append(copy,pre);details.append(body);
    });
    return details;
  }
  function ruleTable(criteria) {
    return table(["评分点","评分规则","允许分值"],criteria.map(item=>[
      item.id||item.criterionId,longText(item.rule??item.description,"未提供规则"),
      Array.isArray(item.allowedScores)?item.allowedScores.map(score).join("、"):"未保存"
    ]),"未保存评分点","此记录没有可读的评分点快照。");
  }
  function inputCard(detail) {
    const request=detail.request,section=card(detail.call.kind==="RUBRIC_GENERATION"?"生成评分规则的输入":"题目与评分规则");
    if(!request) {section.append(empty("此旧记录未保存输入快照","无法还原当时的标准答案、评分规则和实际提示词；不会使用当前规则代替。"));return section;}
    const body=node("div",undefined,"card-body"),q=request.question||{},rubric=request.rubric||{},generation=request.generation||{};
    if(detail.call.kind==="RUBRIC_GENERATION") {
      body.append(textBlock("学科",generation.subject),textBlock("标准答案",generation.referenceAnswer),textBlock("满分",generation.maxScore));
    } else {
      body.append(textBlock("题号",q.id??rubric.questionId),textBlock("题目原文",q.prompt??rubric.sourceText),
        textBlock("标准答案",q.standardAnswer??rubric.referenceAnswer),textBlock("满分",q.maxScore??rubric.maxScore),
        textBlock("原始评分规则",rubric.rawRubric,"未保存原始规则文本，请核对下方评分点。"));
      if(rubric.scoreStep!==undefined) body.append(textBlock("分值步长",rubric.scoreStep));
    }
    section.append(body);
    const criteria=q.criteria??rubric.criteria;
    if(Array.isArray(criteria)) section.append(ruleTable(criteria));
    return section;
  }
  function promptCard(request) {
    const section=card("本次模型请求的实际提示词"),provider=request?.providerRequest;
    if(!Array.isArray(provider?.messages)) {section.append(empty("未保存实际提示词","旧记录无法事后还原该次请求的实际输入文本。"));return section;}
    const body=node("div",undefined,"card-body");
    const settings=node("div",undefined,"request-settings");
    for(const [name,value] of [["模型",provider.model],["返回格式",provider.response_format?.type],["温度",provider.temperature],
      ["输出上限",provider.max_completion_tokens??provider.max_tokens],["思考开关",provider.enable_thinking],["推理强度",provider.reasoning_effort]]) {
      if(value!==null&&value!==undefined) settings.append(textBlock(name,value));
    }
    body.append(settings);
    const roles={system:"系统提示",developer:"开发者提示",user:"发送给 AI 的评分规则与答卷图片",assistant:"助手上下文",tool:"工具结果"};
    for(const [index,message] of provider.messages.entries()) {
      const block=node("section",undefined,"prompt-message");block.append(node("h3",(index+1)+". "+(roles[message.role]||"消息（"+String(message.role)+"）")));
      if(typeof message.content==="string") block.append(longText(message.content,"空文本"));
      else if(Array.isArray(message.content)) for(const part of message.content) {
        if(part.type==="text") block.append(longText(part.text,"空文本"));
        else if(part.type==="image") {
          const metadata=node("div",undefined,"image-metadata");metadata.append(node("p","单题图片 · "+number(part.bytes)+" 字节；仅保存元数据。"),node("span","SHA-256："+(part.sha256||"未保存"),"small-id"));block.append(metadata);
        } else block.append(node("p","此消息部分没有可显示的文本。","muted"));
      } else block.append(node("p","此消息未保存文本。","muted"));
      body.append(block);
    }
    section.append(body);return section;
  }
  function gradeResultCard(result,request,validated) {
    const section=card(validated?"评分结果 · 已通过服务端校验":"模型返回的评分内容 · 未通过服务端确认"),body=node("div",undefined,"card-body");
    const status={SCORED:"已给出评分",UNABLE:"无法可靠评分"},readability={CLEAR:"清晰",PARTIAL:"部分可读",UNREADABLE:"无法辨认"};
    body.append(node("p",(status[result.status]||"未知评分状态")+" · 答卷可读性："+(readability[result.readability]||"未提供"),"muted"),
      textBlock("学生答题内容",result.studentText,"未返回学生答题内容"));
    if(Array.isArray(result.issues)&&result.issues.length) {
      const issues=node("ul",undefined,"record-issues");for(const issue of result.issues) issues.append(node("li",issue));body.append(node("h3","模型提示的问题"),issues);
    }
    section.append(body);
    const sourceCriteria=request?.question?.criteria??request?.rubric?.criteria??[];
    const descriptions=new Map(Array.isArray(sourceCriteria)?sourceCriteria.map(item=>[item.id,item.rule??item.description]):[]);
    const criteria=Array.isArray(result.criteria)?result.criteria:[];
    const grid=table(["评分点与规则","本项得分","依据内容","评分理由"],criteria.map(item=>[
      entity(descriptions.get(item.criterionId)||item.criterionId,item.criterionId),node("span",score(item.awarded),"number"),
      longText(item.evidence,"未提供依据"),longText(item.reason,"未提供理由")
    ]),"没有可用的评分点结果","未取得评分的项目不代表 0 分。");grid.classList.add("score-table");section.append(grid);
    return section;
  }
  function generatedRulesCard(result,validated) {
    const section=card(validated?"生成的评分规则 · 已通过服务端校验":"模型生成的规则 · 未通过服务端确认");
    const body=node("div",undefined,"card-body");body.append(textBlock("分值步长",result.scoreStep));section.append(body,ruleTable(result.criteria));return section;
  }
  function outputCards(detail) {
    const fragment=document.createDocumentFragment(),response=detail.response;
    let parsed=null,parsedJson=false;
    if(typeof detail.modelOutput==="string") {try {parsed=JSON.parse(detail.modelOutput);parsedJson=true;} catch {}}
    const validated=detail.call.state==="SUCCEEDED";
    const grade=response?.result??(parsed&&typeof parsed==="object"?parsed:null);
    const generated=Array.isArray(response?.criteria)?response:parsed;
    if(detail.call.kind==="GRADING"&&grade&&Array.isArray(grade.criteria)) fragment.append(gradeResultCard(grade,detail.request,validated&&!!response?.result));
    else if(detail.call.kind==="RUBRIC_GENERATION"&&Array.isArray(generated?.criteria)) fragment.append(generatedRulesCard(generated,validated&&!!response?.criteria));
    else {
      const result=card("调用结果"),body=node("div",undefined,"card-body");
      body.append(longText(detail.call.errorCode?"请求未取得可用结果。错误代码："+detail.call.errorCode:"此调用尚无可读的评分或规则结果。"));
      result.append(body);fragment.append(result);
    }
    const raw=card("供应商实际输出"),body=node("div",undefined,"card-body");
    if(detail.modelOutput===null||detail.modelOutput===undefined) body.append(node("p","此记录未保存供应商输出原文。结构化结果如已保存，仍可在上方查看。","muted"));
    else {
      body.append(node("p",parsedJson?"模型输出为 JSON，可展开核对供应商实际输出原文。":"供应商返回的内容未形成合法 JSON，以下按原文显示。","muted"));
      if(!parsedJson) body.append(longText(detail.modelOutput,"供应商返回了空文本。"));
      body.append(disclosure("查看并复制模型输出原文",detail.modelOutput));
    }
    raw.append(body);fragment.append(raw);return fragment;
  }
  function failureDiagnosticCard(detail) {
    if(!detail.call.errorCode&&!detail.failureDiagnostic) return null;
    const section=card("失败诊断"),body=node("div",undefined,"card-body"),failure=detail.failureDiagnostic;
    if(!failure) {
      body.append(node("p",detail.failureDiagnosticStatus==="INVALID_RECORDED_DATA"?
        "此记录的诊断数据不符合安全格式，已隐藏；不能据此判断具体原因。":
        "旧记录没有保存底层失败诊断，无法事后断定是超时、连接中断还是其他原因。","muted"));
    } else {
      const phases={BEFORE_DISPATCH:"派发前",AFTER_DISPATCH:"派发后"};
      const codes={MODEL_DNS_FAILURE:"供应商域名解析失败",MODEL_CONNECT_TIMEOUT:"连接供应商超时",MODEL_REQUEST_TIMEOUT:"等待供应商响应超时",
        MODEL_SOCKET_TIMEOUT:"网络读写超时",MODEL_TLS_HANDSHAKE_FAILED:"安全连接握手失败",MODEL_TLS_FAILURE:"安全连接失败",
        MODEL_NETWORK_UNREACHABLE:"供应商网络不可达",MODEL_CONNECTION_REFUSED:"供应商连接被拒绝",MODEL_CONNECT_FAILED:"无法建立供应商连接",
        MODEL_CONNECTION_RESET:"连接被重置",MODEL_CONNECTION_CLOSED:"连接提前关闭",MODEL_SOCKET_FAILURE:"网络连接异常",MODEL_IO_FAILURE:"网络传输异常",
        MODEL_CANCELLED:"模型请求被取消",MODEL_AUTHENTICATION:"供应商认证失败",MODEL_RATE_LIMIT:"供应商限流",MODEL_SERVER_ERROR:"供应商服务错误",
        MODEL_HTTP_ERROR:"供应商返回非成功状态",MODEL_INVALID_JSON:"模型返回格式不正确",MODEL_INVALID_RESULT:"模型返回内容未通过校验",
        MODEL_EMPTY_RESPONSE:"模型返回空内容",MODEL_TRUNCATED:"模型输出被截断",MODEL_RESPONSE_TOO_LARGE:"模型输出超过安全上限",
        MODEL_INTERNAL_ERROR:"模型调用模块内部异常",MODEL_UNVERIFIED_CONFIGURATION:"模型配置尚未验证",MODEL_MISSING_CREDENTIAL:"模型凭据不可用",
        MODEL_INVALID_IMAGE:"答卷图片未通过调用前校验",MODEL_TIMEOUT:"供应商请求超时",MODEL_NETWORK:"供应商连接异常",MODEL_REFUSED:"模型拒绝处理请求",
        GATEWAY_WAIT_TIMEOUT:"服务端等待模型结果超时",GATEWAY_INTERRUPTED:"服务端等待被中断",
        GATEWAY_RESULT_INVALID:"服务端未确认合法评分结果",GATEWAY_UNEXPECTED_ERROR:"服务端调用流程内部异常"};
      body.append(textBlock("失败阶段",phases[failure.phase]||"未知阶段"),textBlock("具体原因",codes[failure.diagnosticCode]||"请按安全诊断码定位"),
        textBlock("诊断码",failure.diagnosticCode),textBlock("失败类别",failure.failureKind));
      if(failure.upstreamStatus!==null&&failure.upstreamStatus!==undefined) body.append(textBlock("供应商 HTTP 状态",failure.upstreamStatus));
      body.append(node("p","仅记录安全分类与状态码，不记录密钥、服务地址或底层异常原文。","muted"));
    }
    if(detail.call.state==="UNKNOWN") body.append(node("p","派发结果未知，不代表供应商未收到请求；不会自动重试、自动补扣或把失败当作 0 分。","muted"));
    section.append(body);return section;
  }
  async function modelCallPage(r,signal) {
    const kind=r.query.get("kind"),userId=r.query.get("userId");
    if(!callKinds[kind]||!userId||!/^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i.test(userId))
      throw new Error("调用详情缺少有效的用户或用途，请从调用记录列表重新进入。");
    const detail=await api("/v1/admin/model-calls/"+encodeURIComponent(r.id)+"?"+params({kind,userId}),{signal,maxResponseBytes:8388608});
    const call=detail.call;
    if(detail.schemaVersion!==1||!call||String(call.requestId).toLowerCase()!==r.id.toLowerCase()||String(call.userId).toLowerCase()!==userId.toLowerCase()||call.kind!==kind) throw new Error("调用详情与所选记录不一致，请返回列表重新读取。");
    const fragment=document.createDocumentFragment();fragment.append(link("← 返回模型调用记录",callListHref(r),"back-link"),header("核对该次模型请求的输入、每项评分与返回内容。"));
    const overview=card("调用概况"),body=node("div",undefined,"card-body call-overview");
    body.append(entity(call.username,call.userId,"#/users/"+encodeURIComponent(call.userId)),textBlock("调用用途",callKinds[call.kind]),
      textBlock("模型",call.modelId,"尚未分配"),textBlock("扣除次数",number(call.chargedCredits)+" 次"));
    const state=node("div",undefined,"record-block");state.append(node("h3","调用状态"),callState(call));if(call.errorCode) state.append(longText(call.errorCode));body.append(state);
    body.append(textBlock("请求 ID",call.requestId),textBlock("开始时间",call.createdAt?date(call.createdAt):"未保存"),
      textBlock("完成时间",call.completedAt?date(call.completedAt):"尚无完成时间"),textBlock("配置 ID",call.profileId),textBlock("策略版本",call.policyRevision));
    const usage=responseUsage(detail.response);if(usage) body.append(usage);
    overview.append(body);fragment.append(overview);
    const diagnostic=failureDiagnosticCard(detail);if(diagnostic) fragment.append(diagnostic);
    fragment.append(outputCards(detail),inputCard(detail),promptCard(detail.request));
    const advanced=card("高级查看"),advancedBody=node("div",undefined,"card-body");
    advancedBody.append(node("p","用于逐字段核对保存的 JSON；图片只保存哈希与字节数等元数据。","muted"),disclosure("展开完整记录 JSON",detail,true));
    advanced.append(advancedBody);fragment.append(advanced);return fragment;
  }
  function responseUsage(response) {
    if(!response?.usage) return null;
    const usage=response.usage,parts=[];
    for(const [label,value] of [["输入",usage.inputTokens],["输出",usage.outputTokens],["总计",usage.totalTokens]]) if(value!==null&&value!==undefined) parts.push(label+" "+number(value));
    return parts.length?textBlock("Token 用量",parts.join(" · ")):null;
  }
  async function auditPage(r,signal) {
    const data=await api("/v1/admin/audit?"+params({...pagingQuery(r),type:r.query.get("type")||"ALL"}),{signal});
    const fragment=document.createDocumentFragment(), list=card("管理员操作记录");
    fragment.append(header("核对管理员调次、模型切换和套餐变更。每笔操作保留版本、原因与时间。"));
    list.append(filterBar(r,"type","操作类型",[["ALL","全部操作"],["CREDITS","调整次数"],["MODEL_POLICY","阅卷模型切换"],["RUBRIC_POLICY","规则模型切换"],["PACKAGE","套餐变更"]]),
      table(["时间","管理员","操作","对象与变化","原因"],data.entries.map(entry=>{
        const detail=node("div");
        if(entry.kind==="CREDITS") detail.append(link(entry.targetUsername,"#/users/"+entry.targetUserId),node("span"," · "),delta(entry.delta),node("span"," 次，余额 "+number(entry.balanceAfter)));
        else if(entry.kind==="PACKAGE") detail.append(node("span","套餐 "+(entry.packageId||"—")+" · "+(labels[entry.reason]||entry.reason)),node("span","版本 "+(entry.previousRevision==null?"—":entry.previousRevision)+" → "+entry.revision,"small-id"));
        else detail.append(node("span",(entry.previousModelId||"尚未分配")+" → "+entry.modelId),node("span","策略版本 "+entry.previousRevision+" → "+entry.revision,"small-id"));
        detail.append(node("span",entry.id,"small-id"));
        return [time(entry.createdAt),entry.adminUsername,badge(labels[entry.kind]||entry.kind,["MODEL_POLICY","RUBRIC_POLICY","PACKAGE"].includes(entry.kind)?"blue":""),detail,entry.kind==="PACKAGE"?(labels[entry.reason]||entry.reason):entry.reason];
      }),"暂无操作审计","管理员调整次数、切换模型或变更套餐后会自动生成记录。"),pager(data,r));
    fragment.append(list); return fragment;
  }
  async function packagesPage(r,signal) {
    const data=await api("/v1/admin/packages",{signal});
    if(!Array.isArray(data.packages)) throw new Error("套餐目录格式不完整，请重新读取。");
    const editId=r.query.get("edit"),editing=editId?data.packages.find(pack=>pack.id===editId):null;
    if(editId&&!editing) throw new Error("所选套餐不存在或已删除，请返回套餐列表。");
    const fragment=document.createDocumentFragment(),heading=node("div",undefined,"page-heading"),headingText=node("div");
    headingText.append(node("p","配置用户可见的次数、价格与上架状态。所有修改持久化保存并写入管理员审计，重启后不会还原。"));
    const headingActions=node("div",undefined,"form-actions");headingActions.append(button("新增套餐",()=>navigate("/packages"),"primary"),button("刷新",()=>void render(),"quiet"));heading.append(headingText,headingActions);fragment.append(heading);
    const columns=node("div",undefined,"package-admin-layout"),list=card("套餐目录");
    list.append(table(["套餐","次数","价格","状态","版本","操作"],data.packages.map(pack=>{
      const actions=node("div",undefined,"row-actions"),edit=button("编辑",()=>navigate("/packages",{edit:pack.id}),"quiet"),remove=button("删除",async()=>{
        if(mutating||!confirm("确定删除套餐“"+pack.name+"”吗？历史订单仍会保留。")) return;
        mutating=true;remove.disabled=true;edit.disabled=true;
        try {await api("/v1/admin/packages/"+encodeURIComponent(pack.id),{method:"DELETE",body:{expectedRevision:pack.revision}});notice("套餐已删除。历史订单未受影响。");if(editId===pack.id) navigate("/packages");else await render();}
        catch(failure){if(!(failure instanceof Stale)) notice(failure.message,"error");}
        finally{mutating=false;remove.disabled=false;edit.disabled=false;}
      },"quiet danger-button");actions.append(edit,remove);
      return [entity(pack.name,pack.id),number(pack.credits),pack.priceMinor==null?"未定价":"¥"+(pack.priceMinor/100).toFixed(2),badge(pack.enabled?"已上架":"已下架",pack.enabled?"good":"warn"),"v"+number(pack.revision),actions];
    }),"暂无套餐","在右侧创建第一个次数套餐。"));
    const editor=card(editing?"编辑套餐":"新增套餐"),form=node("form",undefined,"card-body package-form");
    const id=field("套餐编号",{required:true,maxLength:64,value:editing?.id||"",readOnly:!!editing,placeholder:"例如 calls-200"});id.input.pattern="[a-z0-9][a-z0-9-]{0,63}";
    const name=field("套餐名称",{required:true,maxLength:80,value:editing?.name||"",placeholder:"例如 200 次"});
    const credits=field("包含次数",{required:true,type:"number",value:editing?.credits||"",placeholder:"正整数"});credits.input.min="1";credits.input.max="1000000000";credits.input.step="1";
    const price=field("价格（元）",{value:editing?.priceMinor==null?"":(editing.priceMinor/100).toFixed(2),placeholder:"留空表示未定价"});price.input.inputMode="decimal";price.input.pattern="[0-9]{1,9}(\\.[0-9]{1,2})?";
    const currency=field("币种",{value:"CNY",readOnly:true});
    const enabledWrap=node("label","上架状态"),enabled=node("select");for(const [value,text] of [["true","已上架（用户可创建订单）"],["false","已下架（禁止新订单）"]]) {const option=node("option",text);option.value=value;option.selected=(editing?.enabled??true)===(value==="true");enabled.append(option);}enabledWrap.append(enabled);
    const hint=node("div",editing?"当前版本 v"+editing.revision+"。若另一位管理员已修改，系统会拒绝覆盖并要求刷新。":"套餐编号创建后不可修改；删除采用保留历史引用的安全删除。","hint");
    const actions=node("div",undefined,"form-actions"),submit=button(editing?"保存修改":"创建套餐",null,"primary");submit.type="submit";actions.append(submit);if(editing) actions.append(button("取消编辑",()=>navigate("/packages"),"quiet"));
    form.append(id.wrap,name.wrap,credits.wrap,price.wrap,currency.wrap,enabledWrap,hint,actions);editor.append(form);
    form.addEventListener("submit",async event=>{
      event.preventDefault();if(mutating)return;
      const units=Number(credits.input.value),rawPrice=price.input.value.trim();let priceMinor=null;
      if(!Number.isSafeInteger(units)||units<1||units>1000000000||!id.input.checkValidity()||!name.input.value.trim()){notice("请检查套餐编号、名称和次数。","error");return;}
      if(rawPrice){const match=/^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(rawPrice);if(!match){notice("价格最多保留两位小数，或留空表示未定价。","error");return;}priceMinor=Number(match[1])*100+Number((match[2]||"").padEnd(2,"0"));}
      const payload={name:name.input.value.trim(),credits:units,priceMinor,currency:"CNY",enabled:enabled.value==="true"};
      const path=editing?"/v1/admin/packages/"+encodeURIComponent(editing.id):"/v1/admin/packages",method=editing?"PUT":"POST",body=editing?{expectedRevision:editing.revision,...payload}:{id:id.input.value,...payload};
      mutating=true;submit.disabled=true;submit.textContent="正在保存…";
      try {const saved=await api(path,{method,body});notice("套餐“"+saved.name+"”已保存。");navigate("/packages",{edit:saved.id});}
      catch(failure){if(!(failure instanceof Stale)) notice(failure.message,failure.rejected?"error":"warn");}
      finally{mutating=false;submit.disabled=false;submit.textContent=editing?"保存修改":"创建套餐";}
    });
    columns.append(list,editor);fragment.append(columns);return fragment;
  }
  async function modelsPage(r,signal) {
    const data=await api("/v1/admin/models",{signal}), fragment=document.createDocumentFragment(), columns=node("div",undefined,"two-column");
    fragment.append(header("分别设置阅卷模型和一键生成评分规则的模型。两项策略独立保存，互不影响正在处理的另一类请求。"));
    const list=card(), current=node("div",undefined,"current-model"), description=node("div");
    description.append(node("span","当前评分模型","eyebrow"),node("strong",data.policy.modelId||"尚未分配"),node("p","自动备用："+(data.policy.fallbackModelId||"无可用备用")+" · 策略版本 "+data.policy.revision));
    current.append(description,badge(data.policy.profileId?"已分配":"等待设置",data.policy.profileId?"blue":"warn")); list.append(current);
    const formCard=card("切换当前模型"), form=node("form",undefined,"card-body");
    let selected=pendingPolicy?data.models.find(model=>model.profileId===pendingPolicy.profileId):null;
    const selectedField=field("选定模型",{readOnly:true,value:selected?.modelId||pendingPolicy?.profileId||"",placeholder:"从左侧目录选择可用模型"});
    const reason=field("切换原因",{id:"policy-reason",textarea:true,required:true,maxLength:500,value:pendingPolicy?.reason||"",placeholder:"填写切换原因"});
    const submit=button(pendingPolicy?"重查本次切换":"确认切换",null,"primary"); submit.type="submit"; submit.disabled=!selected&&!pendingPolicy;
    const hint=node("div",pendingPolicy?"上次切换尚未确认，重查沿用原操作。":"切换立即使旧策略失效。已发出的请求仍扣 1 次，未发出的旧请求不再调用模型。","hint");
    form.append(selectedField.wrap,reason.wrap,hint,submit); formCard.append(form);
    list.append(table(["候选模型","验证 / 可用状态","操作"],data.models.map(model=>{
      const choose=button(model.active?"当前模型":"选择",()=>{
        if(pendingPolicy||mutating) {notice("请先确认上一笔模型切换。","warn");return;}
        selected=model; selectedField.input.value=model.modelId; submit.disabled=false; reason.input.focus();
      },"quiet");
      choose.disabled=model.active||!model.verified||!model.available;
      const identity=entity(model.displayName,model.modelId);
      if(model.active) identity.append(badge("主模型","blue"));
      if(model.fallback) identity.append(badge("自动备用","warn"));
      return [identity,badge(!model.verified?"未验证":model.available?"已验证 · 可用":"暂不可用",model.available?"good":"warn"),choose];
    }),"暂无模型配置","请先在服务端配置并验证模型。"));
    const info=node("div",undefined,"card-body"); info.append(node("p","这里展示管理员已载入的模型目录。未验证或暂不可用的模型不能选用；选择模型不会发起额外付费验证。主模型返回无法可靠评分或结果不合法时，自动备用最多派发一次，并单独扣 1 次。","muted"),link("查看模型切换审计","#/audit?type=MODEL_POLICY"));
    list.append(info);
    form.addEventListener("submit",async event=>{
      event.preventDefault(); if(mutating) return;
      const why=reason.input.value.trim();
      if(!why||(!selected&&!pendingPolicy)) {notice("请选择可用模型并填写切换原因。","error");return;}
      if(pendingPolicy&&((selected&&pendingPolicy.profileId!==selected.profileId)||pendingPolicy.reason!==why)) {notice("上次切换尚未确认，请保留原内容重查。","warn");return;}
      pendingPolicy??={requestId:crypto.randomUUID(),profileId:selected.profileId,expectedRevision:data.policy.revision,reason:why};
      const epoch=authEpoch; mutating=true; submit.disabled=true; submit.textContent="正在确认…";
      try {
        const result=await api("/v1/admin/model-policy",{method:"POST",body:pendingPolicy});
        if(epoch!==authEpoch) return;
        pendingPolicy=null; notice("模型切换已确认，策略版本 "+result.policy.revision+"。客户端需刷新当前模型。");
        await render();
      } catch(failure) {
        if(failure instanceof Stale) return;
        if(failure.rejected) {
          pendingPolicy=null;
          if(["MODEL_POLICY_CHANGED","MODEL_UNAVAILABLE"].includes(failure.code)) {selected=null;selectedField.input.value="";}
        }
        notice(failure.message,pendingPolicy?"warn":"error");
        hint.textContent=pendingPolicy?"切换尚未确认。请保持原内容重查，或前往操作审计核对。":"请刷新模型目录后重新选择。";
      } finally {mutating=false;submit.disabled=!selected&&!pendingPolicy;submit.textContent=pendingPolicy?"重查本次切换":"确认切换";}
    });
    const rubricCard=card("一键生成评分规则模型"), rubricForm=node("form",undefined,"card-body");
    const rubricCurrent=node("div",undefined,"current-model"), rubricDescription=node("div");
    rubricDescription.append(node("span","当前规则生成模型","eyebrow"),node("strong",data.rubricPolicy.modelId||"尚未分配"),node("p","独立策略版本 "+data.rubricPolicy.revision+" · 每次成功派发扣 1 次"));
    rubricCurrent.append(rubricDescription,badge(data.rubricPolicy.profileId?"已分配":"等待设置",data.rubricPolicy.profileId?"blue":"warn"));
    rubricCard.append(rubricCurrent);
    let rubricSelected=pendingRubricPolicy?data.models.find(model=>model.profileId===pendingRubricPolicy.profileId):null;
    const rubricField=field("选定模型",{readOnly:true,value:rubricSelected?.modelId||pendingRubricPolicy?.profileId||"",placeholder:"从下方目录选择可用模型"});
    const rubricReason=field("切换原因",{textarea:true,required:true,maxLength:500,value:pendingRubricPolicy?.reason||"",placeholder:"填写规则生成模型切换原因"});
    const rubricSubmit=button(pendingRubricPolicy?"重查本次切换":"确认规则模型",null,"primary"); rubricSubmit.type="submit"; rubricSubmit.disabled=!rubricSelected&&!pendingRubricPolicy;
    const rubricHint=node("div",pendingRubricPolicy?"上次切换尚未确认，重查沿用原操作。":"此模型只生成评分点，不参与学生答案评分。切换不会改变阅卷模型策略。","hint");
    rubricForm.append(rubricField.wrap,rubricReason.wrap,rubricHint,rubricSubmit);
    rubricCard.append(rubricForm,table(["候选模型","状态","操作"],data.models.map(model=>{
      const choose=button(model.rubricActive?"当前模型":"选择",()=>{
        if(pendingRubricPolicy||mutating) {notice("请先确认上一笔规则模型切换。","warn");return;}
        rubricSelected=model; rubricField.input.value=model.modelId; rubricSubmit.disabled=false; rubricReason.input.focus();
      },"quiet");
      choose.disabled=model.rubricActive||!model.verified||!model.available;
      const identity=entity(model.displayName,model.modelId); if(model.rubricActive) identity.append(badge("规则生成","blue"));
      return [identity,badge(!model.verified?"未验证":model.available?"已验证 · 可用":"暂不可用",model.available?"good":"warn"),choose];
    }),"暂无模型配置","请先在服务端配置并验证模型。"));
    rubricForm.addEventListener("submit",async event=>{
      event.preventDefault(); if(mutating) return;
      const why=rubricReason.input.value.trim();
      if(!why||(!rubricSelected&&!pendingRubricPolicy)) {notice("请选择可用模型并填写切换原因。","error");return;}
      if(pendingRubricPolicy&&((rubricSelected&&pendingRubricPolicy.profileId!==rubricSelected.profileId)||pendingRubricPolicy.reason!==why)) {notice("上次切换尚未确认，请保留原内容重查。","warn");return;}
      pendingRubricPolicy??={requestId:crypto.randomUUID(),profileId:rubricSelected.profileId,expectedRevision:data.rubricPolicy.revision,reason:why};
      const epoch=authEpoch; mutating=true; rubricSubmit.disabled=true; rubricSubmit.textContent="正在确认…";
      try {
        const result=await api("/v1/admin/rubric-model-policy",{method:"POST",body:pendingRubricPolicy});
        if(epoch!==authEpoch) return;
        pendingRubricPolicy=null; notice("规则生成模型切换已确认，独立策略版本 "+result.policy.revision+"。");
        await render();
      } catch(failure) {
        if(failure instanceof Stale) return;
        if(failure.rejected) {
          pendingRubricPolicy=null;
          if(["MODEL_POLICY_CHANGED","MODEL_UNAVAILABLE"].includes(failure.code)) {rubricSelected=null;rubricField.input.value="";}
        }
        notice(failure.message,pendingRubricPolicy?"warn":"error");
        rubricHint.textContent=pendingRubricPolicy?"切换尚未确认。请保持原内容重查。":"请刷新模型目录后重新选择。";
      } finally {mutating=false;rubricSubmit.disabled=!rubricSelected&&!pendingRubricPolicy;rubricSubmit.textContent=pendingRubricPolicy?"重查本次切换":"确认规则模型";}
    });
    const forms=node("div"); rubricCard.classList.add("section-gap"); forms.append(formCard,rubricCard);
    columns.append(list,forms);fragment.append(columns);return fragment;
  }
  async function render() {
    if(!session) return;
    const epoch=++pageEpoch; if(pageAbort) pageAbort.abort(); pageAbort=new AbortController();
    const r=route(); $("page-title").textContent=r.id&&r.section==="users"?"用户详情":r.id&&r.section==="model-calls"?"模型调用详情":titles[r.section];
    document.title=$("page-title").textContent+" · DeskGrade 管理后台";
    document.querySelectorAll("nav [data-section]").forEach(item=>{
      if(item.dataset.section===r.section) item.setAttribute("aria-current","page");else item.removeAttribute("aria-current");
    });
    const loading=node("div",undefined,"card loading");loading.setAttribute("role","status");loading.append(node("span",undefined,"spinner"),node("span","正在读取…"));
    $("page-content").replaceChildren(loading); $("page-content").setAttribute("aria-busy","true");
    try {
      const builders={dashboard:dashboardPage,users:r.id?userPage:usersPage,models:modelsPage,"model-calls":r.id?modelCallPage:modelCallsPage,audit:auditPage,orders:ordersPage,packages:packagesPage};
      const content=await builders[r.section](r,pageAbort.signal);
      if(epoch!==pageEpoch||!session) return;
      $("page-content").replaceChildren(content);
    } catch(failure) {
      if(epoch!==pageEpoch||failure instanceof Stale||!session) return;
      const error=node("section",undefined,"card error-card");error.append(node("h2","暂时无法读取"),node("p",failure.message,"muted"),button("重新读取",()=>void render(),"primary"));
      $("page-content").replaceChildren(error);
    } finally {if(epoch===pageEpoch) $("page-content").removeAttribute("aria-busy");}
  }
  $("login-form").addEventListener("submit",async event=>{
    event.preventDefault(); const submit=$("login-button");submit.disabled=true;submit.textContent="正在登录…";loginMessage("");
    const username=$("username").value.trim(), password=$("password").value; $("password").value="";
    try {
      const data=await api("/v1/auth/login",{method:"POST",body:{username,password},authenticated:false});
      session={token:data.token,expiresAt:data.expiresAt,account:data.account};authEpoch++;
      if(data.account.role!=="ADMIN") {
        try {await api("/v1/auth/logout",{method:"POST"});} finally {endSession("此账号没有管理员权限，请使用管理员账号登录。");}
        return;
      }
      const remaining=Date.parse(data.expiresAt)-Date.now();
      if(!Number.isFinite(remaining)||remaining<=0) {endSession(messages.AUTH_REQUIRED);return;}
      expiryTimer=setTimeout(()=>endSession(messages.AUTH_REQUIRED),Math.min(remaining,2147483647));
      $("login-screen").hidden=true;$("shell").hidden=false;$("account-name").textContent=data.account.username;
      await render();
    } catch(failure) {if(!(failure instanceof Stale)) loginMessage(failure.message);}
    finally {submit.disabled=false;submit.textContent="登录";}
  });
  $("logout-button").addEventListener("click",async()=>{
    const submit=$("logout-button");submit.disabled=true;
    try {await api("/v1/auth/logout",{method:"POST"});endSession("已退出登录。");}
    catch(failure) {if(!(failure instanceof Stale)) notice("退出请求尚未确认，请重试退出；当前会话仍保留。","warn");}
    finally {submit.disabled=false;}
  });
  window.addEventListener("hashchange",()=>{notice("");void render();});
  window.addEventListener("pagehide",()=>endSession("请重新登录管理后台。"));
})();
