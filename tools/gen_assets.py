"""Generates Golf Tour's textures, models, blockstates and item definitions.

All art is drawn procedurally here (no third-party or game assets), so it can be regenerated at any time:
    python tools/gen_assets.py
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "golftour")


def path(*parts):
    p = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    return p


def write_json(rel, obj):
    with open(path(*rel.split("/")), "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def hexrgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255)


def clamp(v):
    return max(0, min(255, int(v)))


def shade(c, f):
    return tuple(clamp(x * f) for x in c[:3])


# ------------------------------------------------------------------ block textures

RES = 32


def soft_noise(rnd, size, cells=4):
    """Low-frequency value noise in 0..1, tileable."""
    grid = [[rnd.random() for _ in range(cells)] for _ in range(cells)]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            fx, fy = x / size * cells, y / size * cells
            x0, y0 = int(fx) % cells, int(fy) % cells
            x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
            tx, ty = fx - int(fx), fy - int(fy)
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            top = grid[y0][x0] * (1 - tx) + grid[y0][x1] * tx
            bot = grid[y1][x0] * (1 - tx) + grid[y1][x1] * tx
            out[y][x] = top * (1 - ty) + bot * ty
    return out


def turf(name, base, grain, blade=None, blade_density=0.0, seed=0, stripe=None, blade_len=(1, 2), blade_contrast=0.18):
    rnd = random.Random(seed)
    size = RES
    soft = soft_noise(rnd, size)
    img = Image.new("RGBA", (size, size))
    for y in range(size):
        for x in range(size):
            f = 1 + (rnd.random() - 0.5) * grain + (soft[y][x] - 0.5) * 0.12
            c = shade(hexrgb(base), f)
            if stripe is not None:
                c = shade(c, stripe)
            img.putpixel((x, y), c + (255,))
    blades = int(size * size * max(blade_density, 0.12))
    bcol = hexrgb(blade if blade else base)
    for _ in range(blades):
        x, y = rnd.randrange(size), rnd.randrange(size)
        length = rnd.randint(*blade_len)
        light = rnd.random() < 0.45
        f = (1 + blade_contrast * rnd.uniform(0.4, 1.0)) if light else (1 - blade_contrast * rnd.uniform(0.4, 1.0))
        lean = rnd.choice([-1, 0, 0, 1])
        for i in range(length):
            px, py = (x + (lean if i == length - 1 else 0)) % size, (y - i) % size
            img.putpixel((px, py), shade(bcol, f * (1 - 0.04 * i)) + (255,))
    img.save(path("textures", "block", name + ".png"))
    return img


def bunker_sand(seed=21):
    rnd = random.Random(seed)
    size = RES
    img = Image.new("RGBA", (size, size))
    for y in range(size):
        for x in range(size):
            rake = 0.94 if (y + int(1.5 * math.sin(x / 5.0))) % 5 == 0 else 1.0
            c = shade((238, 226, 190), (1 + (rnd.random() - 0.5) * 0.10) * rake)
            img.putpixel((x, y), c + (255,))
    img.save(path("textures", "block", "bunker_sand.png"))
    return img


def dirt(seed=7):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (RES, RES))
    for y in range(RES):
        for x in range(RES):
            c = shade((134, 96, 67), 1 + (rnd.random() - 0.5) * 0.35)
            img.putpixel((x, y), c + (255,))
    return img


def side(name, top_img, depth=4, seed=3):
    rnd = random.Random(seed)
    img = dirt(seed)
    for x in range(RES):
        d = depth * 2 + rnd.choice([-2, -1, 0, 0, 1, 2])
        for y in range(d):
            img.putpixel((x, y), top_img.getpixel((x, (y * 3) % RES)))
    img.save(path("textures", "block", name + "_side.png"))


def cup_texture(green_img):
    img = green_img.copy()
    cx = cy = RES / 2 - 0.5
    for y in range(RES):
        for x in range(RES):
            d = math.hypot(x - cx, y - cy)
            if d < 5.0:
                depth = 0.35 + 0.25 * max(0, (x - cx + y - cy)) / 5
                img.putpixel((x, y), shade((40, 44, 40), depth + 0.4) + (255,))
            elif d < 6.4:
                img.putpixel((x, y), shade((240, 240, 240), 1 - 0.06 * (d - 5)) + (255,))
    img.save(path("textures", "block", "cup.png"))


def solid(name, color, noise=0.08, seed=11):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), shade(hexrgb(color), 1 + (rnd.random() - 0.5) * noise) + (255,))
    img.save(path("textures", "block", name + ".png"))


def flag_cloth(frames=8):
    """Waving flag: a vertical strip of frames animated by flag_cloth.png.mcmeta."""
    size = 16
    sheet = Image.new("RGBA", (size, size * frames), (0, 0, 0, 0))
    for f in range(frames):
        phase = f / frames * math.pi * 2
        for y in range(size):
            for x in range(size):
                amp = x / size  # free end flaps more than the pole side
                dy = int(round(1.4 * amp * math.sin(x / 2.6 - phase)))
                sy = y - dy
                if not 1 <= sy < size - 1:
                    continue
                light = 1 + 0.22 * amp * math.cos(x / 2.6 - phase)
                c = (214, 38, 38)
                if math.hypot(x - 8, sy - 8) < 3.2:
                    c = (250, 250, 250)
                sheet.putpixel((x, f * size + y), shade(c, light) + (255,))
    sheet.save(path("textures", "block", "flag_cloth.png"))
    with open(path("textures", "block", "flag_cloth.png.mcmeta"), "w") as fh:
        json.dump({"animation": {"frametime": 2, "interpolate": True}}, fh)


def stake():
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = (245, 245, 245) if y > 2 else (60, 60, 60)
            img.putpixel((x, y), shade(c, 0.92 + 0.08 * ((x % 3) == 0)) + (255,))
    img.save(path("textures", "block", "ob_stake.png"))


def post(name, cap, body=(245, 245, 245), cap_rows=4):
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = cap if y < cap_rows else body
            img.putpixel((x, y), shade(c, 0.9 + 0.1 * ((x % 4) == 0)) + (255,))
    img.save(path("textures", "block", name + ".png"))


def tee_marker():
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = (40, 90, 210) if (y // 3) % 2 == 0 else (245, 245, 245)
            img.putpixel((x, y), c + (255,))
    img.save(path("textures", "block", "tee_marker.png"))


# ------------------------------------------------------------------ item textures

GRIP = (30, 30, 34)
SHAFT = (175, 180, 190)
SHAFT_DARK = (120, 125, 135)


def club_icon(name, head, band=None):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    # Shaft from bottom-left (grip) to top-right (head), like vanilla tools.
    for i in range(12):
        x, y = 1 + i, 14 - i
        col = GRIP if i < 4 else (SHAFT if i % 2 == 0 else SHAFT_DARK)
        img.putpixel((x, y), col + (255,))
        if i < 4:
            img.putpixel((x + 1, y), shade(GRIP, 1.4) + (255,))
    if band:
        img.putpixel((6, 9), band + (255,))
        img.putpixel((7, 8), band + (255,))
    for (x, y), col in head.items():
        img.putpixel((x, y), col + (255,))
    img.save(path("textures", "item", name + ".png"))


def wood_head(size, body, accent):
    pts = {}
    cx, cy = 13 - size / 2 + 0.5, 2 + size / 2 - 0.5
    for y in range(16):
        for x in range(16):
            if math.hypot((x - cx) / (size / 2), (y - cy) / (size / 2 * 0.85)) <= 1.0:
                f = 1.25 if (x + y) < cx + cy - 1 else 0.9
                pts[(x, y)] = shade(body, f)
    for x in range(16):
        for y in range(16):
            if (x, y) in pts and y == int(cy + size / 2 * 0.85) - 1:
                pts[(x, y)] = accent
    return pts


def iron_head(loft, color):
    pts = {}
    # A blade below/left of the shaft tip, longer with more loft
    for i in range(4):
        for j in range(2 + loft):
            x, y = 12 + i - j // 2, 2 + j
            if 0 <= x < 16 and 0 <= y < 16:
                pts[(x, y)] = shade(color, 1.2 if i == 0 else 1.0 - 0.06 * j)
    pts[(15, 2)] = shade(color, 0.8)
    return pts


def putter_head():
    pts = {}
    for x in range(9, 16):
        for y in range(1, 4):
            pts[(x, y)] = shade((60, 62, 70), 1.3 if y == 1 else 1.0)
    pts[(12, 2)] = (230, 230, 230)
    return pts


def golf_ball():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d <= 5.2:
                light = 1.0 - max(0, (x - 7.5 + y - 7.5)) * 0.03
                dimple = 0.93 if (x + 2 * y) % 5 == 0 and d < 4.6 else 1.0
                img.putpixel((x, y), shade((250, 250, 250), light * dimple) + (255,))
            elif d <= 5.8:
                img.putpixel((x, y), (190, 190, 190, 255))
    img.save(path("textures", "item", "golf_ball.png"))


def mod_icon():
    """128x128 mod icon: a green on a fairway with a red flag."""
    size = 128
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - 63.5, y - 63.5)
            if d <= 62:
                stripe = 1.0 if (x // 16) % 2 == 0 else 0.92
                sky = y < 52
                if sky:
                    c = shade((120, 180, 255), 1.05 - y / 300)
                else:
                    c = shade((108, 192, 74), stripe)
                    if math.hypot((x - 64) / 44, (y - 84) / 16) <= 1:
                        c = (155, 224, 122)
                img.putpixel((x, y), c + (255,))
            elif d <= 64:
                img.putpixel((x, y), (20, 60, 28, 255))
    for y in range(28, 86):
        for x in range(63, 66):
            img.putpixel((x, y), (245, 245, 245, 255))
    for y in range(28, 46):
        for x in range(66, 66 + int((46 - y) * 1.6) + 4):
            if x < 100:
                img.putpixel((x, y), (214, 38, 38, 255))
    for y in range(83, 89):
        for x in range(60, 69):
            img.putpixel((x, y), (20, 24, 20, 255))
    for y in range(90, 100):
        for x in range(40, 50):
            if math.hypot(x - 44.5, y - 94.5) <= 4.6:
                img.putpixel((x, y), (250, 250, 250, 255))
    img.save(path("icon.png"))


# ------------------------------------------------------------------ models

def cube_turf(name, side_name=None):
    if side_name:
        write_json(f"models/block/{name}.json", {
            "parent": "minecraft:block/cube_bottom_top",
            "textures": {"top": f"golftour:block/{name}", "side": f"golftour:block/{side_name}", "bottom": "minecraft:block/dirt"}})
    else:
        write_json(f"models/block/{name}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": f"golftour:block/{name}"}})
    simple_block(name)


def layered(name, top, side, bottom):
    """Turf with 16 heights (blockstate property layers=1..16); the side texture keeps its grass edge on top."""
    variants = {}
    for k in range(1, 17):
        faces_k = {
            "down": {"uv": [0, 0, 16, 16], "texture": "#bottom", "cullface": "down"},
            "up": {"uv": [0, 0, 16, 16], "texture": "#top"},
        }
        if k == 16:
            faces_k["up"]["cullface"] = "up"
        for d in ["north", "south", "east", "west"]:
            faces_k[d] = {"uv": [0, 0, 16, k], "texture": "#side", "cullface": d}
        model = f"{name}_h{k}" if k < 16 else name
        write_json(f"models/block/{model}.json", {
            "parent": "minecraft:block/block",
            "textures": {"top": top, "side": side, "bottom": bottom, "particle": top},
            "elements": [{"from": [0, 0, 0], "to": [16, k, 16], "faces": faces_k}]})
        variants[f"layers={k}"] = {"model": f"golftour:block/{model}"}
    write_json(f"blockstates/{name}.json", {"variants": variants})
    write_json(f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"golftour:block/{name}"}})


def simple_block(name):
    write_json(f"blockstates/{name}.json", {"variants": {"": {"model": f"golftour:block/{name}"}}})
    write_json(f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"golftour:block/{name}"}})


def faces(tex, uv=None, sides="all"):
    out = {}
    for f in ["north", "south", "east", "west", "up", "down"]:
        face = {"texture": tex}
        if uv:
            face["uv"] = uv
        out[f] = face
    return out


def main():
    rnd_seed = 1
    tee = turf("tee_box", 0x7FCB5E, 0.10, seed=rnd_seed)
    fw = turf("fairway", 0x6CC04A, 0.08, seed=2)
    fwd = turf("fairway_dark", 0x5BAA3D, 0.08, seed=3)
    fr = turf("fringe", 0x86D163, 0.08, seed=4)
    gr = turf("green", 0x8FDB6B, 0.05, seed=5)
    grd = turf("green_dark", 0x7FCB5D, 0.05, seed=6)
    ro = turf("rough", 0x3F8F2F, 0.20, blade=0x3A8A2B, blade_density=0.45, seed=7, blade_len=(2, 4), blade_contrast=0.28)
    dr = turf("deep_rough", 0x2E6E24, 0.24, blade=0x2D6A22, blade_density=0.7, seed=8, blade_len=(3, 6), blade_contrast=0.34)
    for name, img in [("tee_box", tee), ("fairway", fw), ("fairway_dark", fwd), ("fringe", fr), ("green", gr), ("green_dark", grd),
                      ("rough", ro), ("deep_rough", dr)]:
        side(name, img, seed=hash(name) & 0xFFFF)
        layered(name, f"golftour:block/{name}", f"golftour:block/{name}_side", "minecraft:block/dirt")
    bunker_sand()
    layered("bunker_sand", "golftour:block/bunker_sand", "golftour:block/bunker_sand", "golftour:block/bunker_sand")
    cup_texture(gr)
    layered("cup", "golftour:block/cup", "golftour:block/green_side", "minecraft:block/dirt")

    solid("flagstick", 0xF2F2F2)
    flag_cloth()
    stake()
    tee_marker()

    pole = {"from": [7.5, 0, 7.5], "to": [8.5, 16, 8.5], "faces": faces("#pole", [7, 0, 8, 16])}
    write_json("models/block/flagstick.json", {
        "parent": "minecraft:block/block", "ambientocclusion": False,
        "textures": {"pole": "golftour:block/flagstick", "particle": "golftour:block/flagstick"}, "elements": [pole]})
    simple_block("flagstick")
    write_json("models/block/flag.json", {
        "parent": "minecraft:block/block", "ambientocclusion": False,
        "textures": {"pole": "golftour:block/flagstick", "cloth": "golftour:block/flag_cloth", "particle": "golftour:block/flag_cloth"},
        "elements": [
            pole,
            {"from": [8.5, 7, 8], "to": [16, 15, 8.01], "shade": False,
             "faces": {"north": {"texture": "#cloth", "uv": [0, 0, 16, 16]}, "south": {"texture": "#cloth", "uv": [16, 0, 0, 16]}}},
        ]})
    simple_block("flag")
    write_json("models/block/tee_marker.json", {
        "parent": "minecraft:block/block", "textures": {"t": "golftour:block/tee_marker", "particle": "golftour:block/tee_marker"},
        "elements": [{"from": [5, 0, 5], "to": [11, 6, 11], "faces": faces("#t", [5, 10, 11, 16])}]})
    simple_block("tee_marker")
    write_json("models/block/ob_stake.json", {
        "parent": "minecraft:block/block", "textures": {"t": "golftour:block/ob_stake", "particle": "golftour:block/ob_stake"},
        "elements": [{"from": [6.5, 0, 6.5], "to": [9.5, 14, 9.5], "faces": faces("#t", [6, 2, 9, 16])}]})
    simple_block("ob_stake")
    post("hazard_stake", (214, 40, 40), body=(214, 40, 40), cap_rows=0)
    write_json("models/block/hazard_stake.json", {
        "parent": "minecraft:block/block", "textures": {"t": "golftour:block/hazard_stake", "particle": "golftour:block/hazard_stake"},
        "elements": [{"from": [6.5, 0, 6.5], "to": [9.5, 14, 9.5], "faces": faces("#t", [6, 2, 9, 16])}]})
    simple_block("hazard_stake")
    for yards, cap in [(100, (214, 40, 40)), (150, (250, 250, 250)), (200, (40, 90, 210))]:
        name = f"yardage_{yards}"
        post(name, cap, body=(70, 70, 76) if yards == 150 else (245, 245, 245))
        side_uv = {"texture": "#t", "uv": [5, 0, 10, 9]}
        write_json(f"models/block/{name}.json", {
            "parent": "minecraft:block/block", "textures": {"t": f"golftour:block/{name}", "particle": f"golftour:block/{name}"},
            "elements": [{"from": [5.5, 0, 5.5], "to": [10.5, 9, 10.5], "faces": {
                "north": side_uv, "south": side_uv, "east": side_uv, "west": side_uv,
                "up": {"texture": "#t", "uv": [5, 0, 10, 4]}, "down": {"texture": "#t", "uv": [5, 12, 10, 16]}}}]})
        simple_block(name)

    # Clubs: head shapes and colours per category; irons get a coloured shaft band.
    titanium, steel, bronze = (45, 47, 55), (190, 196, 205), (176, 132, 86)
    clubs = {
        "driver": wood_head(6, titanium, (220, 60, 50)),
        "wood_3": wood_head(5, (35, 70, 50), (230, 230, 230)),
        "wood_5": wood_head(5, (40, 55, 95), (230, 230, 230)),
        "hybrid_4": wood_head(4, (70, 72, 80), (240, 190, 40)),
        "iron_5": iron_head(1, steel),
        "iron_6": iron_head(1, steel),
        "iron_7": iron_head(2, steel),
        "iron_8": iron_head(2, steel),
        "iron_9": iron_head(3, steel),
        "pitching_wedge": iron_head(3, steel),
        "gap_wedge": iron_head(4, bronze),
        "sand_wedge": iron_head(4, bronze),
        "lob_wedge": iron_head(5, bronze),
        "putter": putter_head(),
    }
    bands = {"iron_5": (220, 60, 50), "iron_6": (240, 140, 40), "iron_7": (240, 210, 50), "iron_8": (70, 190, 80), "iron_9": (60, 120, 230),
             "pitching_wedge": (150, 90, 210), "gap_wedge": (240, 240, 240), "sand_wedge": (230, 200, 120), "lob_wedge": (90, 220, 220)}
    for name, head in clubs.items():
        club_icon(name, head, bands.get(name))
        write_json(f"models/item/{name}.json", {"parent": "minecraft:item/handheld", "textures": {"layer0": f"golftour:item/{name}"}})
        write_json(f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"golftour:item/{name}"}})
    golf_ball()
    write_json("models/item/golf_ball.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "golftour:item/golf_ball"}})
    write_json("items/golf_ball.json", {"model": {"type": "minecraft:model", "model": "golftour:item/golf_ball"}})
    mod_icon()
    print("assets written to", os.path.abspath(ROOT))


if __name__ == "__main__":
    main()
