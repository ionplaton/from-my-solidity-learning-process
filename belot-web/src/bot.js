// A simple computer player (port of belot-android/core Bot.kt). It only looks at its own cards and at
// what has been played. Ties are broken exactly like Kotlin's maxBy/minBy (first element wins).
import { SUITS, points, order, trumpOrderOf } from './engine.js';

function maxBy(list, f) {
  let best = null;
  let bestV = -Infinity;
  for (const x of list) {
    const v = f(x);
    if (best === null || v > bestV) {
      best = x;
      bestV = v;
    }
  }
  return best;
}

function minBy(list, f) {
  return maxBy(list, (x) => -f(x));
}

const RANK_WEIGHT = { J: 3.0, 9: 2.2, A: 1.2, T: 1.0, K: 0.6, Q: 0.5, 8: 0.3, 7: 0.3 };

function strength(cards, trump) {
  let s = 0.0;
  const trumps = cards.filter((c) => c[1] === trump);
  for (const c of trumps) s += RANK_WEIGHT[c[0]];
  if (trumps.length > 2) s += 0.5 * (trumps.length - 2);
  for (const suit of SUITS) {
    if (suit === trump) continue;
    const side = cards.filter((c) => c[1] === suit);
    if (side.some((c) => c[0] === 'A')) {
      s += 1.0;
      if (side.some((c) => c[0] === 'T')) s += 0.5;
    }
  }
  if (cards.includes('K' + trump) && cards.includes('Q' + trump)) s += 0.7;
  return s;
}

function threshold(game) {
  if (game.playerCount === 2) return 5.2;
  if (game.playerCount === 4) return 4.2;
  return 4.6;
}

export function chooseBid(game, seat) {
  const hand = game.hands[seat];
  const turned = game.turnedCard;
  if (game.bidRound === 1) {
    return { take: strength([...hand, turned], turned[1]) >= threshold(game), suit: null };
  }
  const best = maxBy(
    SUITS.filter((s) => s !== turned[1]),
    (s) => strength(hand, s),
  );
  return { take: strength(hand, best) >= threshold(game), suit: best };
}

function isMaster(game, card, trump) {
  return !game.deckCards.some(
    (c) => c[1] === card[1] && order(c, trump) > order(card, trump) && !game.playedCards.has(c),
  );
}

function cheapest(cards, trump) {
  const key = (c) => [c[1] === trump ? 1 : 0, points(c, trump), order(c, trump)];
  const cmp = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
  let best = cards[0];
  for (const c of cards.slice(1)) if (cmp(key(c), key(best)) < 0) best = c;
  return best;
}

function lead(game, seat, legal, trump) {
  const hand = game.hands[seat];
  const outstandingTrumps = game.deckCards.filter(
    (c) => c[1] === trump && !game.playedCards.has(c) && !hand.includes(c),
  ).length;
  const myTrumps = legal.filter((c) => c[1] === trump);
  const ourSideTook = game.sideOf(seat) === game.sideOf(game.taker);
  if (ourSideTook && outstandingTrumps > 0 && myTrumps.length) {
    const master = myTrumps.find((c) => isMaster(game, c, trump));
    if (master !== undefined) return master;
  }
  const masters = legal.filter((c) => c[1] !== trump && isMaster(game, c, trump));
  if (masters.length) return maxBy(masters, (c) => points(c, trump));
  const plain = legal.filter((c) => c[1] !== trump);
  if (plain.length) return cheapest(plain, trump);
  return minBy(legal, (c) => trumpOrderOf(c[0]));
}

export function chooseCard(game, seat) {
  const legal = game.legalCards(seat);
  if (legal.length === 1) return legal[0];
  const trump = game.trump;
  const trick = game.trick;

  if (!trick.length) return lead(game, seat, legal, trump);

  const winnerPlay = trick[game.trickWinnerIndex()];
  const winningNow = winnerPlay.card;
  const last = trick.length === game.playerCount - 1;
  const partnerWins = winnerPlay.player === game.partnerOf(seat);

  if (partnerWins && (last || isMaster(game, winningNow, trump))) {
    // The trick is ours: add points with a non-trump card, but keep aces that are still masters.
    const plain = legal.filter((c) => c[1] !== trump);
    if (plain.length) return maxBy(plain, (c) => points(c, trump) * 2 - (isMaster(game, c, trump) ? 15 : 0));
    return cheapest(legal, trump);
  }

  const winners = legal.filter((c) => {
    const t = [...trick, { player: seat, card: c }];
    return game.trickWinnerIndex(t) === t.length - 1;
  });
  const trickPoints = trick.reduce((sum, p) => sum + points(p.card, trump), 0);

  if (winners.length && !partnerWins) {
    const plainWinners = winners.filter((c) => c[1] !== trump);
    if (last) {
      return plainWinners.length
        ? maxBy(plainWinners, (c) => points(c, trump))
        : minBy(winners, (c) => trumpOrderOf(c[0]));
    }
    const sure = plainWinners.filter((c) => isMaster(game, c, trump));
    if (sure.length) return maxBy(sure, (c) => points(c, trump));
    if (winningNow[1] !== trump && !plainWinners.length && trickPoints >= 10) {
      return minBy(winners, (c) => trumpOrderOf(c[0]));
    }
    if (winningNow[1] === trump) return minBy(winners, (c) => trumpOrderOf(c[0]));
  }
  return cheapest(legal, trump);
}
