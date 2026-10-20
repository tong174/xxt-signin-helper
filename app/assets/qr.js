/* =====================================================================
 * qr.js — 精简 QR Code 编码器（字节模式）
 * 支持版本 1~10、纠错等级 L/M/Q/H，自动选版、自动选最优掩码
 * 输出：二维布尔矩阵（true = 黑）
 * ===================================================================== */
(function (root) {
  'use strict';

  /* ---- GF(256) ---- */
  var EXP = new Uint8Array(512), LOG = new Uint8Array(256);
  (function () {
    var x = 1;
    for (var i = 0; i < 255; i++) {
      EXP[i] = x; LOG[x] = i;
      x <<= 1; if (x & 0x100) x ^= 0x11d;
    }
    for (var j = 255; j < 512; j++) EXP[j] = EXP[j - 255];
  })();
  function gmul(a, b) { return (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]]; }

  function rsGenPoly(n) {
    var p = [1];
    for (var i = 0; i < n; i++) {
      var q = p.concat([0]);
      for (var j = 0; j < p.length; j++) q[j + 1] ^= gmul(p[j], EXP[i]);
      p = q;
    }
    return p;
  }
  function rsEncode(data, ecLen) {
    var gen = rsGenPoly(ecLen);
    var res = new Uint8Array(data.length + ecLen);
    res.set(data);
    for (var i = 0; i < data.length; i++) {
      var f = res[i];
      if (f === 0) continue;
      for (var j = 0; j < gen.length; j++) res[i + j] ^= gmul(gen[j], f);
    }
    return res.subarray(data.length);
  }

  /* ---- 版本 / 纠错表（版本 1~10） ---- */
  // [总码字, 每块数据码字, 块数(组1), 每块数据码字(组2), 块数(组2), EC每块]
  var RS = {
    L: { 1:[26,19,1,0,0,7], 2:[44,34,1,0,0,10], 3:[70,55,1,0,0,15], 4:[100,80,1,0,0,20], 5:[134,108,1,0,0,26], 6:[172,68,2,0,0,18], 7:[196,78,2,0,0,20], 8:[242,97,2,0,0,24], 9:[292,116,2,0,0,30], 10:[346,68,2,69,2,18] },
    M: { 1:[26,16,1,0,0,10], 2:[44,28,1,0,0,16], 3:[70,44,1,0,0,26], 4:[100,32,2,0,0,18], 5:[134,43,2,0,0,24], 6:[172,27,4,0,0,16], 7:[196,31,4,0,0,18], 8:[242,38,2,39,2,22], 9:[292,36,3,37,2,22], 10:[346,43,4,44,1,26] },
    Q: { 1:[26,13,1,0,0,13], 2:[44,22,1,0,0,22], 3:[70,17,2,0,0,18], 4:[100,24,2,0,0,26], 5:[134,15,2,16,2,18], 6:[172,19,4,0,0,24], 7:[196,14,2,15,4,18], 8:[242,18,4,19,2,22], 9:[292,16,4,17,4,20], 10:[346,19,6,20,2,24] },
    H: { 1:[26,9,1,0,0,17], 2:[44,16,1,0,0,28], 3:[70,13,2,0,0,22], 4:[100,9,4,0,0,16], 5:[134,11,2,12,2,22], 6:[172,15,4,0,0,28], 7:[196,13,4,14,1,26], 8:[242,14,4,15,2,26], 9:[292,12,4,13,4,24], 10:[346,15,6,16,2,28] }
  };

  var ALIGN = { 1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30],
                6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50] };
  var ECL_BITS = { L: 1, M: 0, Q: 3, H: 2 };

  function utf8Bytes(str) {
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
        var c2 = str.charCodeAt(++i);
        var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
        out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
      } else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return out;
  }

  /* ---- 数据码字 ---- */
  function dataCodewords(bytes, ver, ecl) {
    var cap = RS[ecl][ver][1] * RS[ecl][ver][2] + RS[ecl][ver][3] * RS[ecl][ver][4];
    var bits = [];
    function push(v, n) { for (var i = n - 1; i >= 0; i--) bits.push((v >> i) & 1); }
    push(4, 4);                                  // 字节模式
    push(bytes.length, ver <= 9 ? 8 : 16);       // 字符计数
    for (var i = 0; i < bytes.length; i++) push(bytes[i], 8);
    var total = cap * 8;
    var term = Math.min(4, total - bits.length);
    push(0, term);
    while (bits.length % 8 !== 0) bits.push(0);
    var cw = [];
    for (var b = 0; b < bits.length; b += 8) {
      var v = 0; for (var k = 0; k < 8; k++) v = (v << 1) | bits[b + k];
      cw.push(v);
    }
    var pad = [0xec, 0x11], pi = 0;
    while (cw.length < cap) cw.push(pad[pi++ % 2]);
    return cw;
  }

  function interleave(cw, ver, ecl) {
    var t = RS[ecl][ver], ecPer = t[5];
    var g1n = t[2], g1d = t[1], g2n = t[4], g2d = t[3];
    var blocks = [], idx = 0;
    for (var i = 0; i < g1n; i++) { blocks.push(cw.slice(idx, idx + g1d)); idx += g1d; }
    for (var j = 0; j < g2n; j++) { blocks.push(cw.slice(idx, idx + g2d)); idx += g2d; }
    var ecs = blocks.map(function (b) { return rsEncode(new Uint8Array(b), ecPer); });
    var out = [];
    var maxD = Math.max(g1d, g2d);
    for (var c = 0; c < maxD; c++)
      for (var b2 = 0; b2 < blocks.length; b2++)
        if (c < blocks[b2].length) out.push(blocks[b2][c]);
    for (var e = 0; e < ecPer; e++)
      for (var b3 = 0; b3 < blocks.length; b3++) out.push(ecs[b3][e]);
    return out;
  }

  /* ---- 模块布局 ---- */
  function makeMatrix(ver) {
    var n = ver * 4 + 17;
    var m = [], r = [], f = [];
    for (var i = 0; i < n; i++) {
      m.push(new Array(n).fill(false));
      r.push(new Array(n).fill(false));
      f.push(new Array(n).fill(false));   // 功能图形占位（不可放数据）
    }
    return { n: n, m: m, reserved: r, func: f };
  }
  function setF(mx, x, y, v) {
    if (x < 0 || y < 0 || x >= mx.n || y >= mx.n) return;
    mx.m[y][x] = v; mx.func[y][x] = true;
  }
  function finder(mx, cx, cy) {
    for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) {
      var d = Math.max(Math.abs(dx), Math.abs(dy));
      setF(mx, cx + dx, cy + dy, d !== 2 && d !== 4);
    }
  }
  function align(mx, cx, cy) {
    for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++)
      setF(mx, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }
  function setupFunction(mx, ver, ecl) {
    var n = mx.n;
    finder(mx, 3, 3); finder(mx, n - 4, 3); finder(mx, 3, n - 4);
    for (var i = 8; i < n - 8; i++) { setF(mx, i, 6, i % 2 === 0); setF(mx, 6, i, i % 2 === 0); }
    var ap = ALIGN[ver];
    for (var a = 0; a < ap.length; a++) for (var b = 0; b < ap.length; b++) {
      var cx = ap[a], cy = ap[b];
      if ((cx === 6 && cy === 6) || (cx === 6 && cy === n - 7) || (cx === n - 7 && cy === 6)) continue;
      align(mx, cx, cy);
    }
    setF(mx, 8, n - 8, true);       // 固定的黑模块
    // 预留格式信息区
    for (var k = 0; k <= 8; k++) {
      if (k !== 6) { setF(mx, k, 8, false); setF(mx, 8, k, false); }
    }
    for (var k2 = 0; k2 < 8; k2++) {
      setF(mx, n - 1 - k2, 8, false);
      setF(mx, 8, n - 1 - k2, false);
    }
    if (ver >= 7) {
      for (var v = 0; v < 6; v++) for (var w = 0; w < 3; w++) {
        setF(mx, n - 11 + w, v, false);
        setF(mx, v, n - 11 + w, false);
      }
    }
  }
  function placeData(mx, data) {
    var n = mx.n, bit = 0, up = true;
    for (var x = n - 1; x > 0; x -= 2) {
      if (x === 6) x = 5;
      for (var y0 = 0; y0 < n; y0++) {
        var y = up ? (n - 1 - y0) : y0;
        for (var c = 0; c < 2; c++) {
          var xx = x - c;
          if (mx.func[y][xx]) continue;
          var v = false;
          if (bit < data.length * 8) v = ((data[bit >> 3] >> (7 - (bit & 7))) & 1) === 1;
          mx.m[y][xx] = v; bit++;
        }
      }
      up = !up;
    }
  }
  function maskFn(k, x, y) {
    switch (k) {
      case 0: return (x + y) % 2 === 0;
      case 1: return y % 2 === 0;
      case 2: return x % 3 === 0;
      case 3: return (x + y) % 3 === 0;
      case 4: return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0;
      case 5: return ((x * y) % 2) + ((x * y) % 3) === 0;
      case 6: return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
      case 7: return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
    }
    return false;
  }
  function applyMask(mx, k) {
    for (var y = 0; y < mx.n; y++) for (var x = 0; x < mx.n; x++)
      if (!mx.func[y][x] && maskFn(k, x, y)) mx.m[y][x] = !mx.m[y][x];
  }
  function penalty(mx) {
    var n = mx.n, p = 0, y, x;
    for (y = 0; y < n; y++) {
      var run = 1;
      for (x = 1; x < n; x++) {
        if (mx.m[y][x] === mx.m[y][x - 1]) { run++; if (run === 5) p += 3; else if (run > 5) p++; }
        else run = 1;
      }
    }
    for (x = 0; x < n; x++) {
      var run2 = 1;
      for (y = 1; y < n; y++) {
        if (mx.m[y][x] === mx.m[y - 1][x]) { run2++; if (run2 === 5) p += 3; else if (run2 > 5) p++; }
        else run2 = 1;
      }
    }
    for (y = 0; y < n - 1; y++) for (x = 0; x < n - 1; x++) {
      var c = mx.m[y][x];
      if (c === mx.m[y][x + 1] && c === mx.m[y + 1][x] && c === mx.m[y + 1][x + 1]) p += 3;
    }
    var pat = [true, false, true, true, true, false, true, false, false, false, false];
    var pat2 = [false, false, false, false, true, false, true, true, true, false, true];
    function match(get) {
      for (var i = 0; i + 11 <= n; i++) {
        var ok1 = true, ok2 = true;
        for (var j = 0; j < 11; j++) {
          if (get(i + j) !== pat[j]) ok1 = false;
          if (get(i + j) !== pat2[j]) ok2 = false;
          if (!ok1 && !ok2) break;
        }
        if (ok1) p += 40; if (ok2) p += 40;
      }
    }
    for (y = 0; y < n; y++) match((function (yy) { return function (i) { return mx.m[yy][i]; }; })(y));
    for (x = 0; x < n; x++) match((function (xx) { return function (i) { return mx.m[i][xx]; }; })(x));
    var dark = 0;
    for (y = 0; y < n; y++) for (x = 0; x < n; x++) if (mx.m[y][x]) dark++;
    var pct = dark * 100 / (n * n);
    p += Math.floor(Math.abs(pct - 50) / 5) * 10;
    return p;
  }
  function writeFormat(mx, ecl, mask) {
    var n = mx.n;
    var data = (ECL_BITS[ecl] << 3) | mask;
    var rem = data;
    for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
    var bits = ((data << 10) | rem) ^ 0x5412;
    for (var j = 0; j <= 5; j++) setF(mx, 8, j, ((bits >> j) & 1) === 1);
    setF(mx, 8, 7, ((bits >> 6) & 1) === 1);
    setF(mx, 8, 8, ((bits >> 7) & 1) === 1);
    setF(mx, 7, 8, ((bits >> 8) & 1) === 1);
    for (var k = 9; k <= 14; k++) setF(mx, 14 - k, 8, ((bits >> k) & 1) === 1);
    for (var m = 0; m <= 7; m++) setF(mx, n - 1 - m, 8, ((bits >> m) & 1) === 1);
    for (var q = 8; q <= 14; q++) setF(mx, 8, n - 15 + q, ((bits >> q) & 1) === 1);
    setF(mx, 8, n - 8, true);
  }
  function writeVersion(mx, ver) {
    if (ver < 7) return;
    var rem = ver;
    for (var i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >> 11) * 0x1f25);
    var bits = (ver << 12) | rem;
    var n = mx.n;
    for (var j = 0; j < 18; j++) {
      var b = ((bits >> j) & 1) === 1;
      var a = Math.floor(j / 3), c = j % 3;
      setF(mx, n - 11 + c, a, b);
      setF(mx, a, n - 11 + c, b);
    }
  }

  /* ---- 主入口 ---- */
  function encode(text, ecl) {
    ecl = ecl || 'M';
    var bytes = utf8Bytes(text);
    var ver = 0;
    for (var v = 1; v <= 10; v++) {
      var capBits = (RS[ecl][v][1] * RS[ecl][v][2] + RS[ecl][v][3] * RS[ecl][v][4]) * 8;
      var ccBits = v <= 9 ? 8 : 16;
      if (4 + ccBits + bytes.length * 8 <= capBits) { ver = v; break; }
    }
    if (!ver) throw new Error('内容过长（仅支持到版本 10）');

    var cw = dataCodewords(bytes, ver, ecl);
    var full = interleave(cw, ver, ecl);
    var best = null, bestP = Infinity;
    for (var mk = 0; mk < 8; mk++) {
      var mx = makeMatrix(ver);
      setupFunction(mx, ver, ecl);
      placeData(mx, full);
      applyMask(mx, mk);
      writeFormat(mx, ecl, mk);
      writeVersion(mx, ver);
      var p = penalty(mx);
      if (p < bestP) { bestP = p; best = mx; }
    }
    return { size: best.n, modules: best.m, version: ver };
  }

  root.QR = { encode: encode };
})(typeof window !== 'undefined' ? window : globalThis);
