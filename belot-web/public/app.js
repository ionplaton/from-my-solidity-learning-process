// Belot web client: works as a Telegram Mini App and as a normal web page.
import { cardFace, cardBack, suitIcon, SUIT_TITLE } from './cards.js';
import { RULES_SHORT, RULES_TEXT } from './rules.js';

const tg = window.Telegram?.WebApp;
const inTelegram = Boolean(tg && tg.initData);
const CONFIG = window.BELOT_CONFIG || {};
const app = document.getElementById('app');
const SUITS = ['S', 'H', 'C', 'D'];
const TARGETS = [301, 501, 1001];

// ---------------------------------------------------------------- helpers

function tgCall(fn, ...args) {
  try {
    return tg?.[fn]?.(...args);
  } catch {
    return undefined;
  }
}

function h(tag, props = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'style') Object.assign(e.style, v);
    else if (k === 'text') e.textContent = v;
    else if (k === 'html') e.innerHTML = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v === true) e.setAttribute(k, '');
    else e.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return e;
}

const px = (v) => `${Math.round(v)}px`;

let toastTimer;
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, value);
  } catch {
    /* storage may be unavailable */
  }
  return null;
}

function confirmAction(text, onYes) {
  if (inTelegram && tg.showConfirm) tgCall('showConfirm', text, (ok) => ok && onYes());
  else if (window.confirm(text)) onYes();
}

function buzz() {
  try {
    if (inTelegram && tg.HapticFeedback) tg.HapticFeedback.notificationOccurred('success');
    else navigator.vibrate?.(60);
  } catch {
    /* ignore */
  }
}

function openLink(url) {
  if (inTelegram) {
    if (url.startsWith('https://t.me/')) tgCall('openTelegramLink', url);
    else tgCall('openLink', url);
  } else {
    window.open(url, '_blank', 'noopener');
  }
}

// ---------------------------------------------------------------- identity

const tgUser = tg?.initDataUnsafe?.user;
let pid = tgUser ? `tg${tgUser.id}` : store('belot.pid');
if (!pid) {
  pid = `w${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  store('belot.pid', pid);
}
let playerName = store('belot.name') || (tgUser ? `${tgUser.first_name || ''}`.trim().slice(0, 16) : '');

// ---------------------------------------------------------------- state & connection

const S = {
  screen: 'menu', // menu | connecting | lobby | game | rules
  state: null,
  room: null,
  selected: null,
  practice: 0,
  connLost: false,
  sheet: null,
  prefillCode: '',
};

let ws = null;
let retries = 0;
let reconnectTimer = null;
let pendingFirst = null;

function connect(first) {
  closeSocket();
  pendingFirst = first;
  const sock = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`);
  ws = sock;
  sock.onopen = () => {
    retries = 0;
    sock.send(JSON.stringify(first));
  };
  sock.onmessage = (e) => {
    if (sock !== ws) return;
    let m;
    try {
      m = JSON.parse(e.data);
    } catch {
      return;
    }
    onMessage(m);
  };
  sock.onclose = () => {
    if (sock !== ws) return;
    ws = null;
    onSocketClosed();
  };
}

function closeSocket() {
  clearTimeout(reconnectTimer);
  const s = ws;
  ws = null;
  if (s) {
    try {
      s.close();
    } catch {
      /* ignore */
    }
  }
}

function send(msg) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
  else toast('Нет связи с сервером…');
}

function onSocketClosed() {
  const delay = Math.min(10000, 1000 * 2 ** retries);
  retries++;
  if (S.room) {
    S.connLost = true;
    render();
    reconnectTimer = setTimeout(() => connect({ t: 'join', room: S.room, name: playerName, pid }), delay);
  } else if (S.screen === 'connecting') {
    // The free server may be waking up: keep trying for about a minute.
    if (retries <= 7) reconnectTimer = setTimeout(() => connect(pendingFirst), delay);
    else {
      toast('Не удалось подключиться к серверу');
      showMenu();
    }
  }
}

function onMessage(m) {
  if (m.t === 'state') {
    const prev = S.state?.game;
    S.connLost = false;
    S.state = m;
    S.room = m.room;
    setUrlRoom(m.room);
    if (S.practice && m.you === m.hostSeat && !m.game) {
      const n = S.practice;
      S.practice = 0;
      send({ t: 'rules', rules: { ...m.rules, players: n } });
      for (let i = 1; i < n; i++) send({ t: 'addbot' });
      send({ t: 'start' });
    }
    const g = m.game;
    const myTurn = g && g.current === g.seat;
    const wasMyTurn = prev && prev.current === prev.seat && prev.phase === g?.phase;
    if (myTurn && !wasMyTurn) buzz();
    if (!g || g.phase !== 'PLAYING' || !myTurn || !g.hand.includes(S.selected)) S.selected = null;
    if (S.screen !== 'rules') S.screen = g ? 'game' : 'lobby';
    render();
  } else if (m.t === 'error') {
    toast(m.msg);
  } else if (m.t === 'closed') {
    S.room = null;
    S.state = null;
    closeSocket();
    setUrlRoom(null);
    toast(m.msg);
    showMenu();
  }
}

function setUrlRoom(code) {
  if (inTelegram) return;
  try {
    const url = new URL(location.href);
    if (code) url.searchParams.set('room', code);
    else url.searchParams.delete('room');
    history.replaceState(null, '', url);
  } catch {
    /* ignore */
  }
}

// Keep the (free) server awake while a table is open.
setInterval(() => {
  if (S.room) fetch('/healthz', { cache: 'no-store' }).catch(() => {});
}, 4 * 60 * 1000);

// ---------------------------------------------------------------- actions

function takeName() {
  const input = document.getElementById('name');
  const n = (input ? input.value : playerName).trim().slice(0, 16);
  if (!n) {
    toast('Введите имя');
    input?.focus();
    return null;
  }
  playerName = n;
  store('belot.name', n);
  return n;
}

function createTable(practice = 0) {
  if (!takeName()) return;
  S.practice = practice;
  S.room = null;
  S.screen = 'connecting';
  retries = 0;
  connect({ t: 'create', name: playerName, pid });
  render();
}

function joinTable(code) {
  code = String(code || '').trim().toUpperCase();
  if (!code) return toast('Введите код стола');
  if (!takeName()) return;
  S.room = null;
  S.screen = 'connecting';
  retries = 0;
  connect({ t: 'join', room: code, name: playerName, pid });
  render();
}

function leaveTable() {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ t: 'bye' }));
  S.room = null;
  S.state = null;
  closeSocket();
  setUrlRoom(null);
  showMenu();
}

function showMenu(prefill = '') {
  S.screen = 'menu';
  S.prefillCode = prefill;
  S.sheet = null;
  render();
}

function inviteLink(code) {
  if (CONFIG.botUsername && CONFIG.appName) return `https://t.me/${CONFIG.botUsername}/${CONFIG.appName}?startapp=${code}`;
  if (CONFIG.botUsername) return `https://t.me/${CONFIG.botUsername}?startapp=${code}`;
  return `${location.origin}/?room=${code}`;
}

function invite(kind) {
  const code = S.room;
  const link = inviteLink(code);
  const text = `Садись за стол белота! Код стола: ${code}`;
  if (kind === 'telegram') {
    openLink(`https://t.me/share/url?url=${encodeURIComponent(link)}&text=${encodeURIComponent(text)}`);
  } else if (kind === 'whatsapp') {
    openLink(`https://wa.me/?text=${encodeURIComponent(`${text}\n${link}`)}`);
  } else {
    const done = () => toast('Ссылка скопирована');
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(link).then(done, () => toast(link));
    else toast(link);
  }
}

function onBack() {
  if (S.sheet) {
    S.sheet = null;
    render();
  } else if (S.screen === 'rules') {
    S.screen = S.state ? (S.state.game ? 'game' : 'lobby') : 'menu';
    render();
  } else if (S.screen === 'connecting') {
    S.room = null;
    closeSocket();
    showMenu();
  } else if (S.screen === 'lobby' || S.screen === 'game') {
    confirmAction('Выйти из-за стола?', leaveTable);
  }
}

// ---------------------------------------------------------------- rendering

function render() {
  if (inTelegram) {
    if (S.screen === 'menu') tg.BackButton.hide();
    else tg.BackButton.show();
  }
  app.replaceChildren();
  const view = {
    menu: renderMenu,
    connecting: renderConnecting,
    lobby: renderLobby,
    game: renderGame,
    rules: renderRules,
  }[S.screen];
  app.append(view());
  if (S.sheet) app.append(renderSheet());
}

function renderMenu() {
  const codeInput = h('input', { type: 'text', id: 'code', placeholder: 'Код стола', value: S.prefillCode, autocapitalize: 'characters', maxlength: 8 });
  return h(
    'div',
    { class: 'screen' },
    h('h1', { text: 'Белот' }),
    h('p', { class: 'subtitle', text: 'вдвоём, втроём, вчетвером' }),
    h('p', { class: 'hint', text: 'Ваше имя', style: { marginTop: '20px' } }),
    h('input', { type: 'text', id: 'name', placeholder: 'Например, Ион', value: playerName, maxlength: 16 }),
    h('button', { text: 'Создать стол', onclick: () => createTable(0), style: { marginTop: '12px' } }),
    h('h3', { text: 'Войти за стол по коду' }),
    h('div', { class: 'row' }, codeInput, h('button', { class: 'fixed', text: 'Войти', onclick: () => joinTable(codeInput.value) })),
    h('p', { class: 'hint', text: 'Тренировка с ботами:', style: { marginTop: '12px' } }),
    h(
      'div',
      { class: 'row' },
      [2, 3, 4].map((n) => h('button', { class: 'secondary', text: playersLabel(n), onclick: () => createTable(n) })),
    ),
    h('button', { class: 'secondary', text: 'Правила', onclick: () => ((S.screen = 'rules'), render()) }),
    h('p', {
      class: 'hint',
      style: { marginTop: '16px' },
      text: 'Создайте стол и отправьте ссылку друзьям в Telegram или WhatsApp — они сядут за стол, где бы ни были.',
    }),
  );
}

function renderConnecting() {
  return h(
    'div',
    { class: 'screen center' },
    h('div', { class: 'spinner' }),
    h('h2', { text: 'Подключаемся…' }),
    h('p', { class: 'hint', text: 'Если сервер долго не использовался, он просыпается — это может занять до минуты.' }),
    h('button', { class: 'secondary', text: 'Отмена', onclick: onBack, style: { marginTop: '16px' } }),
  );
}

function renderRules() {
  return h(
    'div',
    { class: 'screen' },
    h('h2', { text: 'Правила белота' }),
    h('div', { class: 'rules-text', text: RULES_TEXT }),
    h('button', { class: 'secondary', text: 'Назад', onclick: onBack }),
  );
}

function playersLabel(n) {
  return n === 2 ? 'Двое' : n === 3 ? 'Трое' : 'Четверо';
}

function tableDescription(n) {
  if (n === 2) return 'Один на один, 24 карты (от девятки), по 12 карт.';
  if (n === 3) return 'Каждый сам за себя, 24 карты (от девятки), по 8 карт.';
  return 'Двое на двое, 32 карты (от семёрки), по 8 карт.';
}

function rulesSummary(r) {
  return [
    `Игра до ${r.target} очков.`,
    r.players !== 3
      ? 'Играющие должны набрать больше соперников.'
      : r.beatEach
        ? 'Взявший должен набрать больше каждого соперника.'
        : 'Взявший должен набрать больше половины очков.',
    r.underTrump
      ? 'Без масти козырь класть обязательно.'
      : 'Без масти, если нельзя перебить козырь, можно класть любую карту.',
  ].join('\n');
}

function renderLobby() {
  const st = S.state;
  const rules = st.rules;
  const isHost = st.you === st.hostSeat;
  const sendRules = (patch) => send({ t: 'rules', rules: { ...rules, ...patch } });
  const hostName = st.players[st.hostSeat]?.name ?? '—';
  const c = h('div', { class: 'screen' });

  c.append(
    h('h2', { text: `Стол «${hostName}»` }),
    h('div', { class: 'code', text: st.room }),
    h('p', { class: 'hint', text: 'Пригласите друзей — отправьте им ссылку на этот стол:' }),
    h('button', { text: 'Пригласить в Telegram', onclick: () => invite('telegram') }),
    h(
      'div',
      { class: 'row' },
      h('button', { class: 'secondary', text: 'В WhatsApp', onclick: () => invite('whatsapp') }),
      h('button', { class: 'secondary', text: 'Копировать ссылку', onclick: () => invite('copy') }),
    ),
  );

  c.append(h('h3', { text: 'Сколько игроков' }));
  if (isHost) {
    c.append(
      h(
        'div',
        { class: 'row' },
        [2, 3, 4].map((n) =>
          h('button', { class: n === rules.players ? '' : 'secondary', text: playersLabel(n), onclick: () => sendRules({ players: n }) }),
        ),
      ),
    );
  }
  c.append(h('p', { class: 'hint', text: tableDescription(rules.players) }));

  c.append(h('h3', { text: 'Игроки' }));
  if (rules.players === 4) {
    c.append(h('p', { class: 'hint', text: 'Команды: места 1 и 3 (жёлтые) против мест 2 и 4 (голубые). Партнёры сидят напротив.' }));
  }
  st.players.forEach((p, i) => {
    const color = rules.players === 4 ? (i % 2 === 0 ? 'var(--team1)' : 'var(--team2)') : 'var(--text)';
    const tags = p
      ? [i === st.you && 'вы', i === st.hostSeat && 'создатель', p.bot && 'бот', !p.online && 'нет связи'].filter(Boolean)
      : [];
    const label = p ? `${i + 1}. ${p.name}${tags.length ? ` (${tags.join(', ')})` : ''}` : `${i + 1}. свободно`;
    const row = h('div', { class: `seat${p ? '' : ' empty'}` }, h('span', { class: 'name', text: label, style: { color } }));
    if (isHost) {
      if (!p) row.append(h('button', { class: 'secondary small', text: '+ бот', onclick: () => send({ t: 'addbot' }) }));
      else if (i !== st.hostSeat) row.append(h('button', { class: 'secondary small', text: 'Убрать', onclick: () => send({ t: 'remove', seat: i }) }));
      if (st.players.length > 2) {
        row.append(
          h('button', {
            class: 'secondary small',
            text: '↓',
            title: 'Поменять местами со следующим',
            onclick: () => send({ t: 'swap', a: i, b: (i + 1) % st.players.length }),
          }),
        );
      }
    }
    c.append(row);
  });
  if (isHost && st.players.length > 2) c.append(h('p', { class: 'hint', text: '«↓» — поменять игрока местами со следующим.' }));

  c.append(h('h3', { text: 'Правила стола' }));
  if (isHost) {
    c.append(
      h('p', { class: 'hint', text: 'Игра до:' }),
      h(
        'div',
        { class: 'row' },
        TARGETS.map((t) => h('button', { class: t === rules.target ? '' : 'secondary', text: String(t), onclick: () => sendRules({ target: t }) })),
      ),
    );
    if (rules.players === 3) {
      c.append(
        checkbox('Взявший козырь должен набрать больше КАЖДОГО соперника (если выключено — больше половины всех очков)', rules.beatEach, (v) =>
          sendRules({ beatEach: v }),
        ),
      );
    }
    c.append(checkbox('Нет масти и нельзя перебить козырь — всё равно класть козырь', rules.underTrump, (v) => sendRules({ underTrump: v })));
    const full = st.players.every(Boolean);
    c.append(h('button', { text: 'Начать игру', disabled: !full, onclick: () => send({ t: 'start' }), style: { marginTop: '12px' } }));
    if (!full) c.append(h('p', { class: 'hint', text: `Нужно игроков: ${rules.players}. Можно добавить ботов.` }));
  } else {
    c.append(h('p', { class: 'hint rules-text', text: rulesSummary(rules) }));
    c.append(h('p', { class: 'hint', text: 'Ждём, пока создатель стола начнёт игру.' }));
  }
  c.append(h('button', { class: 'secondary', text: 'Выйти', onclick: () => confirmAction('Выйти из-за стола?', leaveTable) }));
  if (S.connLost) c.prepend(h('div', { class: 'hint', text: 'Связь потеряна, переподключаемся…', style: { color: '#ff8a80' } }));
  return c;
}

function checkbox(text, checked, onChange) {
  const input = h('input', { type: 'checkbox', checked: Boolean(checked) });
  input.addEventListener('change', () => onChange(input.checked));
  return h('label', { class: 'check' }, input, h('span', { text }));
}

function renderSheet() {
  const close = () => ((S.sheet = null), render());
  const sheet = h('div', { class: 'sheet' });
  if (S.sheet === 'menu') {
    sheet.append(
      h('button', { class: 'secondary', text: 'Правила (кратко)', onclick: () => ((S.sheet = 'rules'), render()) }),
      h('button', { class: 'secondary', text: 'Пригласить по ссылке', onclick: () => invite(inTelegram ? 'telegram' : 'copy') }),
      h('button', { class: 'danger', text: 'Выйти из игры', onclick: () => confirmAction('Выйти из игры?', leaveTable) }),
      h('button', { class: 'secondary', text: 'Закрыть', onclick: close }),
    );
  } else if (S.sheet === 'rules') {
    sheet.append(h('div', { class: 'rules-text', text: RULES_SHORT }), h('button', { class: 'secondary', text: 'Закрыть', onclick: close }));
  }
  return h('div', { class: 'sheet-bg', onclick: (e) => e.target === e.currentTarget && close() }, sheet);
}

// ---------------------------------------------------------------- game table

const sideOf = (g, p) => (g.n === 4 ? p % 2 : p);
const sideCount = (g) => (g.n === 4 ? 2 : g.n);
const sideMembers = (g, side) => [...Array(g.n).keys()].filter((p) => sideOf(g, p) === side);

function nameOf(st, seat) {
  return st.players[seat]?.name ?? '—';
}

function sideName(st, g, side) {
  return sideMembers(g, side)
    .sort((a, b) => (a === g.seat ? -1 : b === g.seat ? 1 : a - b))
    .map((p) => (p === g.seat ? 'Вы' : nameOf(st, p)))
    .join(' и ');
}

function viewportHeight() {
  if (inTelegram && tg.viewportStableHeight) return tg.viewportStableHeight;
  return window.innerHeight;
}

function badges(g, seat) {
  const b = [];
  if (g.n === 4 && seat !== g.seat && sideOf(g, seat) === sideOf(g, g.seat)) b.push('партнёр');
  if (g.dealer === seat) b.push('сдаёт');
  if (g.taker === seat) b.push('играет');
  if (g.belote === seat) b.push('бела');
  return b;
}

function cardEl(code, x, y, w, opts = {}) {
  return h('div', {
    class: `card${opts.faded ? ' faded' : ''}${opts.extraClass ? ` ${opts.extraClass}` : ''}`,
    style: { left: px(x), top: px(y), width: px(w), height: px(w * 1.45) },
    html: code ? cardFace(code, opts) : cardBack(),
    onclick: opts.onclick,
  });
}

function renderGame() {
  const st = S.state;
  const g = st.game;
  const me = g.seat;
  const n = g.n;
  const W = Math.min(window.innerWidth, 560);
  const H = Math.max(480, viewportHeight());
  const table = h('div', { id: 'table', style: { width: '100%', height: px(H) } });

  const cw = Math.min(W / 3.9, (H * 0.19) / 1.45);
  const tw = Math.min(W / 4.6, (H * 0.15) / 1.45);
  const sw = Math.min(W / 10, (H * 0.075) / 1.45);
  const topBar = 48;
  const handTopFromBottom = cw * 1.45 + H * 0.02 + 30;

  // Top bar.
  const trumpBox = h('div', { class: 'trump' });
  if (g.trump) {
    const who = g.taker === me ? 'вы' : nameOf(st, g.taker);
    trumpBox.append(
      h('div', {}, h('b', { text: 'Козырь' }), h('small', { text: `играет: ${who}` })),
      h('div', { class: 'trump-badge', html: suitIcon(g.trump) }),
    );
  } else {
    trumpBox.append(h('b', { text: 'Козырь' }), h('div', { class: 'trump-badge', text: '?' }));
  }
  table.append(
    h(
      'div',
      { class: 'topbar' },
      h('button', { class: 'menu-btn', text: '☰', 'aria-label': 'Меню', onclick: () => ((S.sheet = 'menu'), render()) }),
      h('div', { class: 'topinfo' }, h('div', { text: `Раздача №${g.hand_no} · стол ${st.room}` }), h('small', { text: `игра до ${g.rules.target}` })),
      trumpBox,
    ),
  );

  // Opponents along the top in table order (the next player sits to the right).
  const oppY = topBar + 14;
  const pillH = 32;
  const colW = W / (n - 1);
  for (let r = n - 1; r >= 1; r--) {
    const seat = (me + r) % n;
    drawOpponent(table, st, g, seat, colW * (n - 1 - r + 0.5), oppY, colW, sw, pillH);
  }
  const oppBottom = oppY + pillH + 16 + sw * 1.45 + 36 + 18;

  // Recent events.
  table.append(
    h(
      'div',
      { class: 'log', style: { top: px(oppBottom - 6) } },
      g.log.slice(-2).map((line) => h('div', { text: line })),
    ),
  );

  const cx = W / 2;
  const handTop = H - handTopFromBottom;
  const cy = (oppBottom + 32 + handTop - 34) / 2;
  const th = tw * 1.45;

  if (g.phase === 'BIDDING') {
    table.append(h('div', { class: 'label-center bold', text: g.bid_round === 1 ? 'Открытая карта' : 'Торговля: второй круг', style: { left: px(cx), top: px(cy - th / 2 - 26) } }));
    table.append(cardEl(null, cx - tw * 1.05, cy - th / 2, tw));
    if (g.turned) table.append(cardEl(g.turned, cx + tw * 0.05, cy - th / 2, tw, { dim: g.bid_round === 2 }));
  } else {
    const showingLast = !g.trick.length && g.last_trick.length;
    const plays = showingLast ? g.last_trick : g.trick;
    for (const p of plays) {
      const [dx, dy] = trickOffset((p.p - me + n) % n, n, tw, th);
      table.append(cardEl(p.c, cx + dx - tw / 2, cy + dy - th / 2, tw, { faded: showingLast }));
    }
    if (showingLast && g.last_winner >= 0) {
      const label = g.last_winner === me ? 'Взятка ваша' : `Взятку взял(а) ${nameOf(st, g.last_winner)}`;
      table.append(h('div', { class: 'label-center', text: label, style: { left: px(cx), top: px(cy + th * 0.8 + 4) } }));
    }
  }

  // Status and own line.
  const infoY = handTop - 12;
  const myTurn = g.current === me;
  let status = '';
  let mine = false;
  if (g.phase === 'BIDDING' && !myTurn) status = `Решает ${nameOf(st, g.current)}…`;
  if (g.phase === 'PLAYING') {
    if (myTurn) {
      status = S.selected ? 'Нажмите ещё раз, чтобы сыграть' : 'Ваш ход';
      mine = true;
    } else if (st.players[g.current] && !st.players[g.current].online) status = `${nameOf(st, g.current)} не на связи…`;
    else status = `Ходит ${nameOf(st, g.current)}…`;
  }
  if (status) table.append(h('div', { class: `status${mine ? ' mine' : ''}`, text: status, style: { left: px(cx), top: px(infoY - (mine ? 50 : 40)) } }));
  const mySide = sideOf(g, me);
  const scoreParts = g.n === 4 ? [`Мы: ${g.totals[mySide]}`, `Они: ${g.totals[1 - mySide]}`] : [`Вы: ${g.totals[mySide]}`];
  if (g.phase !== 'BIDDING') scoreParts.push(`взяток ${g.tricks[me]}`);
  table.append(h('div', { class: 'myline', text: [...scoreParts, ...badges(g, me)].join(' · '), style: { top: px(infoY - 10) } }));

  // Own hand.
  const cards = g.hand;
  const hw = cards.length > 8 ? Math.min(cw, W / 4.4) : cw;
  const hh = hw * 1.45;
  const margin = 10;
  const step = cards.length > 1 ? Math.min(hw * 0.95, (W - 2 * margin - hw) / (cards.length - 1)) : 0;
  const startX = (W - (hw + step * (cards.length - 1))) / 2;
  const baseTop = H - hh - H * 0.02;
  const playing = g.phase === 'PLAYING' && myTurn;
  cards.forEach((code, i) => {
    const selected = code === S.selected;
    table.append(
      cardEl(code, startX + i * step, selected ? baseTop - hh * 0.18 : baseTop, hw, {
        dim: playing && !g.legal.includes(code),
        highlight: selected,
        extraClass: 'hand-card',
        onclick: () => onHandTap(code),
      }),
    );
    const el = table.lastChild;
    el.dataset.card = code;
    if (playing && g.legal.includes(code)) el.dataset.legal = '1';
  });

  // Overlays.
  if (g.phase === 'BIDDING' && myTurn) table.append(bidPanel(g, handTopFromBottom + 4));
  if ((g.phase === 'HAND_OVER' || g.phase === 'GAME_OVER') && g.result) table.append(resultOverlay(st, g));
  const offline = st.players.map((p, i) => (p && !p.online ? i : -1)).filter((i) => i >= 0);
  if (S.connLost) {
    table.append(h('div', { class: 'banner', text: 'Связь с сервером потеряна, переподключаемся…' }));
  } else if (offline.length) {
    const banner = h(
      'div',
      { class: 'banner' },
      h('div', { text: `Нет связи: ${offline.map((i) => nameOf(st, i)).join(', ')}. Игрок вернётся, если снова откроет ссылку на стол.` }),
    );
    if (st.you === st.hostSeat) {
      offline.forEach((i) =>
        banner.append(h('button', { class: 'secondary small', text: `Заменить ботом: ${nameOf(st, i)}`, onclick: () => send({ t: 'tobot', seat: i }) })),
      );
    }
    table.append(banner);
  }
  return table;
}

function trickOffset(rel, n, tw, th) {
  if (n === 2) return rel === 0 ? [0, th * 0.28] : [0, -th * 0.28];
  if (n === 3) return [[0, th * 0.28], [tw * 0.62, -th * 0.16], [-tw * 0.62, -th * 0.16]][rel];
  return [[0, th * 0.36], [tw * 0.95, 0], [0, -th * 0.36], [-tw * 0.95, 0]][rel];
}

function drawOpponent(table, st, g, seat, x, y, colW, sw, pillH) {
  const info = st.players[seat];
  const maxText = colW - 8;
  table.append(
    h('div', {
      class: `pill${g.current === seat ? ' active' : ''}`,
      text: nameOf(st, seat),
      style: { left: px(x), top: px(y), maxWidth: px(maxText) },
    }),
  );
  if (info && !info.online) {
    table.append(h('div', { class: 'opp-line offline', text: 'нет связи', style: { left: px(x), top: px(y + pillH + 1), maxWidth: px(maxText) } }));
  }
  const count = g.counts[seat];
  const sh = sw * 1.45;
  const step = Math.min(sw * 0.32, (maxText - sw) / Math.max(1, count - 1));
  const total = sw + step * Math.max(0, count - 1);
  const top = y + pillH + 16;
  for (let i = 0; i < count; i++) table.append(cardEl(null, x - total / 2 + i * step, top, sw));
  if (g.phase === 'BIDDING' && g.passed[seat]) {
    table.append(h('div', { class: 'passbubble', text: 'Пас', style: { left: px(x), top: px(top + sh / 2) } }));
  }
  const parts = [];
  if (g.n !== 4) parts.push(`очки: ${g.totals[sideOf(g, seat)]}`);
  if (g.phase !== 'BIDDING') parts.push(`взяток ${g.tricks[seat]}`);
  if (parts.length) {
    table.append(h('div', { class: 'opp-line', text: parts.join(' · '), style: { left: px(x), top: px(top + sh + 4), maxWidth: px(maxText) } }));
  }
  const b = badges(g, seat);
  if (b.length) {
    const partner = g.n === 4 && sideOf(g, seat) === sideOf(g, g.seat);
    table.append(
      h('div', {
        class: `opp-line ${partner ? 'partner' : 'badges'}`,
        text: b.join(' · '),
        style: { left: px(x), top: px(top + sh + 20), maxWidth: px(maxText) },
      }),
    );
  }
}

function onHandTap(code) {
  const g = S.state?.game;
  if (!g || g.phase !== 'PLAYING') return;
  if (g.current !== g.seat) return toast('Сейчас не ваш ход');
  if (!g.legal.includes(code)) return toast('Этой картой ходить нельзя: нужно ходить в масть или козырем');
  if (S.selected === code) {
    S.selected = null;
    send({ t: 'play', card: code });
  } else {
    S.selected = code;
  }
  render();
}

function bidPanel(g, bottom) {
  const panel = h('div', { class: 'panel', style: { bottom: px(bottom) } });
  const turned = g.turned;
  const bid = (take, suit) => {
    const m = { t: 'bid', take };
    if (suit) m.suit = suit;
    send(m);
  };
  if (g.bid_round === 1) {
    panel.append(
      h('h4', { text: `Козырь «${SUIT_TITLE[turned[1]]}» — берёте?` }),
      h('div', { class: 'row' }, h('button', { text: 'Беру', onclick: () => bid(true) }), h('button', { class: 'secondary', text: 'Пас', onclick: () => bid(false) })),
    );
  } else {
    panel.append(
      h('h4', { text: 'Назовите другой козырь или пас' }),
      h(
        'div',
        { class: 'suit-row' },
        SUITS.filter((s) => s !== turned[1]).map((s) =>
          h('button', { class: 'suit-btn', html: suitIcon(s), 'aria-label': SUIT_TITLE[s], onclick: () => bid(true, s) }),
        ),
        h('button', { class: 'secondary', text: 'Пас', onclick: () => bid(false) }),
      ),
    );
  }
  return panel;
}

function resultOverlay(st, g) {
  const r = g.result;
  const over = g.phase === 'GAME_OVER';
  const mySide = sideOf(g, g.seat);
  const panel = h('div', { class: 'panel' });
  let title = `Раздача №${g.hand_no} окончена`;
  if (over) {
    title =
      g.winner === mySide
        ? `Игра окончена. ${g.n === 4 ? 'Ваша команда победила!' : 'Вы победили!'}`
        : `Игра окончена. Победа: ${sideName(st, g, g.winner)}`;
  }
  panel.append(
    h('div', { class: `result-title${over ? ' win' : ''}`, text: title }),
    h('div', { class: 'hint', text: `Козырь: ${SUIT_TITLE[r.trump]}, играл(а) ${nameOf(st, r.taker)}` }),
  );
  const takerSide = sideOf(g, r.taker);
  for (let side = 0; side < sideCount(g); side++) {
    const parts = [`карты ${r.cards[side]}`];
    if (r.combo[side]) parts.push(`комбинации ${r.combo[side]}`);
    if (r.belote[side]) parts.push(`бела ${r.belote[side]}`);
    if (r.last[side]) parts.push(`последняя ${r.last[side]}`);
    if (r.capot[side]) parts.push(`все взятки ${r.capot[side]}`);
    const tag = side === takerSide ? (g.n === 4 ? ' (играли)' : ' (играл(а))') : '';
    panel.append(
      h(
        'div',
        { class: 'result-block' },
        h('b', { text: `${sideName(st, g, side)}${tag}: +${r.awarded[side]}` }),
        h('span', { text: `${parts.join(' + ')} = ${r.raw[side]}` }),
      ),
    );
  }
  const counted = g.decl.filter((d) => d.ok);
  if (counted.length) {
    const titleOf = (combo) => {
      const ranks = '789TJQKA';
      const top = combo.cards.reduce((a, c) => (ranks.indexOf(c[0]) > ranks.indexOf(a[0]) ? c : a));
      const label = { 7: '7', 8: '8', 9: '9', T: '10', J: 'В', Q: 'Д', K: 'К', A: 'Т' }[top[0]];
      if (combo.kind === 'CARRE') return `Каре ${label}`;
      const len = combo.cards.length;
      return `${len === 3 ? 'Терц' : len === 4 ? 'Пятьдесят' : 'Сто'} до ${label}${{ S: '♠', H: '♥', C: '♣', D: '♦' }[top[1]]}`;
    };
    panel.append(
      h('div', {
        class: 'hint',
        style: { marginTop: '10px' },
        text: `Комбинации: ${counted.map((d) => `${nameOf(st, d.p)} — ${titleOf(d.combo)} (${d.combo.pts})`).join(', ')}`,
      }),
    );
  }
  const takerName = sideName(st, g, takerSide);
  const tie = !r.made && sideCount(g) === 2 && r.raw[0] === r.raw[1];
  let verdict;
  if (r.made) verdict = `${takerName} — игра сыграна, каждый получает свои очки.`;
  else if (tie) verdict = `${takerName} — ничья по очкам (спорная игра): очки играющих сгорают, соперники получают свои.`;
  else if (sideCount(g) === 2) verdict = `${takerName} — не набрали больше соперника: все очки (кроме белы) уходят соперникам.`;
  else if (g.rules.beatEach) verdict = `${takerName} — не набрал(а) больше каждого соперника: очки (кроме белы) делятся между соперниками.`;
  else verdict = `${takerName} — не набрал(а) больше половины очков: очки (кроме белы) делятся между соперниками.`;
  panel.append(h('div', { class: `verdict ${r.made ? 'good' : 'bad'}`, text: verdict }));

  panel.append(h('b', { text: `Общий счёт (до ${g.rules.target}):`, style: { marginTop: '12px' } }));
  [...Array(sideCount(g)).keys()]
    .sort((a, b) => g.totals[b] - g.totals[a])
    .forEach((side) => panel.append(h('div', { text: `${sideName(st, g, side)} — ${g.totals[side]}` })));

  if (over) {
    if (st.you === st.hostSeat) panel.append(h('button', { text: 'Новая игра', onclick: () => send({ t: 'newgame' }), style: { marginTop: '14px' } }));
    else panel.append(h('div', { class: 'hint', text: 'Новую игру может начать создатель стола.', style: { marginTop: '14px' } }));
    panel.append(h('button', { class: 'secondary', text: 'Выйти в меню', onclick: () => confirmAction('Выйти из-за стола?', leaveTable) }));
  } else if (st.players[g.seat]?.ready) {
    const waiting = st.players.map((p, i) => (p && !p.ready ? nameOf(st, i) : null)).filter(Boolean);
    panel.append(h('div', { class: 'hint', text: `Ждём: ${waiting.join(', ')}`, style: { marginTop: '14px', textAlign: 'center' } }));
  } else {
    panel.append(h('button', { text: 'Дальше', onclick: () => send({ t: 'ready' }), style: { marginTop: '14px' } }));
  }
  return h('div', { class: 'overlay' }, panel);
}

// ---------------------------------------------------------------- start

if (tg) {
  tgCall('ready');
  tgCall('expand');
  tgCall('setHeaderColor', '#07311C');
  tgCall('setBackgroundColor', '#07311C');
  tgCall('disableVerticalSwipes');
  tg.BackButton?.onClick?.(onBack);
  tg.onEvent?.('viewportChanged', () => S.screen === 'game' && render());
}
window.addEventListener('resize', () => S.screen === 'game' && render());

const startCode = String(tg?.initDataUnsafe?.start_param || new URLSearchParams(location.search).get('room') || '')
  .trim()
  .toUpperCase();
if (startCode && playerName) joinTable(startCode);
else showMenu(startCode);
