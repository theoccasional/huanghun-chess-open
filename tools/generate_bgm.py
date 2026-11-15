#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
黄昏象棋公开版背景音生成器
- bg.mp3      暮色：原创凄惨长循环（D 小调五声，低频 drone + 古筝拨弦 + 二胡长音 + 风噪 + 钟）
- bgm2.ogg    古调·茉莉：公有领域民歌《茉莉花》旋律的原创编配（古筝/箫/低音）
- bgm1.ogg    寒江：原创备选（稀疏古筝 + 水风声 + 低音）

全部为程序合成，不使用任何第三方录音、采样或音源；可复现。
"""
import math
import os
import subprocess
import tempfile
import wave
import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "assets", "snd")
RNG = np.random.default_rng(20261115)


def midi(n):
    return 440.0 * (2.0 ** ((n - 69) / 12.0))


def env_adsr(n, attack=0.02, decay=0.2, sustain=0.6, release=0.6):
    a = max(1, int(attack * SR)); d = max(1, int(decay * SR)); r = max(1, int(release * SR))
    s = max(1, n - a - d - r)
    e = np.concatenate([np.linspace(0, 1, a, endpoint=False),
                        np.linspace(1, sustain, d, endpoint=False),
                        np.full(s, sustain),
                        np.linspace(sustain, 0, r)])
    return e[:n] if len(e) >= n else np.pad(e, (0, n - len(e)))


def pan_gains(p):
    p = max(-1.0, min(1.0, p))
    return math.sqrt((1 - p) / 2), math.sqrt((1 + p) / 2)


def add(buf, sig, at, pan=0.0, gain=1.0):
    i = int(at * SR)
    if i >= len(buf): return
    sig = sig[:len(buf) - i]
    gl, gr = pan_gains(pan)
    buf[i:i + len(sig), 0] += sig * gl * gain
    buf[i:i + len(sig), 1] += sig * gr * gain


def pluck(freq, dur, amp=0.4, brightness=1.0):
    """古筝/琵琶式拨弦：谐波叠加 + 快速起音 + 指数衰减。"""
    n = max(1, int(dur * SR)); t = np.arange(n) / SR
    sig = np.zeros(n)
    for k, w in [(1, 1.0), (2, 0.45 * brightness), (3, 0.22 * brightness),
                 (4, 0.12 * brightness), (5, 0.06 * brightness)]:
        det = 1.0 + (k - 1) * 0.0007
        sig += w * np.sin(2 * math.pi * freq * det * k * t) * np.exp(-t * (2.2 + 1.1 * k))
    sig *= (1 - np.exp(-t * 180)) * amp
    return sig


def erhu(freq, dur, amp=0.22):
    """二胡式长音：基频 + 少量谐波 + 5Hz 揉弦 + 慢起音。"""
    n = max(1, int(dur * SR)); t = np.arange(n) / SR
    vib = 1 + 0.0045 * np.sin(2 * math.pi * 5.0 * t) * np.clip(t / 0.8, 0, 1)
    sig = (np.sin(2 * math.pi * freq * vib * t)
           + 0.28 * np.sin(2 * math.pi * freq * 2 * vib * t)
           + 0.10 * np.sin(2 * math.pi * freq * 3 * vib * t))
    sig *= env_adsr(n, 0.35, 0.4, 0.55, 0.9) * amp
    return sig


def drone(freq, dur, amp=0.12):
    n = max(1, int(dur * SR)); t = np.arange(n) / SR
    lfo = 0.75 + 0.25 * np.sin(2 * math.pi * 0.035 * t + 0.7)
    sig = (np.sin(2 * math.pi * freq * t)
           + 0.35 * np.sin(2 * math.pi * freq * 0.5 * t + 0.3)
           + 0.15 * np.sin(2 * math.pi * freq * 1.5 * t + 1.1))
    sig *= lfo * amp
    return sig


def wind(dur, amp=0.06, cutoff=0.012):
    n = int(dur * SR)
    x = RNG.standard_normal(n)
    y = np.empty(n)
    acc = 0.0
    for i in range(n):
        acc += cutoff * (x[i] - acc)
        y[i] = acc
    y /= (np.max(np.abs(y)) + 1e-9)
    t = np.arange(n) / SR
    swell = 0.45 + 0.55 * (0.5 + 0.5 * np.sin(2 * math.pi * 0.018 * t + 1.2))
    return y * swell * amp


def bell(freq, dur=3.0, amp=0.10):
    n = int(dur * SR); t = np.arange(n) / SR
    sig = (np.sin(2 * math.pi * freq * t) * np.exp(-t * 1.8)
           + 0.5 * np.sin(2 * math.pi * freq * 2.01 * t) * np.exp(-t * 2.8))
    return sig * amp


def reverb_stereo(buf, delays=((0.13, 0.28), (0.23, 0.20), (0.37, 0.13), (0.53, 0.08))):
    out = buf.copy()
    for d, g in delays:
        k = int(d * SR)
        if k <= 0: continue
        out[k:, 0] += buf[:-k, 0] * g
        out[k:, 1] += buf[:-k, 1] * g * 0.92
    return out


def normalize(buf, peak=0.86):
    m = float(np.max(np.abs(buf))) or 1.0
    buf *= peak / m
    return np.tanh(buf * 1.05) * 0.96


def write_wav(buf, path):
    data = (np.clip(buf, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(data.tobytes())


def base_bed(dur, root=50, fifth=57, wind_amp=0.05):
    n = int(dur * SR)
    buf = np.zeros((n, 2), dtype=np.float64)
    add(buf, drone(midi(root), dur, 0.10), 0, 0, 1.0)
    add(buf, drone(midi(fifth), dur, 0.055), 0, 0.15, 1.0)
    add(buf, wind(dur, wind_amp), 0, -0.2, 1.0)
    add(buf, wind(dur, wind_amp * 0.7), 0, 0.35, 1.0)
    return buf


def fade_edges(buf, sec=1.6):
    n = int(sec * SR)
    if n * 2 >= len(buf): return buf
    r = np.linspace(0, 1, n)[:, None]
    buf[:n] *= r
    buf[-n:] *= r[::-1]
    return buf


def render_somber(path):
    dur = 180.0
    buf = base_bed(dur, 50, 57, 0.055)  # D2 / A2
    # 低音大提琴式长音，三段变化
    for start, length, note, pan, amp in [
        (4, 36, 38, -0.15, 0.10), (48, 44, 41, 0.10, 0.09),
        (100, 40, 38, -0.1, 0.10), (146, 30, 46, 0.15, 0.07)]:
        add(buf, erhu(midi(note), length, amp), start, pan, 1.0)
    # 古筝主题：D 小调五声，长句+停顿，避免短循环
    melody = [
        (0.0, 74, 2.2, -0.3), (3.0, 69, 1.6, 0.25), (5.5, 72, 2.8, -0.1),
        (10.0, 65, 2.4, 0.3), (14.0, 69, 3.0, -0.25), (19.0, 74, 2.0, 0.2),
        (24.0, 72, 3.2, -0.3), (29.5, 69, 2.0, 0.1), (33.0, 65, 2.4, -0.2),
        (62.0, 69, 2.0, 0.3), (66.0, 72, 2.6, -0.25), (71.0, 77, 2.2, 0.1),
        (76.0, 74, 3.0, -0.3), (82.0, 69, 2.2, 0.2), (87.0, 65, 2.6, -0.1),
        (118.0, 74, 3.0, -0.25), (124.0, 77, 2.0, 0.25), (128.0, 81, 2.6, -0.1),
        (134.0, 79, 3.2, 0.2), (141.0, 74, 2.4, -0.3), (147.0, 69, 3.0, 0.1),
        (154.0, 65, 2.4, -0.2), (160.0, 69, 3.2, 0.25),
    ]
    for at, note, length, pan in melody:
        add(buf, pluck(midi(note), length, 0.30, 0.9), at, pan, 1.0)
    # 稀疏钟声
    for at, note, pan in [(12.5, 86, 0.4), (55.0, 81, -0.35), (108.0, 86, 0.3), (172.0, 81, -0.4)]:
        add(buf, bell(midi(note), 4.0, 0.075), at, pan, 1.0)
    buf = reverb_stereo(buf)
    return fade_edges(normalize(buf), 1.8)


def render_classical(path):
    # 公有领域民歌《茉莉花》的简化五声旋律，慢速古筝/箫编配；编曲与录音为本项目原创。
    dur = 150.0
    buf = base_bed(dur, 43, 50, 0.045)  # G2 / D3
    # 旋律音（G 大调五声：G A B D E）
    phrase = [
        (62, 1.6), (62, 0.8), (66, 1.6), (69, 1.2), (71, 2.0),
        (71, 0.8), (69, 1.2), (66, 1.6), (66, 1.2), (69, 2.4),
        (66, 1.2), (62, 1.6), (59, 1.2), (62, 2.8),
    ]
    # 四句，逐句加花/换音区
    starts = [2, 38, 75, 112]
    for si, st in enumerate(starts):
        t = st
        for j, (note, d) in enumerate(phrase):
            n = note + (12 if si == 2 and j % 3 == 0 else 0)
            add(buf, pluck(midi(n), max(1.4, d * 1.4), 0.26, 0.75), t, -0.25 + 0.5 * ((j % 3) / 2), 1.0)
            t += d * 1.55
        # 箫/二胡长音铺底
        add(buf, erhu(midi(62 + (12 if si == 3 else 0)), 10.0, 0.055), st + 1.0, 0.15, 1.0)
    # 段落之间的古筝刮奏感
    for at in [35, 72, 109, 145]:
        for k in range(6):
            add(buf, pluck(midi(62 + [0, 2, 4, 7, 9, 12][k]), 0.7, 0.12, 0.6),
                at + k * 0.16, -0.35 + k * 0.12, 1.0)
    buf = reverb_stereo(buf, ((0.17, 0.22), (0.29, 0.14), (0.43, 0.08)))
    return fade_edges(normalize(buf), 1.6)


def render_backup(path):
    dur = 120.0
    buf = base_bed(dur, 45, 52, 0.07)  # A2 / E3
    # 稀疏拨弦，像江面偶起的涟漪
    notes = [(3, 69, 0.3), (11, 72, -0.2), (19, 76, 0.4), (27, 72, -0.3),
             (37, 69, 0.2), (47, 64, -0.4), (58, 69, 0.3), (67, 72, -0.1),
             (78, 76, 0.35), (89, 72, -0.3), (99, 69, 0.2), (108, 64, -0.2)]
    for at, note, pan in notes:
        add(buf, pluck(midi(note), 3.0, 0.22, 0.65), at, pan, 1.0)
    # 低频水声/风声起伏
    for at, note, pan in [(15, 57, 0.2), (55, 52, -0.2), (95, 57, 0.3)]:
        add(buf, erhu(midi(note), 18.0, 0.04), at, pan, 1.0)
    buf = reverb_stereo(buf, ((0.19, 0.24), (0.31, 0.15), (0.47, 0.09)))
    return fade_edges(normalize(buf), 1.5)


def encode(wav, out, fmt, title):
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", wav,
           "-metadata", f"title={title}", "-metadata", "artist=黄昏象棋",
           "-metadata", "comment=Program-generated original; no third-party recording"]
    if fmt == "mp3":
        cmd += ["-codec:a", "libmp3lame", "-b:a", "192k"]
    else:
        cmd += ["-codec:a", "libvorbis", "-q:a", "5"]
    cmd += [out]
    subprocess.run(cmd, check=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    tracks = [
        ("bg.mp3", "mp3", "暮色", render_somber),
        ("bgm2.ogg", "ogg", "古调·茉莉", render_classical),
        ("bgm1.ogg", "ogg", "寒江", render_backup),
    ]
    for filename, fmt, title, renderer in tracks:
        print("rendering", filename, "...", flush=True)
        buf = renderer(None)
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
            tmp = tf.name
        write_wav(buf, tmp)
        encode(tmp, os.path.join(OUT, filename), fmt, title)
        os.unlink(tmp)
        print("wrote", os.path.join(OUT, filename), flush=True)


if __name__ == "__main__":
    main()