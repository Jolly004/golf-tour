"""Synthesises every Golf Tour sound effect and writes OGG Vorbis files plus sounds.json.

No recorded or third-party audio is used: clubs, ball, crowd and ambience are all built from noise,
sines and filters here, so the set can be regenerated or tweaked at any time:
    python tools/gen_sounds.py
"""
import json
import os

import numpy as np
import soundfile as sf
from scipy.signal import butter, sosfilt

SR = 44100
ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "golftour")
rng = np.random.default_rng(2026)


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def noise(dur):
    return rng.standard_normal(int(SR * dur))


def brown(dur):
    b = np.cumsum(rng.standard_normal(int(SR * dur)))
    b -= np.convolve(b, np.ones(2000) / 2000, mode="same")
    return b / (np.max(np.abs(b)) + 1e-9)


def bp(x, lo, hi, order=3):
    return sosfilt(butter(order, [lo, hi], btype="band", fs=SR, output="sos"), x)


def lp(x, f, order=3):
    return sosfilt(butter(order, f, btype="low", fs=SR, output="sos"), x)


def hp(x, f, order=3):
    return sosfilt(butter(order, f, btype="high", fs=SR, output="sos"), x)


def decay(t, tau, attack=0.0005):
    a = np.clip(t / attack, 0, 1) if attack > 0 else 1
    return a * np.exp(-t / tau)


def tone(t, f, tau, amp=1.0, phase=0.0):
    return amp * np.sin(2 * np.pi * f * t + phase) * np.exp(-t / tau)


def pad(x, dur):
    out = np.zeros(int(SR * dur))
    out[: min(len(x), len(out))] = x[: len(out)]
    return out


def place(buf, x, at):
    i = int(at * SR)
    n = min(len(x), len(buf) - i)
    if n > 0:
        buf[i:i + n] += x[:n]


def norm(x, peak=0.9):
    x = x - np.mean(x)
    m = np.max(np.abs(x)) + 1e-9
    fade = min(len(x), int(0.01 * SR))
    x[-fade:] *= np.linspace(1, 0, fade)
    return x / m * peak


def save(rel, x, peak=0.9):
    path = os.path.join(ROOT, "sounds", rel + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = norm(x, peak).astype(np.float32)
    # libsndfile's Vorbis encoder overflows the stack on Windows with large writes, so stream it in blocks.
    with sf.SoundFile(path, "w", SR, 1, format="OGG", subtype="VORBIS") as f:
        for i in range(0, len(data), 1024):
            f.write(data[i:i + 1024])


# ------------------------------------------------------------------ clubs

def driver(variant):
    t = t_axis(0.6)
    click = hp(noise(0.6), 3500) * decay(t, 0.0025) * 0.9
    ring = sum(tone(t, f * (1 + 0.01 * variant), d, a) for f, a, d in
               [(2950, 0.55, 0.07), (4320, 0.40, 0.055), (6550, 0.28, 0.035), (8150, 0.16, 0.022)])
    thump = tone(t, 150, 0.03, 0.7)
    air = bp(noise(0.6), 900, 3200) * decay(t, 0.13, 0.01) * 0.18
    return click + ring + thump + air


def iron(variant, turf=0.35):
    t = t_axis(0.45)
    click = bp(noise(0.45), 2200, 7500) * decay(t, 0.004) * 1.1
    body = tone(t, 1850 + 60 * variant, 0.03, 0.45) + tone(t, 3300, 0.018, 0.25)
    thump = tone(t, 190, 0.025, 0.6)
    divot = lp(noise(0.45), 2600) * decay(t, 0.09, 0.006) * turf
    return click + body + thump + divot


def putter(variant):
    t = t_axis(0.3)
    return tone(t, 1040 + 30 * variant, 0.035, 0.8) + tone(t, 2380, 0.014, 0.35) + hp(noise(0.3), 3000) * decay(t, 0.0015) * 0.25


def sand_shot(variant):
    t = t_axis(0.9)
    spray = lp(noise(0.9), 2000 + 300 * variant) * decay(t, 0.22, 0.004)
    grit = bp(noise(0.9), 3000, 9000) * decay(t, 0.35, 0.03) * 0.25
    thump = tone(t, 95, 0.06, 0.8)
    return spray + grit + thump


def whoosh(variant):
    t = t_axis(0.55)
    env = np.exp(-((t - 0.30) / (0.07 + 0.01 * variant)) ** 2)
    lo = bp(noise(0.55), 350, 1200) * env
    hi = bp(noise(0.55), 1200, 3800) * np.exp(-((t - 0.33) / 0.05) ** 2) * 0.6
    return lo + hi


# ------------------------------------------------------------------ ball

def land(kind, variant):
    t = t_axis(0.3)
    if kind == "grass":
        f0 = 80 + 8 * variant
        body = np.sin(2 * np.pi * f0 * t * (1 + 0.5 * np.exp(-t / 0.02))) * decay(t, 0.05)
        return body + lp(noise(0.3), 900) * decay(t, 0.03) * 0.6
    if kind == "green":
        body = np.sin(2 * np.pi * (115 + 10 * variant) * t) * decay(t, 0.035)
        return body + lp(noise(0.3), 1500) * decay(t, 0.015) * 0.4
    if kind == "sand":
        return lp(noise(0.3), 1100) * decay(t, 0.09, 0.002) + tone(t, 70, 0.04, 0.4)
    # hard
    return hp(noise(0.3), 2500) * decay(t, 0.003) + tone(t, 2250 + 100 * variant, 0.025, 0.6) + tone(t, 520, 0.02, 0.3)


def tree(variant):
    t = t_axis(0.8)
    knock = tone(t, 520 + 20 * variant, 0.05, 0.8) + tone(t, 1280, 0.03, 0.4) + tone(t, 2100, 0.02, 0.25)
    flutter = (np.sin(2 * np.pi * 23 * t) * 0.5 + 0.5) * (rng.random(len(t)) * 0.4 + 0.6)
    leaves = bp(noise(0.8), 2000, 7500) * flutter * decay(t, 0.25, 0.02) * 0.7
    return knock + leaves


def splash(variant):
    dur = 1.2
    t = t_axis(dur)
    body = lp(noise(dur), 3500) * decay(t, 0.28, 0.006)
    out = body + tone(t, 70, 0.08, 0.5)
    for _ in range(18 + 4 * variant):
        at = rng.uniform(0.08, 0.8)
        bt = t_axis(0.05)
        f0 = rng.uniform(700, 2400)
        bubble = np.sin(2 * np.pi * f0 * bt * (1 + 6 * bt)) * decay(bt, 0.012) * rng.uniform(0.1, 0.3)
        place(out, bubble, at)
    return out


def cup():
    dur = 0.7
    out = np.zeros(int(SR * dur))
    for at, amp, f in [(0.0, 1.0, 2600), (0.075, 0.6, 3100), (0.125, 0.45, 2800), (0.16, 0.3, 3300), (0.19, 0.2, 2900)]:
        t = t_axis(0.12)
        hit = hp(noise(0.12), 2500) * decay(t, 0.002) * 0.6 + tone(t, f, 0.03, amp) + tone(t, f * 1.52, 0.02, amp * 0.4)
        place(out, hit * amp, at)
    t = t_axis(0.2)
    place(out, tone(t, 380, 0.05, 0.8) + lp(noise(0.2), 600) * decay(t, 0.03) * 0.4, 0.23)
    return out


def lip():
    t = t_axis(0.6)
    clack = tone(t, 2700, 0.03, 0.8) + hp(noise(0.6), 2500) * decay(t, 0.002) * 0.5
    roll = lp(noise(0.6), 900) * decay(t, 0.25, 0.05) * 0.25
    return clack + roll


# ------------------------------------------------------------------ crowd

def clap_bank(n=40):
    bank = []
    for _ in range(n):
        t = t_axis(0.05)
        lo = rng.uniform(700, 1400)
        bank.append(bp(noise(0.05), lo, lo * rng.uniform(2.2, 3.5)) * decay(t, rng.uniform(0.006, 0.014), 0.0008))
    return bank


def applause(dur, peak_rate, rise, hold):
    out = np.zeros(int(SR * dur))
    bank = clap_bank()
    time = 0.0
    while time < dur:
        if time < rise:
            rate = peak_rate * (time / rise) ** 0.7 + 1
        elif time < rise + hold:
            rate = peak_rate
        else:
            rate = peak_rate * max(0.0, 1 - (time - rise - hold) / (dur - rise - hold)) ** 1.5 + 0.5
        time += rng.exponential(1.0 / rate)
        place(out, bank[rng.integers(len(bank))] * rng.uniform(0.3, 1.0), time)
    t = t_axis(dur)
    env = np.clip(t / rise, 0, 1) * np.clip((dur - t) / (dur - rise - hold), 0, 1)
    return out + lp(noise(dur), 1500) * env * 0.05


def voices(dur, count, f_lo, f_hi, formants, glide=0.0, onset=0.25, release=0.9):
    t = t_axis(dur)
    out = np.zeros(len(t))
    for _ in range(count):
        f0 = rng.uniform(f_lo, f_hi)
        start = rng.uniform(0, 0.25)
        vib = 1 + 0.012 * np.sin(2 * np.pi * rng.uniform(4, 7) * t + rng.uniform(0, 6))
        f = f0 * vib * (1 - glide * np.clip((t - start) / (dur - start), 0, 1))
        phase = np.cumsum(f) / SR
        saw = 2 * (phase % 1.0) - 1
        env = np.clip((t - start) / onset, 0, 1) * np.clip((dur - t) / release, 0, 1)
        out += saw * env * rng.uniform(0.5, 1.0)
    shaped = sum(bp(out, lo, hi, 2) * g for lo, hi, g in formants)
    return lp(shaped, 3500)


def cheer():
    dur = 4.0
    roar = voices(dur, 70, 170, 340, [(550, 950, 1.0), (1000, 1500, 0.6), (2300, 3000, 0.25)], glide=-0.08, onset=0.15, release=1.8)
    return norm(roar, 0.8) + applause(dur, 70, 0.25, 1.8) * 0.9


def groan():
    dur = 1.8
    return voices(dur, 50, 140, 260, [(250, 450, 1.0), (650, 950, 0.5)], glide=0.22, onset=0.12, release=0.9)


# ------------------------------------------------------------------ ambience and UI

def course_loop():
    dur = 20.0
    t = t_axis(dur)
    wind = lp(brown(dur), 450) * (0.6 + 0.4 * np.sin(2 * np.pi * 0.11 * t) * np.sin(2 * np.pi * 0.047 * t + 1))
    rustle = hp(noise(dur), 3000) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.23 * t + 2)) ** 3 * 0.03
    x = wind / (np.max(np.abs(wind)) + 1e-9) + rustle
    fade = int(SR * 1.5)
    x[:fade] = x[:fade] * np.linspace(0, 1, fade) + x[-fade:] * np.linspace(1, 0, fade)
    return x[:-fade]


def bird(variant):
    dur = 2.4
    out = np.zeros(int(SR * dur))
    at = 0.05
    style = variant % 3
    for _ in range(rng.integers(3, 7)):
        if style == 0:  # rising tweet
            d = rng.uniform(0.06, 0.11)
            t = t_axis(d)
            f = np.linspace(rng.uniform(2800, 3400), rng.uniform(4200, 5200), len(t))
        elif style == 1:  # warble
            d = rng.uniform(0.15, 0.25)
            t = t_axis(d)
            f = 3800 + 700 * np.sin(2 * np.pi * rng.uniform(25, 40) * t)
        else:  # falling whistle
            d = rng.uniform(0.18, 0.3)
            t = t_axis(d)
            f = np.linspace(rng.uniform(4800, 5400), rng.uniform(2600, 3200), len(t))
        phase = np.cumsum(f) / SR
        env = np.sin(np.pi * np.clip(t / d, 0, 1)) ** 1.5
        place(out, np.sin(2 * np.pi * phase) * env * rng.uniform(0.5, 1.0), at)
        at += d + rng.uniform(0.04, 0.25)
        if at > dur - 0.35:
            break
    return out


def ui_tick(f):
    t = t_axis(0.08)
    return tone(t, f, 0.012, 1.0) + tone(t, f * 2, 0.006, 0.3)


def chime():
    t = t_axis(1.6)
    out = np.zeros(len(t))
    for start, f in [(0.0, 784.0), (0.14, 1175.0)]:
        tt = t_axis(1.6 - start)
        bell = sum(tone(tt, f * r, 0.45 / (i + 1) ** 0.5, 1 / (i + 1)) for i, r in enumerate([1, 2.0, 2.76, 5.4]))
        place(out, bell, start)
    return out


# ------------------------------------------------------------------ write everything

def main():
    events = {}

    def event(name, files, subtitle=None, **extra):
        entry = {"sounds": [dict({"name": f"golftour:{f}"}, **extra) if extra else f"golftour:{f}" for f in files]}
        if subtitle:
            entry["subtitle"] = subtitle
        events[name] = entry

    for v in range(3):
        save(f"club/driver_{v}", driver(v))
        save(f"club/iron_{v}", iron(v))
        save(f"club/wedge_{v}", iron(v, turf=0.6))
        save(f"club/putter_{v}", putter(v), 0.7)
        save(f"club/sand_{v}", sand_shot(v))
        save(f"swing/whoosh_{v}", whoosh(v), 0.6)
        for kind in ["grass", "green", "sand", "hard"]:
            save(f"ball/land_{kind}_{v}", land(kind, v), 0.7)
        save(f"ball/tree_{v}", tree(v), 0.8)
        save(f"ball/splash_{v}", splash(v), 0.85)
        save(f"ambient/bird_{v}", bird(v), 0.35)
    save("ball/cup", cup())
    save("ball/lip", lip(), 0.8)
    save("crowd/applause_0", applause(3.5, 55, 0.3, 1.4), 0.75)
    save("crowd/applause_1", applause(2.5, 35, 0.25, 0.8), 0.6)
    save("crowd/cheer_0", cheer(), 0.9)
    save("crowd/cheer_1", cheer(), 0.9)
    save("crowd/groan_0", groan(), 0.7)
    save("crowd/groan_1", groan(), 0.7)
    save("ambient/course_loop", course_loop(), 0.35)
    save("ui/meter_tick", ui_tick(1800), 0.5)
    save("ui/meter_lock", ui_tick(1250) + np.pad(ui_tick(2500), (int(0.03 * SR), 0))[: int(0.08 * SR)], 0.5)
    save("ui/hole_start", chime(), 0.6)

    three = lambda base: [f"{base}_{v}" for v in range(3)]
    event("club.driver", three("club/driver"), "subtitles.golftour.club")
    event("club.iron", three("club/iron"), "subtitles.golftour.club")
    event("club.wedge", three("club/wedge"), "subtitles.golftour.club")
    event("club.putter", three("club/putter"), "subtitles.golftour.putt")
    event("club.sand", three("club/sand"), "subtitles.golftour.sand")
    event("swing.whoosh", three("swing/whoosh"), "subtitles.golftour.swing")
    for kind in ["grass", "green", "sand", "hard"]:
        event(f"ball.land_{kind}", three(f"ball/land_{kind}"), "subtitles.golftour.land")
    event("ball.tree", three("ball/tree"), "subtitles.golftour.tree")
    event("ball.splash", three("ball/splash"), "subtitles.golftour.splash")
    event("ball.cup", ["ball/cup"], "subtitles.golftour.cup")
    event("ball.lip", ["ball/lip"], "subtitles.golftour.lip")
    event("crowd.applause", ["crowd/applause_0", "crowd/applause_1"], "subtitles.golftour.applause")
    event("crowd.cheer", ["crowd/cheer_0", "crowd/cheer_1"], "subtitles.golftour.cheer")
    event("crowd.groan", ["crowd/groan_0", "crowd/groan_1"], "subtitles.golftour.groan")
    event("ambient.course", ["ambient/course_loop"], None, stream=True, volume=0.4)  # quiet wind bed
    event("ambient.birds", three("ambient/bird"), None, volume=0.55)  # occasional and soft; see biome tick_chance
    event("ui.meter_tick", ["ui/meter_tick"])
    event("ui.meter_lock", ["ui/meter_lock"])
    event("ui.hole_start", ["ui/hole_start"])
    with open(os.path.join(ROOT, "sounds.json"), "w", encoding="utf-8") as f:
        json.dump(events, f, indent=2)
        f.write("\n")
    print(len(events), "sound events written")


if __name__ == "__main__":
    main()
