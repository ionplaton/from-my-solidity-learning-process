import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BelotGame, deck, points, findCombos, comboWinners, defaultRules, decodeRules, encodeRules, SUITS } from '../src/engine.js';
import { chooseBid, chooseCard } from '../src/bot.js';

test('decks and card points', () => {
  for (const t of SUITS) {
    assert.equal(deck(3).reduce((s, c) => s + points(c, t), 0), 152);
    assert.equal(deck(4).reduce((s, c) => s + points(c, t), 0), 152);
  }
  assert.equal(new Set(deck(4)).size, 32);
  assert.equal(new Set(deck(2)).size, 24);
});

test('combinations', () => {
  const c = findCombos(['9H', 'TH', 'JH', 'QH', 'AS', 'AC', 'AD', 'AH']);
  assert.equal(c.filter((x) => x.kind === 'CARRE' && x.points === 100).length, 1);
  assert.equal(c.find((x) => x.kind === 'SEQUENCE').points, 50);
  assert.equal(findCombos(['7S', '7H', '7C', '7D']).length, 0);
  const team = comboWinners(
    [findCombos(['9H', 'TH', 'JH']), findCombos(['QS', 'KS', 'AS']), findCombos(['7D', '8D', '9D', 'TD']), []],
    'C',
    (p) => p % 2,
  );
  assert.deepEqual([...team].sort(), ['0:0', '2:0']);
});

test('rules round trip', () => {
  const r = { players: 2, target: 1001, takerMustBeatEach: false, mustUnderTrump: false };
  assert.deepEqual(decodeRules(encodeRules(r)), r);
  assert.deepEqual(decodeRules({ players: 9, target: 5 }), { ...defaultRules(4), target: 501 });
});

test('bots finish games for 2, 3 and 4 players', () => {
  for (const n of [2, 3, 4]) {
    for (let k = 0; k < 40; k++) {
      const g = new BelotGame(['A', 'B', 'C', 'D'].slice(0, n), { ...defaultRules(n), target: 301 });
      let guard = 0;
      while (g.phase !== 'GAME_OVER') {
        assert.ok(guard++ < 10000);
        if (g.phase === 'BIDDING') {
          const b = chooseBid(g, g.currentPlayer);
          g.bid(g.currentPlayer, b.take, b.suit);
        } else if (g.phase === 'PLAYING') {
          const p = g.currentPlayer;
          const card = chooseCard(g, p);
          assert.ok(g.legalCards(p).includes(card));
          g.play(p, card);
        } else {
          const r = g.lastResult;
          assert.equal(r.cardPoints.reduce((a, b) => a + b) + r.lastTrick.reduce((a, b) => a + b), 162);
          g.nextHand();
        }
      }
      assert.ok(Math.max(...g.totals) >= 301);
    }
  }
});
