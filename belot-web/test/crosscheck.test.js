// Replays games recorded from the Kotlin engine (belot-android/core, FixtureExport.kt) and checks that the
// JavaScript engine and bot make exactly the same decisions and produce exactly the same state.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { BelotGame, decodeRules } from '../src/engine.js';
import { chooseBid, chooseCard } from '../src/bot.js';

const fixture = JSON.parse(readFileSync(new URL('./fixtures/kotlin-games.json', import.meta.url), 'utf8'));

for (const [gi, rec] of fixture.games.entries()) {
  test(`kotlin game ${gi} (${rec.players} players)`, () => {
    let k = 0;
    const g = new BelotGame(rec.names, decodeRules(rec.rules), {
      firstDealer: rec.firstDealer,
      shuffle: () => rec.decks[k++],
    });
    for (const [si, step] of rec.steps.entries()) {
      const where = `game ${gi} step ${si} ${JSON.stringify(step.action)}`;
      const a = step.action;
      if (a.t === 'bid') {
        const b = chooseBid(g, a.p);
        assert.equal(b.take, a.take, `bot bid ${where}`);
        if (a.suit) assert.equal(b.suit, a.suit, `bot suit ${where}`);
        g.bid(a.p, a.take, a.suit ?? null);
      } else if (a.t === 'play') {
        assert.equal(chooseCard(g, a.p), a.card, `bot card ${where}`);
        g.play(a.p, a.card);
      } else if (a.t === 'next') {
        g.nextHand();
      }
      assert.equal(g.phase, step.phase, `phase ${where}`);
      assert.equal(g.actor, step.actor, `actor ${where}`);
      assert.equal(g.handNumber, step.hand, `hand ${where}`);
      assert.equal(g.dealer, step.dealer, `dealer ${where}`);
      assert.deepEqual(g.totals, step.totals, `totals ${where}`);
      assert.deepEqual(g.actor >= 0 ? g.legalCards(g.actor) : [], step.legal, `legal ${where}`);
      assert.deepEqual(g.logLines, step.log, `log ${where}`);
      if (step.views) {
        step.views.forEach((v, p) => assert.deepEqual(g.viewFor(p), v, `view of ${p} ${where}`));
      }
    }
    assert.equal(g.phase, 'GAME_OVER');
  });
}
