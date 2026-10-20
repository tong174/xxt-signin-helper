#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make_icon.py — 零依赖生成 App 图标（手写 PNG 编码 + 超采样抗锯齿）

设计：蓝色渐变圆角方块 + 白色圆环 + 对勾（签到完成意象）
输出：
  res/mipmap-*/ic_launcher.png      完整图标（旧系统 / 兜底）
  res/mipmap-*/ic_fg.png            前景（透明，adaptive icon 用）
  res/mipmap-anydpi-v26/ic_launcher.xml  adaptive icon 描述
  res/mipmap-anydpi-v26/ic_launcher_round.xml
  res/values/colors.xml             背景色
"""
import math, os, struct, zlib

APP = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(APP, "res")

SS = 4  # 超采样倍率


# ---------------------------------------------------------------- PNG 编码
def png_bytes(w, h, rgba):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)                      # filter: None
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


# ---------------------------------------------------------------- 距离场
def sd_round_rect(px, py, cx, cy, hw, hh, r):
    dx = abs(px - cx) - (hw - r)
    dy = abs(py - cy) - (hh - r)
    ax, ay = max(dx, 0.0), max(dy, 0.0)
    return math.hypot(ax, ay) + min(max(dx, dy), 0.0) - r


def sd_seg(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    d = vx * vx + vy * vy
    t = 0.0 if d == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / d))
    return math.hypot(wx - t * vx, wy - t * vy)


def mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(3))


# ---------------------------------------------------------------- 绘制
C_TOP = (0x5B, 0x8D, 0xFF)     # 亮蓝
C_BOT = (0x2B, 0x4F, 0xD6)     # 深蓝
C_ACC = (0x7B, 0xE0, 0xFF)     # 高光青


def render(size, with_bg=True, alpha_bg=True):
    """返回 RGBA bytearray。size 为最终边长。"""
    S = size * SS
    buf = bytearray(S * S * 4)

    cx = cy = S / 2.0
    hw = hh = S / 2.0
    rad = S * 0.235                       # 圆角半径（Android 惯例 ~23%）
    cxr = S * 0.5

    # 环 + 勾 几何（相对 S 的比例）
    ring_r = S * 0.255
    ring_w = S * 0.058
    ring_cy = cy - S * 0.012

    # 勾的三个点
    p1 = (cx - S * 0.105, ring_cy + S * 0.006)
    p2 = (cx - S * 0.020, ring_cy + S * 0.098)
    p3 = (cx + S * 0.118, ring_cy - S * 0.082)
    mark_w = S * 0.050

    for y in range(S):
        fy = y + 0.5
        row = y * S * 4
        for x in range(S):
            fx = x + 0.5

            # ---- 底：圆角矩形 + 渐变
            d_bg = sd_round_rect(fx, fy, cx, cy, hw, hh, rad)
            if d_bg > 0:
                continue
            if with_bg:
                t = (fx / S * 0.35 + fy / S * 0.65)
                # 左上到右下
                col = mix(C_TOP, C_BOT, min(1.0, max(0.0, t)))
                # 左上角柔光
                glow = max(0.0, 1.0 - math.hypot(fx - S * 0.24, fy - S * 0.20) / (S * 0.62))
                col = mix(col, C_ACC, glow * 0.30)
            else:
                col = None          # ★ 前景模式：底保持透明，只画白色图形

            a = 255

            # ---- 白色圆环
            d_ring = abs(math.hypot(fx - cx, fy - ring_cy) - ring_r) - ring_w / 2.0
            if d_ring < 0:
                col = (255, 255, 255)

            # ---- 对勾（盖在环上）
            d_mark = min(sd_seg(fx, fy, p1[0], p1[1], p2[0], p2[1]),
                         sd_seg(fx, fy, p2[0], p2[1], p3[0], p3[1]))
            if d_mark < mark_w / 2.0:
                col = (255, 255, 255)
            elif d_mark < mark_w / 2.0 + S * 0.006:
                # 勾边缘一点点青色描边
                base = col if col else C_BOT
                col = mix(base, C_ACC, 0.75)

            # ---- 环外右下角小圆点（装饰）
            dd = math.hypot(fx - (cx + ring_r * 0.93), fy - (ring_cy + ring_r * 0.93))
            if dd < S * 0.020:
                col = (255, 255, 255)

            if col is None:
                continue

            o = row + x * 4
            buf[o] = col[0]
            buf[o + 1] = col[1]
            buf[o + 2] = col[2]
            buf[o + 3] = a

    # ---- 降采样（SS x SS 平均）
    out = bytearray(size * size * 4)
    n = SS * SS
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                base = ((y * SS + dy) * S + x * SS) * 4
                for dx in range(SS):
                    o = base + dx * 4
                    r += buf[o]; g += buf[o + 1]; b += buf[o + 2]; a += buf[o + 3]
            o = (y * size + x) * 4
            out[o] = r // n; out[o + 1] = g // n; out[o + 2] = b // n; out[o + 3] = a // n
    return out


DENS = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
        ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]


def main():
    # 完整图标
    for name, ic, _fg in DENS:
        d = os.path.join(OUT, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        px = render(ic, with_bg=True)
        open(os.path.join(d, "ic_launcher.png"), "wb").write(png_bytes(ic, ic, px))
        print("  ic_launcher  %-9s %dx%d" % (name, ic, ic))
        # round 版（同图，系统按需裁剪）
        open(os.path.join(d, "ic_launcher_round.png"), "wb").write(png_bytes(ic, ic, px))

    # adaptive 前景：画布 108dp，图形居中占 66dp 安全区
    for name, _ic, fg in DENS:
        d = os.path.join(OUT, "mipmap-" + name)
        # render() 里图形恒定占画布约 56.8%，这里放大 1.11 倍再裁中心，
        # 使最终图形约占 108dp 画布的 63%（落在 adaptive icon 安全区内）
        S = int(fg * 1.11)
        full = render(S, with_bg=False)
        pad = int((S - fg) / 2)
        out = bytearray(fg * fg * 4)
        for y in range(fg):
            src = ((y + pad) * S + pad) * 4
            dst = y * fg * 4
            out[dst:dst + fg * 4] = full[src:src + fg * 4]
        open(os.path.join(d, "ic_fg.png"), "wb").write(png_bytes(fg, fg, out))
        print("  ic_fg        %-9s %dx%d" % (name, fg, fg))

    # adaptive icon XML
    d = os.path.join(OUT, "mipmap-anydpi-v26")
    os.makedirs(d, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_bg" />\n'
           '    <foreground android:drawable="@mipmap/ic_fg" />\n'
           '</adaptive-icon>\n')
    open(os.path.join(d, "ic_launcher.xml"), "w", encoding="utf-8").write(xml)
    open(os.path.join(d, "ic_launcher_round.xml"), "w", encoding="utf-8").write(xml)
    print("  adaptive xml  已生成")

    # 背景色
    d = os.path.join(OUT, "values")
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "colors.xml"), "w", encoding="utf-8").write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <color name="ic_launcher_bg">#2B4FD6</color>\n'
        '</resources>\n')
    print("  colors.xml    已生成")
    print("\n图标已输出到", OUT)


if __name__ == "__main__":
    main()
