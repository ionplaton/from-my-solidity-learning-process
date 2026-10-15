// Game tables ("rooms") for the online server. Transport-agnostic: a connection is any object with
// send(obj) and close(). Mirrors the command set of the Android app's GameServer.kt.
import { BelotGame, GameError, decodeRules, defaultRules, encodeRules } from './engine.js';
import { chooseBid, chooseCard } from './bot.js';

const MAX_PLAYERS = 4;
const CODE_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

function cleanName(name) {
  const s = String(name ?? '')
    .replace(/[\u0000-\u001f<>]/g, '')
    .trim()
    .slice(0, 16);
  return s || 'Игрок';
}

export class Room {
  constructor(code, { botDelayMs = 900 } = {}) {
    this.code = code;
    this.botDelayMs = botDelayMs;
    this.seats = new Array(MAX_PLAYERS).fill(null); // { name, bot, pid, conn, ready }
    this.rules = defaultRules(4);
    this.hostPid = null;
    this.game = null;
    this.conns = new Set();
    this.botTimer = null;
    this.lastActivity = Date.now();
    this.closed = false;
  }

  get capacity() {
    return this.rules.players;
  }

  get active() {
    return this.seats.slice(0, this.capacity);
  }

  get hostSeat() {
    return this.active.findIndex((s) => s && s.pid === this.hostPid);
  }

  /** Humans currently connected to this table. */
  get onlineHumans() {
    return this.active.filter((s) => s && !s.bot && s.conn).length;
  }

  isHost(conn) {
    return conn.pid != null && conn.pid === this.hostPid;
  }

  /** Seats a connection. Returns false if the table has no place for it. */
  join(conn, { name, pid }) {
    this.lastActivity = Date.now();
    name = cleanName(name);
    pid = String(pid ?? '').slice(0, 64) || null;
    if (this.hostPid === null) this.hostPid = pid;

    let index = this.active.findIndex((s) => s && !s.bot && pid && s.pid === pid);
    if (index < 0 && this.game) {
      // Rejoin from another device: a disconnected seat with the same name.
      index = this.active.findIndex((s) => s && !s.bot && !s.conn && s.name === name);
    }
    if (index < 0 && !this.game) {
      index = this.active.findIndex((s) => s === null);
      if (index >= 0) this.seats[index] = { name: this.uniqueName(name), bot: false, pid, conn: null, ready: false };
    }
    if (index < 0) {
      conn.send({ t: 'closed', msg: this.game ? 'Игра за этим столом уже идёт' : 'Стол уже заполнен' });
      return false;
    }
    const seat = this.seats[index];
    if (seat.conn && seat.conn !== conn) {
      seat.conn.send({ t: 'closed', msg: 'Вы открыли этот стол на другом устройстве' });
      this.detach(seat.conn);
      seat.conn.close();
    }
    seat.conn = conn;
    seat.pid = pid ?? seat.pid;
    conn.room = this;
    conn.pid = seat.pid;
    this.conns.add(conn);
    this.changed();
    return true;
  }

  uniqueName(name) {
    const taken = new Set(this.seats.filter(Boolean).map((s) => s.name));
    if (!taken.has(name)) return name;
    let i = 2;
    while (taken.has(`${name} ${i}`)) i++;
    return `${name} ${i}`;
  }

  seatOf(conn) {
    return this.active.findIndex((s) => s && s.conn === conn);
  }

  detach(conn) {
    this.conns.delete(conn);
    const i = this.seats.findIndex((s) => s && s.conn === conn);
    if (i >= 0) this.seats[i].conn = null;
    conn.room = null;
  }

  /** Connection lost (the player may come back). */
  disconnect(conn) {
    if (conn.room !== this) return;
    this.detach(conn);
    this.changed();
  }

  /** The player left the table on purpose. */
  leave(conn) {
    if (conn.room !== this) return;
    const i = this.seatOf(conn);
    this.detach(conn);
    if (i >= 0 && !this.game) {
      this.seats[i] = null;
      if (conn.pid === this.hostPid) {
        const next = this.active.find((s) => s && !s.bot);
        this.hostPid = next ? next.pid : null;
      }
    }
    this.changed();
  }

  handle(conn, msg) {
    this.lastActivity = Date.now();
    try {
      this.command(conn, msg);
      this.changed();
    } catch (e) {
      if (e instanceof GameError) conn.send({ t: 'error', msg: e.message });
      else throw e;
    }
  }

  command(conn, msg) {
    const g = this.game;
    const seat = this.seatOf(conn);
    const need = (cond, text) => {
      if (!cond) throw new GameError(text);
    };
    need(seat >= 0, 'Вы не за столом');
    const host = this.isHost(conn);
    switch (msg.t) {
      case 'addbot': {
        need(host && !g, 'Только создатель стола может добавить бота');
        const free = this.active.findIndex((s) => s === null);
        need(free >= 0, 'Мест нет');
        let n = 1;
        while (this.seats.some((s) => s && s.name === `Бот ${n}`)) n++;
        this.seats[free] = { name: `Бот ${n}`, bot: true, pid: null, conn: null, ready: false };
        break;
      }
      case 'remove': {
        need(host && !g, 'Только создатель стола может убрать игрока');
        const i = Number(msg.seat);
        need(Number.isInteger(i) && i >= 0 && i < this.capacity && i !== this.hostSeat, 'Нельзя');
        const s = this.seats[i];
        if (s && s.conn) {
          s.conn.send({ t: 'closed', msg: 'Вас убрали со стола' });
          const c = s.conn;
          this.detach(c);
          c.close();
        }
        this.seats[i] = null;
        break;
      }
      case 'rules': {
        need(host && !g, 'Правила меняет создатель стола');
        const r = decodeRules(msg.rules);
        const dropped = [];
        for (let i = r.players; i < MAX_PLAYERS; i++) if (this.seats[i]) dropped.push(i);
        need(
          dropped.every((i) => this.seats[i].bot),
          'Сначала уберите или пересадите игроков с лишних мест',
        );
        dropped.forEach((i) => (this.seats[i] = null));
        this.rules = r;
        break;
      }
      case 'swap': {
        need(host && !g, 'Пересаживает создатель стола');
        const a = Number(msg.a);
        const b = Number(msg.b);
        const ok = (x) => Number.isInteger(x) && x >= 0 && x < this.capacity;
        need(ok(a) && ok(b) && a !== b, 'Нельзя');
        [this.seats[a], this.seats[b]] = [this.seats[b], this.seats[a]];
        break;
      }
      case 'start': {
        need(host && !g, 'Игру начинает создатель стола');
        need(this.active.every(Boolean), `Нужно игроков: ${this.capacity}`);
        this.game = new BelotGame(
          this.active.map((s) => s.name),
          this.rules,
        );
        break;
      }
      case 'tobot': {
        need(host, 'Только создатель стола');
        const i = Number(msg.seat);
        const s = this.active[i];
        need(s && !s.bot && !s.conn, 'Игрок на связи');
        s.bot = true;
        s.name += ' (бот)';
        if (g) g.names[i] = s.name;
        break;
      }
      case 'bid':
        need(g, 'Игра не начата');
        g.bid(seat, Boolean(msg.take), msg.suit || null);
        break;
      case 'play':
        need(g, 'Игра не начата');
        g.play(seat, String(msg.card));
        break;
      case 'ready':
        need(g, 'Игра не начата');
        if (g.phase === 'HAND_OVER') this.seats[seat].ready = true;
        break;
      case 'newgame':
        need(g, 'Игра не начата');
        need(g.phase === 'GAME_OVER', 'Игра ещё идёт');
        g.newGame();
        break;
      default:
        break;
    }
  }

  changed() {
    const g = this.game;
    if (g && g.phase === 'HAND_OVER') {
      this.active.forEach((s) => {
        if (s.bot) s.ready = true;
      });
      if (this.active.every((s) => s.ready)) {
        this.active.forEach((s) => (s.ready = false));
        g.nextHand();
      }
    }
    this.broadcast();
    this.scheduleBot();
  }

  scheduleBot() {
    const g = this.game;
    if (!g || this.botTimer || this.closed) return;
    const a = g.actor;
    if (a < 0 || !this.seats[a]?.bot) return;
    this.botTimer = setTimeout(() => {
      this.botTimer = null;
      if (this.game !== g || this.closed) return;
      const actor = g.actor;
      try {
        if (actor >= 0 && this.seats[actor]?.bot) {
          if (g.phase === 'BIDDING') {
            const b = chooseBid(g, actor);
            g.bid(actor, b.take, b.suit);
          } else {
            g.play(actor, chooseCard(g, actor));
          }
        }
      } catch (e) {
        console.error('bot failed', e);
      }
      this.changed();
    }, this.botDelayMs);
  }

  stateFor(conn) {
    const you = this.seatOf(conn);
    const msg = {
      t: 'state',
      room: this.code,
      you,
      hostSeat: this.hostSeat,
      players: this.active.map((s) =>
        s ? { name: s.name, bot: s.bot, online: s.bot || !!s.conn, ready: s.ready } : null,
      ),
      rules: encodeRules(this.rules),
    };
    if (this.game && you >= 0) msg.game = this.game.viewFor(you);
    return msg;
  }

  broadcast() {
    for (const c of this.conns) c.send(this.stateFor(c));
  }

  close() {
    this.closed = true;
    clearTimeout(this.botTimer);
    for (const c of this.conns) c.close();
    this.conns.clear();
  }
}

export class Lobby {
  constructor({ botDelayMs = 900, maxRooms = 500 } = {}) {
    this.rooms = new Map();
    this.botDelayMs = botDelayMs;
    this.maxRooms = maxRooms;
  }

  newCode() {
    for (;;) {
      let code = '';
      for (let i = 0; i < 5; i++) code += CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)];
      if (!this.rooms.has(code)) return code;
    }
  }

  /** Routes one client message. */
  handle(conn, msg) {
    if (!msg || typeof msg !== 'object') return;
    switch (msg.t) {
      case 'ping':
        return;
      case 'create': {
        if (conn.room) conn.room.leave(conn);
        if (this.rooms.size >= this.maxRooms) this.cleanup(0);
        if (this.rooms.size >= this.maxRooms) {
          conn.send({ t: 'error', msg: 'Сервер перегружен, попробуйте позже' });
          return;
        }
        const room = new Room(this.newCode(), { botDelayMs: this.botDelayMs });
        this.rooms.set(room.code, room);
        room.join(conn, msg);
        return;
      }
      case 'join': {
        const code = String(msg.room ?? '').toUpperCase().trim();
        const room = this.rooms.get(code);
        if (!room) {
          conn.send({ t: 'closed', msg: `Стол ${code} не найден. Возможно, сервер перезапускался — создайте новый стол.` });
          return;
        }
        if (conn.room && conn.room !== room) conn.room.leave(conn);
        room.join(conn, msg);
        return;
      }
      case 'bye':
        conn.room?.leave(conn);
        return;
      default:
        if (conn.room) conn.room.handle(conn, msg);
        else conn.send({ t: 'error', msg: 'Сначала войдите за стол' });
    }
  }

  disconnect(conn) {
    conn.room?.disconnect(conn);
  }

  /** Removes tables nobody has used for [idleMs]. */
  cleanup(idleMs = 30 * 60 * 1000) {
    const now = Date.now();
    for (const [code, room] of this.rooms) {
      if (room.onlineHumans === 0 && now - room.lastActivity >= idleMs) {
        room.close();
        this.rooms.delete(code);
      }
    }
  }
}
