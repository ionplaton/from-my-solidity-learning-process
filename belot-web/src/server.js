// HTTP + WebSocket server: serves the web app (public/) and runs the game tables.
// Environment: PORT (default 8080), BOT_USERNAME and APP_NAME (optional, for Telegram invite links).
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { Lobby } from './rooms.js';

const PUBLIC_DIR = fileURLToPath(new URL('../public/', import.meta.url));
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
};

export function createServer({ port = Number(process.env.PORT) || 8080, botDelayMs = 900, log = console.log } = {}) {
  const lobby = new Lobby({ botDelayMs });
  const config = {
    botUsername: (process.env.BOT_USERNAME || '').replace(/^@/, ''),
    appName: process.env.APP_NAME || '',
  };

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname === '/healthz') {
        res.writeHead(200, { 'content-type': 'text/plain' });
        res.end(`ok ${lobby.rooms.size}`);
        return;
      }
      if (url.pathname === '/config.js') {
        res.writeHead(200, { 'content-type': TYPES['.js'], 'cache-control': 'no-store' });
        res.end(`window.BELOT_CONFIG = ${JSON.stringify(config)};`);
        return;
      }
      const path = url.pathname === '/' ? '/index.html' : url.pathname;
      const file = normalize(join(PUBLIC_DIR, path));
      if (!file.startsWith(PUBLIC_DIR)) {
        res.writeHead(403).end();
        return;
      }
      const body = await readFile(file);
      res.writeHead(200, {
        'content-type': TYPES[extname(file)] || 'application/octet-stream',
        'cache-control': 'no-cache',
      });
      res.end(body);
    } catch {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('Not found');
    }
  });

  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 16 * 1024 });
  wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => (ws.isAlive = true));
    const conn = {
      room: null,
      pid: null,
      send(obj) {
        if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
      },
      close() {
        try {
          ws.close();
        } catch {
          /* ignore */
        }
      },
    };
    ws.on('message', (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      try {
        lobby.handle(conn, msg);
      } catch (e) {
        log('handler error', e);
        conn.send({ t: 'error', msg: 'Ошибка сервера' });
      }
    });
    ws.on('close', () => lobby.disconnect(conn));
    ws.on('error', () => {});
  });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, 25_000);
  const cleanup = setInterval(() => lobby.cleanup(), 60_000);

  return {
    lobby,
    listen() {
      return new Promise((resolve) =>
        server.listen(port, () => {
          const actual = server.address().port;
          log(`Belot server on http://localhost:${actual}`);
          resolve(actual);
        }),
      );
    },
    close() {
      clearInterval(heartbeat);
      clearInterval(cleanup);
      for (const room of lobby.rooms.values()) room.close();
      wss.close();
      return new Promise((resolve) => server.close(resolve));
    },
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  createServer().listen();
}
