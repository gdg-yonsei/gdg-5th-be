import {readSSE} from './sse.mjs';
const $ = id => document.getElementById(id);
const KEY = 'moa.chats.v1';
let chats = [], currentId = null, active = null, follow = true;
try {
  const saved = JSON.parse(localStorage.getItem(KEY) || '[]');
  if (Array.isArray(saved)) chats = saved.filter(c => typeof c.id === 'string' && Array.isArray(c.messages))
    .map(c => ({id:c.id, messages:c.messages.filter(m => ['user','assistant'].includes(m.role) && typeof m.content === 'string')
      .map(m => ({...m, status:m.status === 'streaming' ? 'stopped' : m.status}))}));
} catch { /* Corrupt or unavailable storage starts an empty workspace. */ }
function toast(text) { $('toast').textContent=text; $('toast').hidden=false; clearTimeout(toast.timer); toast.timer=setTimeout(()=>$('toast').hidden=true,2400); }
function save() { try { localStorage.setItem(KEY,JSON.stringify(chats)); } catch { toast('저장 공간이 부족하거나 사용할 수 없어 대화를 저장하지 못했어요.'); } }
const current = () => chats.find(c=>c.id===currentId);
function sidebar(open) { document.body.classList.toggle('sidebar-open',open); $('backdrop').hidden=!open; $('menu').setAttribute('aria-expanded',String(open)); }
function history() {
  $('history').replaceChildren(); $('chat-count').textContent=chats.length;
  if (!chats.length) { const p=document.createElement('p'); p.className='history-empty'; p.textContent='첫 번째 대화를 시작해 보세요.\n대화가 여기에 모여요.'; $('history').append(p); }
  for (const c of chats) {
    const row=document.createElement('div'); row.className='history-item'+(c.id===currentId?' active':'');
    const open=document.createElement('button'); open.className='history-open'; open.textContent=c.messages.find(m=>m.role==='user')?.content.slice(0,35)||'새로운 대화'; open.title=open.textContent;
    open.onclick=()=>{currentId=c.id; render(); sidebar(false);};
    const del=document.createElement('button'); del.className='delete-chat'; del.textContent='×'; del.setAttribute('aria-label',`${open.textContent} 대화 삭제`);
    del.onclick=()=>{if(active?.chatId===c.id) active.controller.abort(); chats=chats.filter(x=>x!==c); if(currentId===c.id) currentId=null; save(); render();};
    row.append(open,del); $('history').append(row);
  }
}
function content(node,text) {
  // Model output is always text nodes; fenced code receives a safe code block.
  node.replaceChildren();
  text.split(/```(?:[^\n`]*\n)?([\s\S]*?)```/g).forEach((part,i)=>{
    if(i%2){const pre=document.createElement('pre'),code=document.createElement('code');code.textContent=part;pre.append(code);node.append(pre);}
    else node.append(document.createTextNode(part));
  });
}
function message(m) {
  const row=document.createElement('article'); row.className=`message ${m.role}`;
  if(m.role==='assistant'){const icon=document.createElement('div');icon.className='assistant-icon';icon.textContent='✳';row.append(icon);}
  const body=document.createElement('div');body.className='message-body';
  if(m.role==='assistant'){const label=document.createElement('div');label.className='message-label';label.textContent='GDGoC AI';body.append(label);}
  const text=document.createElement('div');text.className='message-text'+(m.status==='streaming'?' typing':'');content(text,m.content);body.append(text);
  if(m.role==='assistant'){
    const actions=document.createElement('div');actions.className='message-actions';
    if(m.content){const copy=document.createElement('button');copy.className='copy';copy.textContent='⧉ 복사';copy.onclick=async()=>{try{await navigator.clipboard.writeText(m.content);toast('답변을 복사했어요.');}catch{toast('복사할 수 없어요. 텍스트를 선택해서 복사해 주세요.');}};actions.append(copy);}
    const status=document.createElement('span');status.className='message-status';status.textContent=({streaming:'생각을 모으는 중…',stopped:'응답 중지됨',error:'응답 실패'})[m.status]||'';actions.append(status);body.append(actions);
  }
  row.append(body);return row;
}
function scroll(force=false){if(follow||force)$('scroll-area').scrollTop=$('scroll-area').scrollHeight;}
function renderMessages(){const list=current()?.messages||[];$('welcome').hidden=!!list.length;$('messages').hidden=!list.length;$('messages').replaceChildren(...list.map(message));scroll();}
function controls(){const busy=!!active;$('send').hidden=busy;$('stop').hidden=!busy;$('send').disabled=busy||!$('prompt').value.trim();$('prompt').disabled=busy;}
function render(){history();renderMessages();controls();}
function fresh(){currentId=null;follow=true;$('error').hidden=true;render();sidebar(false);if(!active)$('prompt').focus();}
$('new-chat').onclick=fresh;$('menu').onclick=()=>sidebar(!document.body.classList.contains('sidebar-open'));$('backdrop').onclick=()=>sidebar(false);
$('prompt').oninput=()=>{$('prompt').style.height='auto';$('prompt').style.height=Math.min($('prompt').scrollHeight,180)+'px';controls();};
$('prompt').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();if(!active&&$('prompt').value.trim())$('composer').requestSubmit();}};
document.addEventListener('keydown',e=>{if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='k'){e.preventDefault();fresh();}if(e.key==='Escape')sidebar(false);});
document.querySelectorAll('[data-prompt]').forEach(b=>b.onclick=()=>{if(active)return;$('prompt').value=b.dataset.prompt;$('prompt').dispatchEvent(new Event('input'));$('prompt').focus();});
$('scroll-area').addEventListener('scroll',()=>{const el=$('scroll-area');follow=el.scrollHeight-el.scrollTop-el.clientHeight<100;$('jump-bottom').hidden=follow;});
$('jump-bottom').onclick=()=>{follow=true;scroll(true);};$('stop').onclick=()=>active?.controller.abort();
$('composer').onsubmit=async e=>{
  e.preventDefault();const prompt=$('prompt').value.trim();if(!prompt||active)return;
  let chat=current();if(!chat){chat={id:crypto.randomUUID(),messages:[]};chats.unshift(chat);currentId=chat.id;}
  // Keep failed / interrupted answers out of the provider's history.
  const historyMessages=chat.messages.filter(m=>m.role==='user'||m.status==='done').map(({role,content})=>({role,content}));
  if(historyMessages.length>=100){toast('대화가 길어졌어요. 새 대화를 시작해 주세요.');return;}
  const user={role:'user',content:prompt};chat.messages.push(user);
  const answer={role:'assistant',content:'',status:'streaming'};chat.messages.push(answer);
  const controller=new AbortController();active={controller,chatId:chat.id};follow=true;
  $('prompt').value='';$('prompt').style.height='auto';$('error').hidden=true;save();render();
  let completed=false;
  try{
    const response=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json','Accept':'text/event-stream'},body:JSON.stringify({conversationId:chat.id,messages:[...historyMessages,user]}),signal:controller.signal});
    if(!response.ok)throw new Error(response.status===400?'메시지 형식을 확인해 주세요.':`서비스에 연결하지 못했어요. (HTTP ${response.status})`);
    if(!response.body||!response.headers.get('content-type')?.includes('text/event-stream'))throw new Error('올바른 스트리밍 응답을 받지 못했어요.');
    await readSSE(response.body,(event,data)=>{
      if(event==='delta'){if(typeof data.content!=='string')throw new Error('응답 형식이 올바르지 않아요.');answer.content+=data.content;if(currentId===chat.id)renderMessages();}
      if(event==='done')completed=true;
      if(event==='error')throw new Error(data.message||'응답 중 오류가 발생했어요.');
    });
    if(!completed)throw new Error('응답이 중간에 끊겼어요. 다시 시도해 주세요.');
    answer.status='done';
  }catch(error){
    answer.status=error.name==='AbortError'?'stopped':'error';
    if(error.name!=='AbortError'){ $('error').textContent=error.message||'연결을 확인한 후 다시 시도해 주세요.';$('error').hidden=false; }
  }finally{active=null;save();render();$('prompt').focus();}
};
render();
