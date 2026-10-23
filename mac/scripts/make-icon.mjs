// Generates Resources/AppIcon.icns: a gradient squircle with a text cursor and a sparkle. No dependencies.
import { writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { execSync } from 'node:child_process';

const crcTable = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc32 = (b) => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(td)); return Buffer.concat([l, td, c]); };

function png(size) {
  const ss = 3, raw = Buffer.alloc((size * 4 + 1) * size);
  const m = size * 0.1, s = size - 2 * m, r = s * 0.225; // macOS icon grid: inset squircle
  const inBody = (x, y) => { if (x < m || y < m || x > m + s || y > m + s) return false; const cx = Math.min(Math.max(x, m + r), m + s - r), cy = Math.min(Math.max(y, m + r), m + s - r); return (x - cx) ** 2 + (y - cy) ** 2 <= r * r; };
  const star = (x, y, cx, cy, R) => Math.sqrt(Math.abs(x - cx) / R) + Math.sqrt(Math.abs(y - cy) / R) <= 1;
  // I-beam text cursor
  const cxB = size * 0.44, top = size * 0.3, bot = size * 0.7, w = size * 0.035, serif = size * 0.09, sh = size * 0.035;
  const beam = (x, y) => (Math.abs(x - cxB) <= w / 2 && y >= top && y <= bot) || (Math.abs(x - cxB) <= serif && (Math.abs(y - top) <= sh / 2 || Math.abs(y - bot) <= sh / 2));
  // ghost text line after the cursor
  const ghost = (x, y) => x >= size * 0.5 && x <= size * 0.74 && Math.abs(y - size * 0.6) <= size * 0.022;
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      let a = 0, R = 0, G = 0, B = 0;
      for (let sy = 0; sy < ss; sy++) for (let sx = 0; sx < ss; sx++) {
        const px = x + (sx + 0.5) / ss, py = y + (sy + 0.5) / ss;
        if (!inBody(px, py)) continue;
        const t = (px + py) / (2 * size);
        // indigo #4f46e5 → violet #7c3aed → pink #db2777
        let cr = t < 0.5 ? 79 + (124 - 79) * t * 2 : 124 + (219 - 124) * (t - 0.5) * 2;
        let cg = t < 0.5 ? 70 + (58 - 70) * t * 2 : 58 + (39 - 58) * (t - 0.5) * 2;
        let cb = t < 0.5 ? 229 + (237 - 229) * t * 2 : 237 + (119 - 237) * (t - 0.5) * 2;
        if (beam(px, py) || star(px, py, size * 0.7, size * 0.36, size * 0.11)) { cr = cg = cb = 255; }
        else if (ghost(px, py)) { cr = cr * 0.45 + 255 * 0.55; cg = cg * 0.45 + 255 * 0.55; cb = cb * 0.45 + 255 * 0.55; }
        R += cr; G += cg; B += cb; a++;
      }
      const o = y * (size * 4 + 1) + 1 + x * 4;
      raw[o] = a ? R / a : 0; raw[o + 1] = a ? G / a : 0; raw[o + 2] = a ? B / a : 0; raw[o + 3] = (a / (ss * ss)) * 255;
    }
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

const dir = 'build/AppIcon.iconset';
rmSync(dir, { recursive: true, force: true });
mkdirSync(dir, { recursive: true });
for (const s of [16, 32, 128, 256, 512]) {
  writeFileSync(`${dir}/icon_${s}x${s}.png`, png(s));
  writeFileSync(`${dir}/icon_${s}x${s}@2x.png`, png(s * 2));
}
execSync(`iconutil -c icns ${dir} -o Resources/AppIcon.icns`);
writeFileSync('Resources/AppIcon-1024.png', png(1024));
console.log('Resources/AppIcon.icns written');
