// SVG drawing of cards and suit symbols (same artwork as the Android app; no emoji fonts involved).
export const SUIT_RED = { S: false, H: true, C: false, D: true };
export const SUIT_TITLE = { S: 'Пики', H: 'Черви', C: 'Трефы', D: 'Бубны' };
export const SUIT_SYMBOL = { S: '♠', H: '♥', C: '♣', D: '♦' };
export const RANK_LABEL = { 7: '7', 8: '8', 9: '9', T: '10', J: 'В', Q: 'Д', K: 'К', A: 'Т' };
const RED = '#C62828';
const BLACK = '#1B1B1B';

const SUIT_SHAPES = {
  H: '<path d="M0.5,0.28C0.5,0.02 0,0 0.02,0.32C0.04,0.58 0.32,0.74 0.5,0.97C0.68,0.74 0.96,0.58 0.98,0.32C1,0 0.5,0.02 0.5,0.28Z"/>',
  D: '<path d="M0.5,0Q0.7,0.28 0.9,0.5Q0.7,0.72 0.5,1Q0.3,0.72 0.1,0.5Q0.3,0.28 0.5,0Z"/>',
  S: '<path d="M0.5,0C0.32,0.24 0,0.38 0.02,0.62C0.04,0.86 0.32,0.88 0.46,0.72L0.36,1L0.64,1L0.54,0.72C0.68,0.88 0.96,0.86 0.98,0.62C1,0.38 0.68,0.24 0.5,0Z"/>',
  C:
    '<circle cx="0.5" cy="0.26" r="0.22"/><circle cx="0.25" cy="0.58" r="0.22"/><circle cx="0.75" cy="0.58" r="0.22"/>' +
    '<circle cx="0.5" cy="0.52" r="0.12"/><path d="M0.46,0.55L0.36,1L0.64,1L0.54,0.55Z"/>',
};

export const suitColor = (s) => (SUIT_RED[s] ? RED : BLACK);

/** A suit symbol placed in a square of `size` at (x, y), in the units of the enclosing SVG. */
export function suitShape(s, x, y, size, color = suitColor(s)) {
  return `<g transform="translate(${x} ${y}) scale(${size})" fill="${color}">${SUIT_SHAPES[s]}</g>`;
}

/** Standalone SVG with just a suit symbol (buttons, badges). */
export function suitIcon(s) {
  return `<svg viewBox="0 0 1 1" class="suit-icon" aria-label="${SUIT_TITLE[s]}">${suitShape(s, 0, 0, 1)}</svg>`;
}

function corner(code, color) {
  const r = code[0];
  const ten = r === 'T';
  return (
    `<text x="15" y="27" font-size="25" text-anchor="middle" fill="${color}" ` +
    `font-family="Georgia,'Times New Roman',serif" font-weight="bold"${ten ? ' textLength="22" lengthAdjust="spacingAndGlyphs"' : ''}>` +
    `${RANK_LABEL[r]}</text>` +
    suitShape(code[1], 6.5, 33, 17, color)
  );
}

/** Card face as SVG markup (viewBox 100×145). */
export function cardFace(code, { dim = false, highlight = false } = {}) {
  const r = code[0];
  const s = code[1];
  const color = suitColor(s);
  let center;
  if (r === 'J' || r === 'Q' || r === 'K') {
    center =
      `<rect x="22" y="24.6" width="56" height="95.8" rx="4" fill="none" stroke="${color}" stroke-width="1.5"/>` +
      `<text x="50" y="71" font-size="42" text-anchor="middle" fill="${color}" font-family="Georgia,'Times New Roman',serif" font-weight="bold">${RANK_LABEL[r]}</text>` +
      suitShape(s, 38, 78.3, 24, color);
  } else {
    const size = r === 'A' ? 50 : 40;
    center = suitShape(s, 50 - size / 2, 72.5 - size / 2, size, color);
  }
  return (
    `<svg viewBox="-2 -2 104 149" class="card-svg">` +
    `<rect x="1" y="2" width="100" height="145" rx="8" fill="rgba(0,0,0,0.33)"/>` +
    `<rect x="0" y="0" width="100" height="145" rx="8" fill="#FFFDF6" stroke="${highlight ? '#F2B705' : '#9E9E9E'}" stroke-width="${highlight ? 5 : 1.2}"/>` +
    corner(code, color) +
    `<g transform="rotate(180 50 72.5)">${corner(code, color)}</g>` +
    center +
    (dim ? `<rect x="0" y="0" width="100" height="145" rx="8" fill="rgba(0,0,0,0.45)"/>` : '') +
    `</svg>`
  );
}

/** Card back as SVG markup (uses the #belot-back pattern defined in index.html). */
export function cardBack() {
  return (
    `<svg viewBox="-2 -2 104 149" class="card-svg">` +
    `<rect x="1" y="2" width="100" height="145" rx="8" fill="rgba(0,0,0,0.33)"/>` +
    `<rect x="0" y="0" width="100" height="145" rx="8" fill="#FFFFFF" stroke="#9E9E9E" stroke-width="1.2"/>` +
    `<rect x="7" y="7" width="86" height="131" rx="5" fill="url(#belot-back)"/>` +
    `</svg>`
  );
}
