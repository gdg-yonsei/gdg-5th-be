import {readSSE} from './sse.mjs';

const $ = id => document.getElementById(id);
let chats = [], currentId = null, active = null, follow = true;
let loadingList = true, loadingChat = false, deletingId = null, listError = false;
const current = () => chats.find(chat => chat.id === currentId);
const busy = () => Boolean(active || loadingList || loadingChat || deletingId);

function toast(text) {
  $('toast').textContent = text;
  $('toast').hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => $('toast').hidden = true, 2400);
}
function showError(text) {
  $('error').textContent = text;
  $('error').hidden = false;
}
async function requestJSON(path, {method = 'GET', body, signal} = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener('abort', abort, {once: true});
  if (signal?.aborted) abort();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 15000);
  try {
    const response = await fetch(path, {
      method, signal: controller.signal,
      headers: {'Accept': 'application/json', ...(body ? {'Content-Type': 'application/json'} : {})},
      ...(body ? {body: JSON.stringify(body)} : {}),
    });
    if (!response.ok) {
      if (response.status === 404) throw new Error('대화를 찾을 수 없어요. 목록을 새로 불러와 주세요.');
      throw new Error(`대화 요청을 처리하지 못했어요. (HTTP ${response.status})`);
    }
    return response.status === 204 ? null : await response.json();
  } catch (error) {
    if (timedOut) throw new Error('응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.');
    throw error;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}
function parseConversation(value) {
  if (!value || typeof value.id !== 'string' || !value.id || typeof value.title !== 'string') {
    throw new Error('대화 목록의 응답 형식이 올바르지 않아요.');
  }
  return {...value, messages: []};
}
function parseMessages(values) {
  if (!Array.isArray(values) || values.some(value => !value ||
      !['user', 'assistant'].includes(value.role) || typeof value.content !== 'string')) {
    throw new Error('메시지 응답 형식이 올바르지 않아요.');
  }
  return values.map(value => ({...value, status: value.role === 'assistant' ?
    (['done', 'stopped', 'error'].includes(value.status) ? value.status : 'done') : undefined}));
}
async function loadConversations() {
  if (active || loadingChat || deletingId) return;
  loadingList = true;
  listError = false;
  render();
  try {
    const result = await requestJSON('/api/conversations');
    if (!Array.isArray(result)) throw new Error('대화 목록의 응답 형식이 올바르지 않아요.');
    chats = result.map(parseConversation);
    currentId = null;
  } catch (error) {
    listError = true;
    showError(error.message || '대화 목록을 불러오지 못했어요.');
  } finally {
    loadingList = false;
    render();
  }
}
async function openConversation(chat) {
  if (busy()) return;
  loadingChat = true;
  $('error').hidden = true;
  render();
  try {
    const result = await requestJSON(`/api/conversations/${encodeURIComponent(chat.id)}/messages`);
    chat.messages = parseMessages(result);
    currentId = chat.id;
    follow = true;
    sidebar(false);
  } catch (error) {
    showError(error.message || '대화를 불러오지 못했어요.');
  } finally {
    loadingChat = false;
    render();
  }
}
async function deleteConversation(chat) {
  if (busy()) return;
  deletingId = chat.id;
  $('error').hidden = true;
  render();
  try {
    await requestJSON(`/api/conversations/${encodeURIComponent(chat.id)}`, {method: 'DELETE'});
    chats = chats.filter(value => value.id !== chat.id);
    if (currentId === chat.id) currentId = null;
  } catch (error) {
    showError(error.message || '대화를 삭제하지 못했어요.');
  } finally {
    deletingId = null;
    render();
  }
}
function sidebar(open) {
  document.body.classList.toggle('sidebar-open', open);
  $('backdrop').hidden = !open;
  $('menu').setAttribute('aria-expanded', String(open));
}
function history() {
  $('history').replaceChildren();
  $('chat-count').textContent = chats.length;
  if (loadingList || listError || !chats.length) {
    const note = document.createElement('p');
    note.className = 'history-empty';
    note.textContent = loadingList ? '대화를 불러오는 중…' : listError ?
      '대화 목록을 불러오지 못했어요.' : '첫 번째 대화를 시작해 보세요. 대화가 여기에 모여요.';
    $('history').append(note);
    if (listError) {
      const retry = document.createElement('button');
      retry.className = 'new-chat';
      retry.textContent = '목록 다시 불러오기';
      retry.disabled = busy();
      retry.onclick = () => { $('error').hidden = true; loadConversations(); };
      $('history').append(retry);
    }
  }
  for (const chat of chats) {
    const row = document.createElement('div');
    row.className = 'history-item' + (chat.id === currentId ? ' active' : '');
    const open = document.createElement('button');
    open.className = 'history-open';
    open.textContent = chat.title || '새로운 대화';
    open.title = open.textContent;
    open.disabled = busy();
    open.onclick = () => openConversation(chat);
    const remove = document.createElement('button');
    remove.className = 'delete-chat';
    remove.textContent = deletingId === chat.id ? '…' : '×';
    remove.setAttribute('aria-label', `${open.textContent} 대화 삭제`);
    remove.disabled = busy();
    remove.onclick = () => deleteConversation(chat);
    row.append(open, remove);
    $('history').append(row);
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
function scroll(force = false) {
  if (follow || force) $('scroll-area').scrollTop = $('scroll-area').scrollHeight;
}
function renderMessages() {
  const list = current()?.messages || [];
  $('welcome').hidden = Boolean(list.length);
  $('messages').hidden = !list.length;
  $('messages').replaceChildren(...list.map(message));
  scroll();
}
function controls() {
  const working = busy();
  $('send').hidden = Boolean(active);
  $('stop').hidden = !active;
  $('send').disabled = working || !$('prompt').value.trim();
  $('prompt').disabled = working;
  $('new-chat').disabled = working;
  document.querySelectorAll('[data-prompt]').forEach(button => button.disabled = working);
}
function render() { history(); renderMessages(); controls(); }
function fresh() {
  if (busy()) return;
  currentId = null;
  follow = true;
  $('error').hidden = true;
  render();
  sidebar(false);
  $('prompt').focus();
}
$('new-chat').onclick = fresh;
$('menu').onclick = () => sidebar(!document.body.classList.contains('sidebar-open'));
$('backdrop').onclick = () => sidebar(false);
$('prompt').oninput = () => {
  $('prompt').style.height = 'auto';
  $('prompt').style.height = Math.min($('prompt').scrollHeight, 180) + 'px';
  controls();
};
$('prompt').onkeydown = event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    if (!busy() && $('prompt').value.trim()) $('composer').requestSubmit();
  }
};
document.addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault(); fresh();
  }
  if (event.key === 'Escape') sidebar(false);
});
document.querySelectorAll('[data-prompt]').forEach(button => button.onclick = () => {
  if (busy()) return;
  $('prompt').value = button.dataset.prompt;
  $('prompt').dispatchEvent(new Event('input'));
  $('prompt').focus();
});
$('scroll-area').addEventListener('scroll', () => {
  const element = $('scroll-area');
  follow = element.scrollHeight - element.scrollTop - element.clientHeight < 100;
  $('jump-bottom').hidden = follow;
});
$('jump-bottom').onclick = () => { follow = true; scroll(true); };
$('stop').onclick = () => active?.controller.abort();
$('composer').onsubmit = async event => {
  event.preventDefault();
  const prompt = $('prompt').value.trim();
  if (!prompt || busy()) return;
  let chat = current();
  const historyMessages = (chat?.messages || [])
    .filter(value => value.role === 'user' || value.status === 'done')
    .map(({role, content}) => ({role, content}));
  if (historyMessages.length >= 100) {
    toast('대화가 길어졌어요. 새 대화를 시작해 주세요.'); return;
  }
  const controller = new AbortController();
  active = {controller, chatId: chat?.id};
  $('error').hidden = true;
  render();
  let answer = null;
  let completed = false;
  try {
    if (!chat) {
      const result = await requestJSON('/api/conversations', {
        method: 'POST', body: {title: prompt.slice(0, 80)}, signal: controller.signal,
      });
      chat = parseConversation(result);
      chats.unshift(chat);
      currentId = chat.id;
      active.chatId = chat.id;
    }
    const user = {role: 'user', content: prompt};
    answer = {role: 'assistant', content: '', status: 'streaming'};
    chat.messages.push(user, answer);
    chat.updatedAt = new Date().toISOString();
    chats = [chat, ...chats.filter(value => value.id !== chat.id)];
    follow = true;
    $('prompt').value = '';
    $('prompt').style.height = 'auto';
    render();
    // 사용자 메시지와 답변 저장은 이 요청을 받은 백엔드가 처리합니다.
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json', 'Accept': 'text/event-stream'},
      body: JSON.stringify({conversationId: chat.id, messages: [...historyMessages, user]}),
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(response.status === 400 ?
      '메시지 형식을 확인해 주세요.' : `서비스에 연결하지 못했어요. (HTTP ${response.status})`);
    if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
      throw new Error('올바른 스트리밍 응답을 받지 못했어요.');
    }
    await readSSE(response.body, (name, data) => {
      if (name === 'delta') {
        if (typeof data.content !== 'string') throw new Error('응답 형식이 올바르지 않아요.');
        answer.content += data.content;
        renderMessages();
      }
      if (name === 'done') completed = true;
      if (name === 'error') throw new Error(data.message || '응답 중 오류가 발생했어요.');
    });
    if (!completed) throw new Error('응답이 중간에 끊겼어요. 다시 시도해 주세요.');
    answer.status = 'done';
  } catch (error) {
    if (answer) answer.status = error.name === 'AbortError' ? 'stopped' : 'error';
    if (error.name !== 'AbortError') showError(error.message || '연결을 확인한 후 다시 시도해 주세요.');
  } finally {
    active = null;
    render();
    $('prompt').focus();
  }
};
render();
loadConversations();
