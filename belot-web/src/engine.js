// Belot for 2, 3 or 4 players: rules, scoring and the per-player view.
// This is a direct port of belot-android/core (BelotGame.kt, Combos.kt, Cards.kt); the test suite replays
// games recorded from the Kotlin engine to make sure both behave identically.

export const SUITS = ['S', 'H', 'C', 'D'];
export const SUIT_INFO = {
  S: { symbol: '♠', red: false, title: 'Пики' },
  H: { symbol: '♥', red: true, title: 'Черви' },
  C: { symbol: '♣', red: false, title: 'Трефы' },
  D: { symbol: '♦', red: true, title: 'Бубны' },
};

/** Ranks in sequence order (7, 8, 9, 10, J, Q, K, A); index = position in a sequence. */
export const RANKS = ['7', '8', '9', 'T', 'J', 'Q', 'K', 'A'];
const RANK_INFO = {
  //    plainOrder trumpOrder plainPoints trumpPoints label
  '7': [0, 0, 0, 0, '7'],
  '8': [1, 1, 0, 0, '8'],
  '9': [2, 6, 0, 14, '9'],
  T: [6, 4, 10, 10, '10'],
  J: [3, 7, 2, 20, 'В'],
  Q: [4, 2, 3, 3, 'Д'],
  K: [5, 3, 4, 4, 'К'],
  A: [7, 5, 11, 11, 'Т'],
};

export const LAST_TRICK_BONUS = 10;
export const CAPOT_BONUS = 90;
export const BELOTE_POINTS = 20;
export const TARGETS = [301, 501, 1001];
export const PLAYER_COUNTS = [2, 3, 4];

export const rankOf = (c) => c[0];
export const suitOf = (c) => c[1];
export const seqIndex = (r) => RANKS.indexOf(r);
export const trumpOrderOf = (r) => RANK_INFO[r][1];
export const rankLabel = (r) => RANK_INFO[r][4];
export const cardLabel = (c) => rankLabel(c[0]) + SUIT_INFO[c[1]].symbol;

export function points(card, trump) {
  return card[1] === trump ? RANK_INFO[card[0]][3] : RANK_INFO[card[0]][2];
}

export function order(card, trump) {
  return card[1] === trump ? RANK_INFO[card[0]][1] : RANK_INFO[card[0]][0];
}

/** 32 cards (7..A) for four players, 24 cards (9..A) for two or three players. */
export function deck(players) {
  const ranks = players === 4 ? RANKS : RANKS.slice(2);
  return SUITS.flatMap((s) => ranks.map((r) => r + s));
}

export function defaultRules(players = 3) {
  return { players, target: 501, takerMustBeatEach: true, mustUnderTrump: true };
}

/** Sorts a hand for display: suits alternate in colour, trump first, strongest card first. */
export function sortHand(cards, trump) {
  const key = (c) => (c[1] === trump ? -1 : SUITS.indexOf(c[1]));
  return [...cards].sort((a, b) => key(a) - key(b) || order(b, trump) - order(a, trump));
}

// ---------------------------------------------------------------- combinations

function carrePoints(r) {
  return r === 'J' ? 200 : r === '9' ? 150 : 100;
}

function sequencePoints(len) {
  return len >= 5 ? 100 : len === 4 ? 50 : 20;
}

function comboTop(combo) {
  return combo.cards.reduce((best, c) => (seqIndex(c[0]) > seqIndex(best) ? c[0] : best), combo.cards[0][0]);
}

export function comboTitle(combo) {
  const top = comboTop(combo);
  if (combo.kind === 'CARRE') return `Каре ${rankLabel(top)}`;
  const name = combo.cards.length === 3 ? 'Терц' : combo.cards.length === 4 ? 'Пятьдесят' : 'Сто';
  return `${name} до ${rankLabel(top)}${SUIT_INFO[combo.cards[0][1]].symbol}`;
}

/** All combinations in a hand. A card used in a carré is not reused in a sequence. */
export function findCombos(hand) {
  const result = [];
  const used = new Set();
  for (const r of RANKS) {
    if (seqIndex(r) < seqIndex('9')) continue; // carrés of sevens and eights do not count
    const four = hand.filter((c) => c[0] === r);
    if (four.length === 4) {
      result.push({ kind: 'CARRE', cards: four, points: carrePoints(r) });
      four.forEach((c) => used.add(c));
    }
  }
  for (const s of SUITS) {
    const idx = hand.filter((c) => c[1] === s && !used.has(c)).map((c) => seqIndex(c[0])).sort((a, b) => a - b);
    let i = 0;
    while (i < idx.length) {
      let j = i;
      while (j + 1 < idx.length && idx[j + 1] === idx[j] + 1) j++;
      const len = j - i + 1;
      if (len >= 3) {
        const cards = [];
        for (let k = i; k <= j; k++) cards.push(RANKS[idx[k]] + s);
        result.push({ kind: 'SEQUENCE', cards, points: sequencePoints(len) });
      }
      i = j + 1;
    }
  }
  return result;
}

function compareKeys(a, b) {
  return a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
}

/**
 * Which combinations count: only the side holding the best sequence scores its sequences, only the side
 * holding the best carré scores its carrés. Equal best sequences held by different sides cancel out.
 * Returns a Set of "player:comboIndex".
 */
export function comboWinners(combos, trump, sideOf) {
  const counted = new Set();
  const seqKey = (c) => [c.cards.length, seqIndex(comboTop(c)), c.cards[0][1] === trump ? 1 : 0];

  const bestSeq = [];
  combos.forEach((list, p) => {
    let best = null;
    for (const c of list) {
      if (c.kind !== 'SEQUENCE') continue;
      const k = seqKey(c);
      if (best === null || compareKeys(k, best) > 0) best = k;
    }
    if (best !== null) bestSeq.push([p, best]);
  });
  if (bestSeq.length) {
    let top = bestSeq[0][1];
    for (const [, k] of bestSeq) if (compareKeys(k, top) > 0) top = k;
    const sides = new Set(bestSeq.filter(([, k]) => compareKeys(k, top) === 0).map(([p]) => sideOf(p)));
    if (sides.size === 1) {
      const side = [...sides][0];
      combos.forEach((list, p) => {
        if (sideOf(p) === side) list.forEach((c, i) => c.kind === 'SEQUENCE' && counted.add(`${p}:${i}`));
      });
    }
  }

  let bestCarre = null;
  combos.forEach((list, p) => {
    for (const c of list) {
      if (c.kind !== 'CARRE') continue;
      const strength = trumpOrderOf(comboTop(c));
      if (bestCarre === null || strength > bestCarre[1]) bestCarre = [p, strength];
    }
  });
  if (bestCarre) {
    const side = sideOf(bestCarre[0]);
    combos.forEach((list, p) => {
      if (sideOf(p) === side) list.forEach((c, i) => c.kind === 'CARRE' && counted.add(`${p}:${i}`));
    });
  }
  return counted;
}

// ---------------------------------------------------------------- game

export function shuffled(cards, random = Math.random) {
  const a = [...cards];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export class GameError extends Error {}

const check = (cond, msg) => {
  if (!cond) throw new GameError(msg);
};

/**
 * - 4 players: two teams (seats 0+2 and 1+3), 32 cards, 8 each.
 * - 3 players: everyone for themselves, 24 cards, 8 each.
 * - 2 players: one against one, 24 cards, 12 each.
 */
export class BelotGame {
  constructor(names, rules = defaultRules(names.length), { firstDealer, shuffle } = {}) {
    check(names.length >= 2 && names.length <= 4, 'Нужно от 2 до 4 игроков');
    this.playerCount = names.length;
    this.rules = { ...rules };
    this.names = [...names];
    this.sideCount = this.playerCount === 4 ? 2 : this.playerCount;
    this.deckCards = deck(this.playerCount);
    this.tricksPerHand = this.deckCards.length / this.playerCount;
    this.firstDeal = this.playerCount === 2 ? 6 : 5;
    this.takerExtra = this.playerCount === 2 ? 5 : 2;
    this.othersExtra = this.playerCount === 2 ? 6 : 3;
    this.shuffle = shuffle || ((cards) => shuffled(cards));
    this.totals = new Array(this.sideCount).fill(0);
    this.phase = 'BIDDING';
    this.handNumber = 0;
    this.dealer = firstDealer ?? Math.floor(Math.random() * this.playerCount);
    this.winner = -1;
    this.logLines = [];
    this.deal();
  }

  sideOf(p) {
    return this.playerCount === 4 ? p % 2 : p;
  }

  partnerOf(p) {
    return this.playerCount === 4 ? (p + 2) % 4 : -1;
  }

  next(p) {
    return (p + 1) % this.playerCount;
  }

  log(s) {
    this.logLines.push(s);
    while (this.logLines.length > 10) this.logLines.shift();
  }

  take(n, to) {
    for (let i = 0; i < n; i++) to.push(this.stock.pop());
  }

  deal() {
    this.handNumber++;
    this.phase = 'BIDDING';
    this.hands = Array.from({ length: this.playerCount }, () => []);
    this.stock = [...this.shuffle(this.deckCards)];
    for (let i = 0; i < this.playerCount; i++) this.take(this.firstDeal, this.hands[(this.dealer + 1 + i) % this.playerCount]);
    this.turnedCard = this.stock.pop();
    this.bidRound = 1;
    this.passesInRound = 0;
    this.passed = new Array(this.playerCount).fill(false);
    this.trump = null;
    this.taker = -1;
    this.trick = [];
    this.lastTrick = [];
    this.lastTrickWinner = -1;
    this.tricksPlayed = 0;
    this.tricksWon = new Array(this.playerCount).fill(0);
    this.cardPoints = new Array(this.playerCount).fill(0);
    this.playedCards = new Set();
    this.declarations = [];
    this.beloteHolder = -1;
    this.beloteCardsPlayed = 0;
    this.lastResult = null;
    this.currentPlayer = this.next(this.dealer);
    this.log(`Раздача №${this.handNumber}, сдаёт ${this.names[this.dealer]}. Открыта ${cardLabel(this.turnedCard)}`);
  }

  /** Round 1: suit is ignored (the turned suit is taken). Round 2: suit must differ from the turned suit. */
  bid(player, take, suit = null) {
    check(this.phase === 'BIDDING', 'Сейчас не торговля');
    check(player === this.currentPlayer, 'Сейчас не ваш ход');
    const turned = this.turnedCard;
    if (take) {
      let chosen = turned[1];
      if (this.bidRound !== 1) {
        check(SUITS.includes(suit) && suit !== turned[1], 'Нужно выбрать другую масть');
        chosen = suit;
      }
      this.log(`${this.names[player]} берёт, козырь ${SUIT_INFO[chosen].symbol}`);
      this.beginPlay(player, chosen);
      return;
    }
    this.passed[player] = true;
    this.passesInRound++;
    this.log(`${this.names[player]}: пас`);
    if (this.passesInRound < this.playerCount) {
      this.currentPlayer = this.next(player);
    } else if (this.bidRound === 1) {
      this.bidRound = 2;
      this.passesInRound = 0;
      this.passed.fill(false);
      this.currentPlayer = this.next(this.dealer);
    } else {
      this.log('Все спасовали — пересдача');
      this.dealer = this.next(this.dealer);
      this.deal();
    }
  }

  beginPlay(player, chosen) {
    this.trump = chosen;
    this.taker = player;
    this.hands[player].push(this.turnedCard);
    this.take(this.takerExtra, this.hands[player]);
    for (let i = 1; i < this.playerCount; i++) this.take(this.othersExtra, this.hands[(player + i) % this.playerCount]);
    check(this.stock.length === 0, 'internal: stock not empty');

    const combos = this.hands.map((h) => findCombos(h));
    const counted = comboWinners(combos, chosen, (p) => this.sideOf(p));
    this.declarations = combos.flatMap((list, p) =>
      list.map((combo, i) => ({ player: p, combo, counted: counted.has(`${p}:${i}`) })),
    );
    this.beloteHolder = -1;
    for (let p = 0; p < this.playerCount; p++) {
      if (this.hands[p].includes('K' + chosen) && this.hands[p].includes('Q' + chosen)) {
        this.beloteHolder = p;
        break;
      }
    }
    this.phase = 'PLAYING';
    this.currentPlayer = this.next(this.dealer);
  }

  legalCards(player) {
    if (this.phase !== 'PLAYING' || player !== this.currentPlayer) return [];
    const hand = this.hands[player];
    if (!this.trick.length) return [...hand];
    const tr = this.trump;
    const led = this.trick[0].card[1];
    const trumpsOnTable = this.trick.filter((p) => p.card[1] === tr).map((p) => trumpOrderOf(p.card[0]));
    const topTrump = trumpsOnTable.length ? Math.max(...trumpsOnTable) : null;

    const follow = hand.filter((c) => c[1] === led);
    if (follow.length) {
      // When trump is led, a higher trump must be played if possible (even over the partner).
      if (led === tr && topTrump !== null) {
        const higher = follow.filter((c) => trumpOrderOf(c[0]) > topTrump);
        return higher.length ? higher : follow;
      }
      return follow;
    }
    // Cannot follow. With four players there is no obligation to trump while the partner wins the trick.
    const partnerWinning = this.playerCount === 4 && this.trick[this.trickWinnerIndex()].player === this.partnerOf(player);
    if (partnerWinning) return [...hand];
    const trumps = hand.filter((c) => c[1] === tr);
    if (trumps.length) {
      if (topTrump === null) return trumps;
      const higher = trumps.filter((c) => trumpOrderOf(c[0]) > topTrump);
      if (higher.length) return higher;
      return this.rules.mustUnderTrump ? trumps : [...hand];
    }
    return [...hand];
  }

  /** Index into the trick of the card that currently wins it. */
  trickWinnerIndex(cards = this.trick) {
    const tr = this.trump;
    const led = cards[0].card[1];
    let best = 0;
    for (let i = 1; i < cards.length; i++) {
      const c = cards[i].card;
      const b = cards[best].card;
      let beats = false;
      if (c[1] === tr && b[1] !== tr) beats = true;
      else if (c[1] === b[1]) beats = order(c, tr) > order(b, tr);
      if (beats && (c[1] === led || c[1] === tr)) best = i;
    }
    return best;
  }

  play(player, card) {
    check(this.phase === 'PLAYING', 'Сейчас нельзя ходить');
    check(player === this.currentPlayer, 'Сейчас не ваш ход');
    check(this.legalCards(player).includes(card), 'Этой картой ходить нельзя');
    const hand = this.hands[player];
    hand.splice(hand.indexOf(card), 1);
    this.trick.push({ player, card });
    this.playedCards.add(card);

    if (player === this.beloteHolder && card[1] === this.trump && (card[0] === 'K' || card[0] === 'Q')) {
      this.beloteCardsPlayed++;
      this.log(`${this.names[player]}: ${this.beloteCardsPlayed === 1 ? 'Бела!' : 'Ребела!'}`);
    }

    if (this.trick.length < this.playerCount) {
      this.currentPlayer = this.next(player);
      return;
    }

    const w = this.trick[this.trickWinnerIndex()].player;
    this.cardPoints[w] += this.trick.reduce((sum, p) => sum + points(p.card, this.trump), 0);
    this.tricksWon[w]++;
    this.tricksPlayed++;
    this.lastTrick = [...this.trick];
    this.lastTrickWinner = w;
    this.trick = [];
    this.log(`Взятку взял(а) ${this.names[w]}`);

    if (this.tricksPlayed === 1) {
      for (const d of this.declarations) {
        this.log(`${this.names[d.player]}: ${comboTitle(d.combo)} (${d.combo.points})${d.counted ? '' : ' — не считается'}`);
      }
    }

    if (this.tricksPlayed === this.tricksPerHand) this.endHand(w);
    else this.currentPlayer = w;
  }

  endHand(lastWinner) {
    const sides = this.sideCount;
    const zeros = () => new Array(sides).fill(0);
    const cards = zeros();
    for (let p = 0; p < this.playerCount; p++) cards[this.sideOf(p)] += this.cardPoints[p];
    const combo = zeros();
    for (const d of this.declarations) if (d.counted) combo[this.sideOf(d.player)] += d.combo.points;
    const belote = zeros();
    if (this.beloteHolder >= 0) belote[this.sideOf(this.beloteHolder)] = BELOTE_POINTS;
    const last = zeros();
    last[this.sideOf(lastWinner)] = LAST_TRICK_BONUS;
    const capot = zeros();
    const sideTricks = zeros();
    for (let p = 0; p < this.playerCount; p++) sideTricks[this.sideOf(p)] += this.tricksWon[p];
    const capotSide = sideTricks.findIndex((t) => t === this.tricksPerHand);
    if (capotSide >= 0) capot[capotSide] = CAPOT_BONUS;

    const raw = cards.map((_, s) => cards[s] + combo[s] + belote[s] + last[s] + capot[s]);
    const total = raw.reduce((a, b) => a + b, 0);
    const ts = this.sideOf(this.taker);
    const opponents = [...Array(sides).keys()].filter((s) => s !== ts);
    const made =
      sides === 3 && !this.rules.takerMustBeatEach ? raw[ts] * 2 > total : opponents.every((s) => raw[ts] > raw[s]);

    const awarded = [...raw];
    if (!made) {
      // The taking side keeps only its belote. The rest goes to the opponents (split between two opponents
      // with three players). On an exact tie with the single opponent side the taker's points are lost.
      const rest = raw[ts] - belote[ts];
      awarded[ts] = belote[ts];
      if (sides === 2) {
        if (raw[ts] !== raw[opponents[0]]) awarded[opponents[0]] += rest;
      } else {
        const half = Math.floor(rest / 2);
        awarded[opponents[0]] += rest - half;
        awarded[opponents[1]] += half;
      }
    }
    for (let s = 0; s < sides; s++) this.totals[s] += awarded[s];

    this.lastResult = {
      trump: this.trump,
      taker: this.taker,
      cardPoints: cards,
      comboPoints: combo,
      belote,
      lastTrick: last,
      capot,
      raw,
      awarded,
      takerMade: made,
    };
    this.log(made ? `${this.names[this.taker]} сыграл(а) свою игру` : `${this.names[this.taker]} не выполнил(а) игру`);

    const best = Math.max(...this.totals);
    const leaders = this.totals.map((t, s) => (t === best ? s : -1)).filter((s) => s >= 0);
    if (best >= this.rules.target && leaders.length === 1) {
      this.winner = leaders[0];
      this.phase = 'GAME_OVER';
      this.log(`Победа: ${this.sideName(this.winner)}!`);
    } else {
      this.phase = 'HAND_OVER';
    }
  }

  sideMembers(side) {
    return [...Array(this.playerCount).keys()].filter((p) => this.sideOf(p) === side);
  }

  sideName(side) {
    return this.sideMembers(side).map((p) => this.names[p]).join(' и ');
  }

  nextHand() {
    check(this.phase === 'HAND_OVER', 'Раздача ещё не окончена');
    this.dealer = this.next(this.dealer);
    this.deal();
  }

  newGame() {
    check(this.phase === 'GAME_OVER', 'Игра ещё не окончена');
    this.totals.fill(0);
    this.winner = -1;
    this.handNumber = 0;
    this.dealer = this.next(this.dealer);
    this.deal();
  }

  /** Who has to act right now (bidding or playing), or -1. */
  get actor() {
    return this.phase === 'BIDDING' || this.phase === 'PLAYING' ? this.currentPlayer : -1;
  }

  /** Everything one player may see, in the same JSON shape as the Android app's protocol. */
  viewFor(seat) {
    const revealAll = this.tricksPlayed >= 1 || this.phase === 'HAND_OVER' || this.phase === 'GAME_OVER';
    const v = {
      seat,
      n: this.playerCount,
      phase: this.phase,
      hand_no: this.handNumber,
      dealer: this.dealer,
      current: this.actor,
      hand: sortHand(this.hands[seat], this.trump),
      counts: this.hands.map((h) => h.length),
      bid_round: this.bidRound,
      passed: [...this.passed],
      taker: this.taker,
      trick: this.trick.map((p) => ({ p: p.player, c: p.card })),
      last_trick: this.lastTrick.map((p) => ({ p: p.player, c: p.card })),
      last_winner: this.lastTrickWinner,
      tricks: [...this.tricksWon],
      totals: [...this.totals],
      legal: this.legalCards(seat),
      belote: this.beloteCardsPlayed > 0 ? this.beloteHolder : -1,
      winner: this.winner,
      log: [...this.logLines],
      rules: encodeRules(this.rules),
      decl: this.declarations
        .filter((d) => revealAll || d.player === seat)
        .map((d) => ({ p: d.player, combo: { kind: d.combo.kind, cards: d.combo.cards, pts: d.combo.points }, ok: d.counted })),
    };
    if (this.phase === 'BIDDING') v.turned = this.turnedCard;
    if (this.trump) v.trump = this.trump;
    if (this.lastResult) {
      const r = this.lastResult;
      v.result = {
        trump: r.trump,
        taker: r.taker,
        cards: r.cardPoints,
        combo: r.comboPoints,
        belote: r.belote,
        last: r.lastTrick,
        capot: r.capot,
        raw: r.raw,
        awarded: r.awarded,
        made: r.takerMade,
      };
    }
    return v;
  }
}

export function encodeRules(r) {
  return { players: r.players, target: r.target, beatEach: r.takerMustBeatEach, underTrump: r.mustUnderTrump };
}

export function decodeRules(o = {}) {
  const players = Math.min(4, Math.max(2, Number.parseInt(o.players ?? 3, 10) || 3));
  const target = TARGETS.includes(Number(o.target)) ? Number(o.target) : 501;
  return { players, target, takerMustBeatEach: o.beatEach !== false, mustUnderTrump: o.underTrump !== false };
}
