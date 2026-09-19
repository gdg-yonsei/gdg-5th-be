/* feed-lab 대시보드. 의존성 없음 (차트는 canvas 로 직접 그린다: 세션 장소 네트워크에 기대지 않기 위해). */
const $ = (s) => document.querySelector(s);
const fmt = (n, d = 0) => (n == null || isNaN(n)) ? '-' : Number(n).toLocaleString('ko-KR', { maximumFractionDigits: d, minimumFractionDigits: d });
const C = { A: 1, B: 2, C: 3, D: 4, E: 5 };
const CELEBS = [3, 6, 7, 8, 9];
const VERSIONS = {
  v1: { name: '읽을 때 모은다', color: '#ea4335', files: ['V1PullOnRead.kt'] },
  v2: { name: '쓸 때 뿌린다', color: '#f29900', files: ['V2SyncFanout.kt', 'Fanout.kt'] },
  v3: { name: '나중에 뿌린다', color: '#4285f4', files: ['V3Outbox.kt', 'Fanout.kt'] },
  v4: { name: '셀럽만 따로', color: '#34a853', files: ['V4Hybrid.kt', 'V3Outbox.kt'] },
};
const PRESETS = {
  stop:  { readRps: 0,    mix: 'uniform',    writeRps: 0 },
  low:   { readRps: 30,   mix: 'uniform',    writeRps: 0 },
  s1:    { readRps: 300,  mix: 'uniform',    writeRps: 0 },
  heavy: { readRps: 300,  mix: 'heavy_only', writeRps: 0 },
  s2:    { readRps: 3000, mix: 'uniform',    writeRps: 0 },
  s3:    { readRps: 300,  mix: 'uniform',    writeRps: 5 },
};
// 폴링 주기. 주소 뒤에 ?poll=500 처럼 붙이면 바꿀 수 있다 (기본 250ms).
const POLL_MS = Math.max(100, Number(new URLSearchParams(location.search).get('poll')) || 250);
const OBSERVER_MS = Math.min(300, POLL_MS);
const HIST = Math.round(60000 / POLL_MS);           // 스파크라인은 항상 최근 60초
const COHORTS = [['light', '라이트', '5~50', '#34a853'], ['mid', '미드', '100~400', '#f29900'], ['heavy', '헤비', '1,000~3,000', '#ea4335']];

const state = {
  version: 'v1', me: 1, users: {}, config: {}, loadgen: '',
  load: { ...PRESETS.stop }, preset: 'stop', appliedAt: 0,
  manualWrites: [], lastManual: null, lastBurstAt: 0, lastProp: null,
  hist: { read: [], write: [], prop: [], feeds: [] },
  ledger: JSON.parse(localStorage.getItem('feedlab.ledger') || '{}'),
  obs: { seen: new Set(), badges: new Map(), firstSeenAt: new Map(), primed: false },
  codeFile: null,
};

// ---------------------------------------------------------------- API
async function api(path, { method = 'GET', user, body, base = '' } = {}) {
  const t0 = performance.now();
  try {
    const res = await fetch(base + path, {
      method,
      headers: { ...(user ? { 'X-User-Id': String(user) } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    let data = null;
    try { data = await res.json(); } catch { /* 본문 없음 */ }
    return { ok: res.ok, status: res.status, data, ms: performance.now() - t0, serverMs: parseFloat(res.headers.get('X-Server-Time-Ms')) };
  } catch (e) {
    return { ok: false, status: 0, data: null, ms: performance.now() - t0, serverMs: NaN };
  }
}
const userName = (id) => state.users[id]?.name || `user_${id}`;
const shortName = (id) => (state.users[id]?.name || `user_${id}`).split(' · ')[0];

function toast(html, kind = '', ms = 6000) {
  const el = document.createElement('div');
  el.className = `toast ${kind}`; el.innerHTML = html;
  $('#toasts').appendChild(el);
  setTimeout(() => el.remove(), ms);
}

// ---------------------------------------------------------------- 계정 / 버전 / 부하
async function loadAccount(id) {
  const r = await api(`/users/${id}`);
  if (!r.ok) { toast(`유저 ${id} 를 찾을 수 없습니다`, 'bad'); return; }
  state.users[id] = r.data; state.me = id;
  $('#accountMeta').innerHTML = `팔로우 <b>${fmt(r.data.followCount)}</b>명 · 팔로워 <b>${fmt(r.data.followerCount)}</b>명`;
  $('#writeAs').textContent = `(${shortName(id)} 로 작성)`;
  $('#myFeedTitle').textContent = `${shortName(id)}의 피드`;
  if ([...$('#account').options].some((o) => o.value == id)) $('#account').value = id;
  refreshMyFeed();
}

function setVersion(v, push = true) {
  state.version = v;
  document.body.dataset.version = v;
  document.querySelectorAll('#versions button').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  state.obs.primed = false;                       // 버전이 바뀌면 피드 내용이 달라질 수 있다: 배지 기준을 다시 잡는다
  state.lastProp = null;
  if (push) pushControl();
  refreshMyFeed(); renderLedger();
  clearTimeout(state.refreshTimer); state.refreshTimer = setTimeout(refreshMyFeed, 4500);   // 이전 버전의 밀린 요청이 빠진 뒤의 값으로 다시 보여준다
  if (!$('#codeDrawer').hidden) showCode(VERSIONS[v].files[0]);
}

async function pushControl() {
  state.appliedAt = Date.now();
  const l = state.load;
  await api('/control', { base: state.loadgen, method: 'POST', body: { version: state.version, read: { targetRps: l.readRps, mix: l.mix }, write: { targetRps: l.writeRps } } });
}

function applyLoad(load, preset = null) {
  state.load = { ...load }; state.preset = preset;
  $('#readRps').value = load.readRps; $('#readRpsNum').value = load.readRps;
  $('#writeRps').value = load.writeRps; $('#writeRpsNum').value = load.writeRps;
  $('#mix').value = load.mix;
  document.querySelectorAll('#presets button').forEach((b) => b.classList.toggle('on', b.dataset.preset === preset));
  pushControl();
}

// ---------------------------------------------------------------- 글 작성
async function writePost(userId, content, { quiet = false } = {}) {
  const version = state.version;
  const r = await api(`/${version}/posts`, { method: 'POST', user: userId, body: { content } });
  state.manualWrites.push({ at: Date.now(), ms: r.ms });
  state.lastManual = { who: shortName(userId), ms: r.ms, ok: r.ok };
  // 기록: C 혼자 쓴 글의 응답 시간. (v2 에서 5명이 동시에 쓰면 서로 경쟁해서 더 느려지므로 버스트 값은 v3/v4 에서만 쓴다)
  if (r.ok && userId === C.C && (!quiet || version === 'v3' || version === 'v4')) { ledger(version).celebWrite = r.ms; saveLedger(); }
  if (!quiet) {
    if (r.ok) toast(`${userName(userId)} 작성 완료 · 응답 <b>${fmt(r.ms)}ms</b> <small style="color:#cbd5e1">(서버 ${fmt(r.serverMs)}ms)</small>`, r.ms > 1000 ? 'bad' : 'good');
    else toast(`${userName(userId)} 작성 실패 (HTTP ${r.status}) · ${fmt(r.ms)}ms`, 'bad');
  }
  if (userId === state.me) refreshMyFeed();
  return r;
}
const stamp = () => new Date().toLocaleTimeString('ko-KR', { hour12: false });

async function burst() {
  state.lastBurstAt = Date.now();
  toast(`셀럽 5명이 동시에 글을 씁니다 (팔로워 합계 약 ${fmt(CELEBS.reduce((s, id) => s + (state.users[id]?.followerCount || 0), 0))}명)`, '', 4000);
  const rs = await Promise.all(CELEBS.map((id) => writePost(id, `${shortName(id)}: 방금 올린 글 (${stamp()})`, { quiet: true })));
  const worst = Math.max(...rs.map((r) => r.ms));
  toast(`셀럽 5명 작성 완료 · 가장 느린 응답 <b>${fmt(worst)}ms</b>`, worst > 1000 ? 'bad' : 'good');
}

// ---------------------------------------------------------------- 피드 렌더
function renderFeed(ol, items, serverNowMs, { badges = null, firstSeenAt = null } = {}) {
  if (!items.length) { ol.innerHTML = '<li class="empty">글이 없습니다</li>'; return; }
  const now = Date.now();
  ol.innerHTML = items.map((p) => {
    const cls = CELEBS.includes(p.authorId) ? 'celeb' : p.authorId === C.D ? 'd' : p.authorId === state.me ? 'me' : '';
    const age = Math.max(0, (serverNowMs - p.createdAtMs) / 1000);
    const when = age < 60 ? `${fmt(age)}초 전` : age < 3600 ? `${fmt(age / 60)}분 전` : age < 86400 ? `${fmt(age / 3600)}시간 전` : `${fmt(age / 86400)}일 전`;
    let badge = '';
    if (badges?.has(p.id)) { const s = badges.get(p.id); badge = `<span class="badge ${s > 5 ? 'slow' : s > 2 ? 'mid' : ''}">도착까지 ${fmt(s, 1)}초</span>`; }
    const fresh = firstSeenAt?.has(p.id) && now - firstSeenAt.get(p.id) < 2500 ? 'fresh' : '';
    return `<li class="${fresh}"><span class="who ${cls}">${esc(userName(p.authorId))}${badge}</span><span class="when">${when}</span><span class="text">${esc(p.content)}</span></li>`;
  }).join('');
}
const esc = (s) => String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));

async function refreshMyFeed() {
  const r = await api(`/${state.version}/feed`, { user: state.me });
  const t = $('#myFeedTiming');
  if (r.ok) { t.innerHTML = `응답 <b class="${r.ms > 500 ? 'bad' : ''}">${fmt(r.ms)}ms</b> · 서버 ${fmt(r.serverMs)}ms`; renderFeed($('#myFeed'), r.data.items, r.data.serverNowMs); }
  else {
    t.innerHTML = `<b class="bad">실패 (HTTP ${r.status})</b> · ${fmt(r.ms)}ms`;
    $('#myFeed').innerHTML = `<li class="error">피드를 못 가져왔습니다 · ${fmt(r.ms)}ms 기다림<small>${r.status === 503 ? 'DB 커넥션을 3초 안에 얻지 못했습니다 (커넥션 풀 대기열 초과)' : 'HTTP ' + r.status}</small></li>`;
  }
}

// 관찰자 E: 0.5초마다 자기 피드를 읽고, 처음 보는 글에 "도착까지 N초"(= 처음 보인 시각 - 작성 시각, 서버 시계 기준)를 붙인다.
async function observerTick() {
  const version = state.version, obs = state.obs;
  const r = await api(`/${version}/feed`, { user: C.E });
  if (r.ok && version === state.version) {
    const { items, serverNowMs } = r.data;
    for (const p of items) {
      if (obs.seen.has(p.id)) continue;
      obs.seen.add(p.id);
      if (!obs.primed) continue;                  // 화면을 열었을 때 이미 있던 글에는 배지를 달지 않는다
      const sec = (serverNowMs - p.createdAtMs) / 1000;
      if (sec > 300) continue;
      obs.badges.set(p.id, sec); obs.firstSeenAt.set(p.id, Date.now());
      if (p.authorId === C.C) { ledger(version).celebArrive = sec; saveLedger(); }
      if (p.authorId === C.D && Date.now() - state.lastBurstAt < 120000) { ledger(version).dArrive = sec; saveLedger(); }
    }
    obs.primed = true;
    renderFeed($('#obsFeed'), items, serverNowMs, obs);
    $('#obsTiming').innerHTML = `자동 갱신 · 응답 <b class="${r.ms > 500 ? 'bad' : ''}">${fmt(r.ms)}ms</b>`;
  } else if (!r.ok) {
    $('#obsTiming').innerHTML = `<b class="bad">읽기 실패 (HTTP ${r.status})</b> · ${fmt(r.ms)}ms`;
  }
  setTimeout(observerTick, OBSERVER_MS);
}

// ---------------------------------------------------------------- 상황판 (1초 폴링)
async function pollTick() {
  const [s, m] = await Promise.all([api('/stats', { base: state.loadgen }), api('/metrics-lite')]);
  const banner = $('#banner');
  if (!s.ok || !m.ok) { banner.hidden = false; banner.textContent = !m.ok ? '앱 메트릭 응답 없음' : 'loadgen(:8081) 연결 안 됨'; }
  else banner.hidden = true;
  if (s.ok) renderLoad(s.data);
  if (m.ok) renderMetrics(m.data, s.ok ? s.data : null);
  setTimeout(pollTick, POLL_MS);
}

function push(hist, v) { hist.push(v); while (hist.length > HIST) hist.shift(); }

function renderLoad(st) {
  const rd = st.read, wr = st.write, target = rd.targetRps;
  const settled = Date.now() - state.appliedAt > 6000;
  // 1. 읽기
  const allFail = target > 0 && rd.count > 0 && rd.achievedRps === 0;
  $('#readP99').textContent = target > 0 ? (allFail ? '실패' : fmt(rd.p99Ms)) : '-';
  const readBad = target > 0 && (rd.p99Ms > 500 || rd.errorRate > 0.02 || (settled && rd.achievedRps < target * 0.9));
  $('#cardRead').classList.toggle('bad', readBad);
  $('#cardRead').classList.toggle('good', target > 0 && !readBad && settled);
  $('#readSub').innerHTML = target > 0
    ? `성공 <b class="${settled && rd.achievedRps < target * 0.9 ? 'bad' : ''}">${fmt(rd.achievedRps)}</b> / 목표 ${fmt(target)} rps${settled && rd.sentRps < target * 0.9 ? ` (부하기가 보낸 양 ${fmt(rd.sentRps)})` : ''} · 에러 <b class="${rd.errorRate > 0.02 ? 'bad' : ''}">${fmt(rd.errorRate * 100)}%</b> · p50 ${fmt(rd.p50Ms)}ms`
    : '부하 없음';
  push(state.hist.read, target > 0 ? rd.p99Ms : 0);

  // 2. 쓰기: 부하의 최대값과 방금 손으로 쓴 글 중 큰 쪽
  const now = Date.now();
  state.manualWrites = state.manualWrites.filter((w) => now - w.at < 5000);
  const manualMax = Math.max(0, ...state.manualWrites.map((w) => w.ms));
  const wmax = Math.max(wr.maxMs || 0, manualMax);
  const active = wr.targetRps > 0 || state.manualWrites.length > 0;
  $('#writeMax').textContent = active ? fmt(wmax) : (state.lastManual ? fmt(state.lastManual.ms) : '-');
  $('#cardWrite').classList.toggle('bad', active && wmax > 1000);
  const parts = [];
  if (wr.targetRps > 0) parts.push(`부하 ${fmt(wr.achievedRps, 1)}/s · 평균 <b>${fmt(wr.avgMs)}ms</b> · p99 ${fmt(wr.p99Ms)}ms`);
  if (state.lastManual) parts.push(`마지막 수동: ${esc(state.lastManual.who)} <b class="${state.lastManual.ms > 1000 ? 'bad' : ''}">${fmt(state.lastManual.ms)}ms</b>`);
  $('#writeSub').innerHTML = parts.join(' · ') || '쓰기 없음';
  push(state.hist.write, active ? wmax : 0);

  // 코호트 표
  $('#cohortBody').innerHTML = COHORTS.map(([key, label, range, color]) => {
    const c = rd.byCohort[key];
    if (!c || !c.count) return `<tr><td><span class="dot" style="background:${color}"></span>${label}</td><td>${range}</td><td>-</td><td>-</td><td>-</td><td>-</td></tr>`;
    return `<tr><td><span class="dot" style="background:${color}"></span>${label}</td><td>${range}</td><td>${fmt(c.p50Ms)}ms</td><td class="${c.p99Ms > 500 ? 'bad' : ''}">${fmt(c.p99Ms)}ms</td><td>${fmt(c.achievedRps)}</td><td class="${c.errorRate > 0.02 ? 'bad' : ''}">${fmt(c.errorRate * 100)}%</td></tr>`;
  }).join('');

  drawScatter($('#scatterRead'), st.scatterRead, { xMax: 3200, color: (p) => COHORTS[p[3]]?.[3] || '#888', xLabel: '팔로우 수' });
  drawScatter($('#scatterWrite'), st.scatterWrite, { xMax: 5500, color: () => '#4285f4', xLabel: '팔로워 수' });
  drawSpark($('#cardRead .spark'), state.hist.read, readBad ? '#ea4335' : '#4285f4');
  drawSpark($('#cardWrite .spark'), state.hist.write, wmax > 1000 ? '#ea4335' : '#4285f4');

  // 기록: 균등 300rps 로 6초 이상 돌았을 때의 값
  if (target === 300 && rd.mix === 'uniform' && settled && st.version === state.version) {
    const l = ledger(st.version); l.p99 = allFail ? null : rd.p99Ms; l.rps = rd.achievedRps; saveLedger();
  }
}

function renderMetrics(m, st) {
  const db = m.db, ob = m.outbox;
  // DB 풀
  const bar = $('#poolBar');
  if (bar.children.length !== db.poolMax) bar.innerHTML = '<i></i>'.repeat(db.poolMax), bar.style.gridTemplateColumns = `repeat(${db.poolMax}, 1fr)`;
  [...bar.children].forEach((el, i) => el.classList.toggle('on', i < db.poolActive));
  bar.classList.toggle('full', db.poolWaiting > 0);
  $('#poolText').innerHTML = `<b>${db.poolActive}</b>/${db.poolMax} 사용 · 대기 <b class="${db.poolWaiting > 0 ? 'bad' : ''}">${fmt(db.poolWaiting)}</b>`;

  // 3. 반영 지연
  const v = state.version, card = $('#cardProp');
  let val = null, sub = '';
  if (ob.maxPropagationMs != null) state.lastProp = { ms: ob.maxPropagationMs, at: Date.now() };
  if (v === 'v1') { $('#propVal').textContent = '즉시'; $('#propUnit').textContent = ''; sub = '읽을 때 모으므로 쓰자마자 보인다'; }
  else if (v === 'v2') { $('#propVal').textContent = '즉시'; $('#propUnit').textContent = ''; sub = '응답이 곧 반영 (대신 응답이 느리다)'; }
  else {
    $('#propUnit').textContent = '초';
    if (ob.pending > 0) val = ob.oldestPendingMs / 1000;
    else if (state.lastProp && Date.now() - state.lastProp.at < 60000) val = state.lastProp.ms / 1000;
    $('#propVal').textContent = val == null ? '-' : fmt(val, 1);
    const p = ob.processing;
    sub = `큐 대기 <b class="${ob.pending >= 3 ? 'bad' : ''}">${ob.pending}</b>건` + (p ? ` · 처리 중: <b>${esc(shortName(p.authorId))}</b> ${fmt(p.delivered)}/${fmt(p.total)}` : ob.pending === 0 && val != null ? ' · 최근 완료 기준' : '');
  }
  card.classList.toggle('bad', val != null && val > 5);
  $('#propSub').innerHTML = sub;
  push(state.hist.prop, (v === 'v3' || v === 'v4') && ob.pending > 0 ? ob.oldestPendingMs / 1000 : 0);
  drawSpark($('#cardProp .spark'), state.hist.prop, val > 5 ? '#ea4335' : '#4285f4');

  // 4. feeds
  $('#feedsRows').textContent = fmt(db.feedsRows);
  $('#feedsSub').innerHTML = `<b>+${fmt(db.feedsRowsAdded)}</b>행 (초기화 이후) · ${fmt(db.feedsSizeMb)}MB`;
  push(state.hist.feeds, db.feedsRowsAdded);
  drawSpark($('#cardFeeds .spark'), state.hist.feeds, '#f29900');
}

// ---------------------------------------------------------------- 기록표
function ledger(v) { return (state.ledger[v] ||= {}); }
function saveLedger() { localStorage.setItem('feedlab.ledger', JSON.stringify(state.ledger)); renderLedger(); }
function renderLedger() {
  const cell = (val, text, bad) => val == null ? '<td class="none">·</td>' : `<td class="${bad ? 'bad' : 'good'}">${text}</td>`;
  $('#ledgerBody').innerHTML = Object.entries(VERSIONS).map(([v, meta]) => {
    const l = state.ledger[v] || {};
    return `<tr class="${v === state.version ? 'current' : ''}"><td><span class="vtag" style="background:${meta.color}">${v}</span> <small>${meta.name}</small></td>`
      + cell(l.p99, `${fmt(l.p99)}ms`, l.p99 > 500) + cell(l.rps, fmt(l.rps), l.rps < 270)
      + cell(l.celebWrite, `${fmt(l.celebWrite)}ms`, l.celebWrite > 1000)
      + cell(l.celebArrive, `${fmt(l.celebArrive, 1)}초`, l.celebArrive > 5) + cell(l.dArrive, `${fmt(l.dArrive, 1)}초`, l.dArrive > 5) + '</tr>';
  }).join('');
}

// ---------------------------------------------------------------- 차트
function setup(canvas) {
  const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== w * dpr || canvas.height !== h * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
  const ctx = canvas.getContext('2d'); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h);
  return { ctx, w, h };
}
function drawSpark(canvas, values, color) {
  const { ctx, w, h } = setup(canvas);
  if (values.length < 2) return;
  const max = Math.max(1e-9, ...values), n = HIST, step = w / (n - 1), x0 = w - (values.length - 1) * step;
  ctx.beginPath();
  values.forEach((v, i) => { const x = x0 + i * step, y = h - 2 - (v / max) * (h - 5); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
  ctx.strokeStyle = color; ctx.lineWidth = state.big ? 3.5 : 2; ctx.lineJoin = 'round'; ctx.stroke();
  ctx.lineTo(w, h); ctx.lineTo(x0, h); ctx.closePath(); ctx.fillStyle = color + '22'; ctx.fill();
}
function niceCeil(v) { const p = Math.pow(10, Math.floor(Math.log10(v))); for (const m of [1, 2, 5, 10]) if (v <= m * p) return m * p; return 10 * p; }
function drawScatter(canvas, points, { xMax, color, xLabel }) {
  const { ctx, w, h } = setup(canvas);
  const k = state.big ? Math.max(1.5, Math.min(2.4, w / 330)) : 1;   // 크게 보기 모드에서는 그림 크기에 맞춰 글자와 점을 키운다
  const L = 50 * k, R = 14, T = 10 * k, B = 20 * k, pw = w - L - R, ph = h - T - B;
  const yMax = niceCeil(Math.max(50, ...points.map((p) => p[1])) * 1.05);
  xMax = Math.max(xMax, ...points.map((p) => p[0]));
  ctx.font = `${11 * k}px -apple-system, sans-serif`; ctx.fillStyle = '#8b93a1'; ctx.strokeStyle = '#eceff4'; ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = T + ph - (i / 4) * ph; ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke();
    ctx.textAlign = 'right'; ctx.fillText(fmt(yMax * i / 4), L - 6, y + 4 * k);
  }
  for (let i = 0; i <= 4; i++) { if (state.big && w < 480 && i % 2) continue; ctx.textAlign = i === 4 ? 'right' : i === 0 ? 'left' : 'center'; ctx.fillText(fmt(xMax * i / 4), L + (i / 4) * pw, h - 5); }
  ctx.textAlign = 'right'; ctx.fillText(`지연(ms) ↑   ${xLabel} →`, w - R, T + 8 * k);
  if (!points.length) { ctx.textAlign = 'center'; ctx.fillStyle = '#b6bcc8'; ctx.fillText('부하를 걸면 점이 찍힙니다', L + pw / 2, T + ph / 2); return; }
  for (const p of points) {
    const x = L + (p[0] / xMax) * pw, y = T + ph - Math.min(1, p[1] / yMax) * ph;
    if (p[2]) { ctx.globalAlpha = 0.55; ctx.fillStyle = color(p); ctx.beginPath(); ctx.arc(x, y, 2.6 * k, 0, 6.3); ctx.fill(); }
    else { ctx.globalAlpha = 0.8; ctx.strokeStyle = '#b3261e'; ctx.lineWidth = 1.4 * k; const r = 3 * k; ctx.beginPath(); ctx.moveTo(x - r, y - r); ctx.lineTo(x + r, y + r); ctx.moveTo(x + r, y - r); ctx.lineTo(x - r, y + r); ctx.stroke(); }
  }
  ctx.globalAlpha = 1;
}

// ---------------------------------------------------------------- 코드 서랍: 서버가 실제 소스 파일을 그대로 내려준다
const KOTLIN = /\b(class|fun|val|var|override|private|return|if|else|for|while|in|data|companion|object|true|false|null|try|finally|interface)\b/g;
const SQL = /\b(SELECT|FROM|JOIN LATERAL|JOIN|WHERE|ORDER BY|LIMIT|INSERT INTO|VALUES|ON CONFLICT DO NOTHING|UPDATE|SET|AND|ANY|ON|AS|DESC|RETURNING)\b/g;
function highlight(src) {
  return esc(src).replace(/(\/\*[\s\S]*?\*\/|\/\/[^\n]*)|("""[\s\S]*?""")|("(?:[^"\\\n]|\\.)*")|(@\w+)/g, (all, comment, sql, str, anno) => {
    if (comment) return `<span class="c">${comment}</span>`;
    if (sql) return `<span class="s">${sql.replace(/--[^\n]*/g, (c) => `<span class="c">${c}</span>`).replace(SQL, '<span class="q">$1</span>')}</span>`;
    if (str) return `<span class="s">${str.replace(SQL, '<span class="q">$1</span>')}</span>`;
    return `<span class="a">${anno}</span>`;
  }).replace(/(<span[\s\S]*?<\/span>)|\b(class|fun|val|var|override|private|return|if|else|for|while|data|companion|object|true|false|null|try|finally|interface)\b/g, (all, span, kw) => span || `<span class="k">${kw}</span>`);
}
async function showCode(file) {
  state.codeFile = file;
  const meta = VERSIONS[state.version];
  $('#codeTitle').textContent = `${state.version} · ${meta.name}`;
  $('#codeTabs').innerHTML = meta.files.map((f) => `<button data-file="${f}" class="${f === file ? 'on' : ''}">${f}</button>`).join('');
  const res = await fetch(`/code/${file}`);
  const src = (await res.text()).split('\n').filter((l) => !/^(package|import) /.test(l)).join('\n').replace(/^\n+/, '');
  $('#code').innerHTML = highlight(src); $('#code').scrollTop = 0;
}
// 지표 크게 보기: 조작 패널과 내 피드를 접고, 상황판을 화면 가득 키운다. 시연 버튼과 관찰자 패널은 남긴다.
function setBig(on) {
  state.big = on; document.body.classList.toggle('bigmode', on); $('#btnBig').classList.toggle('on', on);
  try { localStorage.setItem('feedlab.big', on ? '1' : ''); } catch { /* 무시 */ }
  if (on) toggleCode(false);
}
function toggleCode(force) {
  const d = $('#codeDrawer'); d.hidden = force != null ? !force : !d.hidden;
  if (!d.hidden && state.big) setBig(false);         // 코드는 넓은 자리가 필요하다
  $('#btnCode').classList.toggle('on', !d.hidden);
  if (!d.hidden) showCode(VERSIONS[state.version].files[0]);
}

// ---------------------------------------------------------------- 시작
async function init() {
  const cfg = await api('/config'); state.config = cfg.data || {};
  state.loadgen = `${location.protocol}//${location.hostname}:${state.config.loadgenPort || 8081}`;
  const ps = await api('/personas');
  for (const u of ps.data || []) state.users[u.id] = u;
  C.E = (ps.data || []).find((u) => u.name.startsWith('E'))?.id || C.E;   // 관찰자는 맨 끝 id (가장 늦게 받는 팔로워)
  $('#account').innerHTML = (ps.data || []).map((u) => `<option value="${u.id}">${esc(u.name)} (id ${u.id})</option>`).join('');

  // 새로고침해도 시연 상태가 이어지도록 loadgen 의 현재 설정을 가져온다
  const ctl = await api('/control', { base: state.loadgen });
  if (ctl.ok) {
    state.load = { readRps: ctl.data.read.targetRps, mix: ctl.data.read.mix, writeRps: ctl.data.write.targetRps };
    const preset = Object.entries(PRESETS).find(([, p]) => p.readRps === state.load.readRps && p.mix === state.load.mix && p.writeRps === state.load.writeRps)?.[0] || null;
    setVersion(ctl.data.version, false);
    applyLoad(state.load, preset);
  } else setVersion('v1', false);

  $('#versions').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) setVersion(b.dataset.v); });
  $('#presets').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) applyLoad(PRESETS[b.dataset.preset], b.dataset.preset); });
  $('#account').addEventListener('change', (e) => loadAccount(Number(e.target.value)));
  $('#btnCustom').onclick = () => { const id = Number($('#customId').value); if (id) loadAccount(id); };
  $('#btnRefresh').onclick = refreshMyFeed;
  $('#btnWrite').onclick = async (e) => { e.target.disabled = true; const r = await writePost(state.me, $('#content').value || `${shortName(state.me)}: 방금 쓴 글 (${stamp()})`); $('#writeResult').innerHTML = r.ok ? `응답 <b class="${r.ms > 1000 ? 'bad' : 'good'}">${fmt(r.ms)}ms</b> · 서버 ${fmt(r.serverMs)}ms` : `<span class="bad">실패 (HTTP ${r.status})</span>`; $('#content').value = ''; e.target.disabled = false; };
  $('#btnCeleb').onclick = () => writePost(C.C, `C 셀럽: 방금 올린 글 (${stamp()})`);
  $('#btnD').onclick = () => writePost(C.D, `D: 평범한 내 글 (${stamp()})`);
  $('#btnBurst').onclick = burst;
  $('#btnBurstD').onclick = () => { burst(); setTimeout(() => writePost(C.D, `D: 셀럽들 바로 뒤에 쓴 글 (${stamp()})`), 1000); };
  $('#btnFollow').onclick = () => follow('POST'); $('#btnUnfollow').onclick = () => follow('DELETE');
  // 슬라이더와 숫자 입력은 서로 따라간다. 숫자는 슬라이더 범위를 넘겨도 된다 (수만 rps 실험용).
  for (const k of ['readRps', 'writeRps']) {
    $('#' + k).oninput = (e) => { $('#' + k + 'Num').value = e.target.value; };
    $('#' + k + 'Num').oninput = (e) => { $('#' + k).value = Math.min(Number(e.target.value) || 0, Number($('#' + k).max)); };
    $('#' + k + 'Num').onkeydown = (e) => { if (e.key === 'Enter') $('#btnApply').click(); };
  }
  $('#btnApply').onclick = () => applyLoad({ readRps: Math.max(0, Number($('#readRpsNum').value) || 0), mix: $('#mix').value, writeRps: Math.max(0, Number($('#writeRpsNum').value) || 0) });
  $('#btnCode').onclick = () => toggleCode(); $('#btnCodeClose').onclick = () => toggleCode(false);
  $('#btnBig').onclick = () => setBig(!state.big);
  setBig(new URLSearchParams(location.search).has('big') || localStorage.getItem('feedlab.big') === '1');
  const codeFont = (d) => { const px = Math.min(26, Math.max(11, Number(localStorage.getItem('feedlab.codeFont') || 15) + d)); localStorage.setItem('feedlab.codeFont', px); document.documentElement.style.setProperty('--code-size', px + 'px'); };
  codeFont(0); $('#btnFontDown').onclick = () => codeFont(-1); $('#btnFontUp').onclick = () => codeFont(1);
  $('#codeTabs').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) showCode(b.dataset.file); });
  $('#btnLedgerClear').onclick = () => { state.ledger = {}; saveLedger(); };
  $('#btnReset').onclick = async (e) => {
    e.target.disabled = true; toast('초기화 중… (시드 이후 데이터 삭제)', '', 3000);
    const r = await api('/admin/reset', { method: 'POST' }); e.target.disabled = false;
    if (r.ok && r.data.ok) { state.obs = { seen: new Set(), badges: new Map(), firstSeenAt: new Map(), primed: false }; state.lastProp = null; state.lastManual = null; toast(`초기화 완료 · 글 ${fmt(r.data.deletedPosts)}개, feeds ${fmt(r.data.deletedFeeds)}행 삭제 (${fmt(r.data.tookMs)}ms)`, 'good'); refreshMyFeed(); }
    else toast(`초기화 실패: ${r.data?.reason || r.status}`, 'bad');
  };
  document.addEventListener('keydown', (e) => {
    if (/INPUT|TEXTAREA|SELECT/.test(e.target.tagName) || e.metaKey || e.ctrlKey) return;
    if (/^[1-4]$/.test(e.key)) setVersion('v' + e.key);
    if (e.key === 'c' || e.key === 'C') toggleCode();
    if (e.key === 'm' || e.key === 'M') setBig(!state.big);
    const act = { q: '#btnCeleb', w: '#btnD', e: '#btnBurst', r: '#btnBurstD' }[e.key.toLowerCase()];   // 지표 크게 보기에서는 버튼이 없으므로 키로 누른다
    if (act) $(act).click();
    if (e.key === 'Escape') toggleCode(false);
  });

  const showConfig = (c) => { state.config = c; $('#batchSize').value = String(c.fanoutBatchSize); $('#threshold').value = c.hybridThreshold; $('#configMeta').innerHTML = `지금 셀럽으로 분류된 유저 <b>${c.celebCount}</b>명`; };
  const saveConfig = async (body) => { const r = await api('/config', { method: 'POST', body }); if (r.ok) { showConfig(r.data); toast(`설정 변경: 묶음 ${fmt(r.data.fanoutBatchSize)}행 · 셀럽 기준 ${fmt(r.data.hybridThreshold)}명 초과`, '', 3000); } };
  if (![...$('#batchSize').options].some((o) => o.value === String(state.config.fanoutBatchSize))) $('#batchSize').add(new Option(String(state.config.fanoutBatchSize), String(state.config.fanoutBatchSize)));
  showConfig(state.config);
  $('#batchSize').onchange = (e) => saveConfig({ fanoutBatchSize: Number(e.target.value) });
  $('#threshold').onchange = (e) => saveConfig({ hybridThreshold: Number(e.target.value) });

  renderLedger();
  await loadAccount(C.A);
  observerTick(); pollTick();
}
async function follow(method) {
  const target = Number($('#followTarget').value); if (!target) return;
  const r = await api(`/follows/${target}`, { method, user: state.me });
  $('#followResult').innerHTML = r.ok ? `${esc(userName(target))} ${method === 'POST' ? '팔로우' : '언팔로우'} ${r.data.changed ? '완료' : '(변화 없음)'}` : `<span class="bad">실패</span>`;
  loadAccount(state.me);
}
init();
