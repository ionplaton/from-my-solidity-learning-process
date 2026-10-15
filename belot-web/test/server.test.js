// End-to-end: real HTTP/WebSocket server, clients that play the first legal card.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { createServer } from '../src/server.js';

function client(port, first, onState) {
  const ws = new WebSocket(`ws://localhost:${port}/ws`);
  const c = { ws, last: null, errors: [], closed: null };
  ws.on('open', () => ws.send(JSON.stringify(first)));
  ws.on('message', (data) => {
    const m = JSON.parse(data.toString());
    if (m.t === 'state') {
      c.last = m;
      onState?.(m, c);
    } else if (m.t === 'error') c.errors.push(m.msg);
    else if (m.t === 'closed') c.closed = m.msg;
  });
  c.send = (m) => ws.send(JSON.stringify(m));
  return c;
}

function autoplay(m, c) {
  const g = m.game;
  if (!g) return;
  if (g.phase === 'HAND_OVER') c.send({ t: 'ready' });
  if (g.current !== g.seat) return;
  if (g.phase === 'BIDDING') c.send(g.bid_round === 1 ? { t: 'bid', take: true } : { t: 'bid', take: true, suit: ['S', 'H', 'C', 'D'].find((s) => s !== g.turned[1]) });
  if (g.phase === 'PLAYING') c.send({ t: 'play', card: g.legal[0] });
}

const until = async (cond, ms = 20000) => {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error('timeout');
    await new Promise((r) => setTimeout(r, 20));
  }
};

test('four players: two humans and two bots finish a game; invitations, rejoin and refusals', async () => {
  const srv = createServer({ port: 0, botDelayMs: 1, log: () => {} });
  const port = await srv.listen();
  try {
    // static files and config
    const page = await fetch(`http://localhost:${port}/`).then((r) => r.text());
    assert.match(page, /telegram-web-app\.js/);
    assert.match(await fetch(`http://localhost:${port}/config.js`).then((r) => r.text()), /BELOT_CONFIG/);
    assert.equal((await fetch(`http://localhost:${port}/../package.json`)).status, 404);

    const host = client(port, { t: 'create', name: 'Ион', pid: 'p1' }, autoplay);
    await until(() => host.last);
    const code = host.last.room;
    assert.match(code, /^[A-Z0-9]{5}$/);
    assert.equal(host.last.rules.players, 4);

    const guest = client(port, { t: 'join', room: code, name: 'Мария', pid: 'p2' }, autoplay);
    await until(() => guest.last);
    guest.send({ t: 'addbot' });
    await until(() => guest.errors.length);

    host.send({ t: 'swap', a: 1, b: 2 }); // Мария becomes the partner (seat 2)
    await until(() => guest.last.you === 2);
    host.send({ t: 'addbot' });
    host.send({ t: 'addbot' });
    host.send({ t: 'rules', rules: { ...host.last.rules, target: 301 } });
    host.send({ t: 'start' });
    await until(() => host.last.game?.phase === 'GAME_OVER', 30000);
    const g = guest.last.game;
    assert.equal(g.n, 4);
    assert.equal(g.totals.length, 2);
    assert.ok(Math.max(...g.totals) >= 301);

    // A stranger cannot join a running game; a wrong code is reported.
    const late = client(port, { t: 'join', room: code, name: 'Пётр', pid: 'p3' });
    await until(() => late.closed);
    const wrong = client(port, { t: 'join', room: 'ZZZZZ', name: 'Пётр', pid: 'p3' });
    await until(() => wrong.closed);
    assert.match(wrong.closed, /не найден/);

    // Reconnect with the same player id keeps the seat.
    guest.ws.close();
    await until(() => host.last.players[2].online === false);
    const back = client(port, { t: 'join', room: code, name: 'Мария', pid: 'p2' });
    await until(() => back.last);
    assert.equal(back.last.you, 2);
    await until(() => host.last.players[2].online === true);
    [host, back, late, wrong].forEach((c) => c.ws.close());
  } finally {
    await srv.close();
  }
});

test('practice table: one human and a bot, two players', async () => {
  const srv = createServer({ port: 0, botDelayMs: 1, log: () => {} });
  const port = await srv.listen();
  try {
    let started = false;
    const me = client(port, { t: 'create', name: 'Ион', pid: 'x' }, (m, c) => {
      if (!started && !m.game) {
        started = true;
        c.send({ t: 'rules', rules: { ...m.rules, players: 2 } });
        c.send({ t: 'addbot' });
        c.send({ t: 'addbot' }); // no third seat
        c.send({ t: 'start' });
      }
      autoplay(m, c);
    });
    await until(() => me.last?.game?.phase === 'GAME_OVER', 30000);
    assert.equal(me.last.game.n, 2);
    assert.ok(me.errors.includes('Мест нет'));
    me.ws.close();
  } finally {
    await srv.close();
  }
});
