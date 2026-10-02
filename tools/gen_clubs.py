"""Generates the 3D held-club models and their texture atlas.

Each club keeps its flat icon for inventories (gui/ground/frames) and switches to a 3D model in the hand,
which the client animates through address, backswing, impact and follow-through.
    python tools/gen_clubs.py
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "golftour")


def write_json(rel, obj):
    path = os.path.join(ROOT, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


# Atlas regions in model UV units (0-16); the texture is 32x32 so each unit is 2 pixels.
REGIONS = {
    "grip": (0, 0, 2, 16),
    "shaft": (2, 0, 3, 16),
    "titanium": (4, 0, 8, 4),
    "red": (4, 4, 8, 5),
    "chrome": (8, 0, 12, 4),
    "chrome_face": (8, 4, 12, 8),
    "bronze": (12, 0, 16, 4),
    "bronze_face": (12, 4, 16, 8),
    "putter": (4, 8, 8, 12),
    "sight": (4, 12, 8, 13),
    "green": (8, 8, 12, 12),
    "blue": (12, 8, 16, 12),
    "gold": (8, 12, 12, 14),
}


def atlas():
    rnd = random.Random(9)
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))

    def fill(region, fn):
        u0, v0, u1, v1 = REGIONS[region]
        for y in range(v0 * 2, v1 * 2):
            for x in range(u0 * 2, u1 * 2):
                img.putpixel((x, y), fn(x - u0 * 2, y - v0 * 2) + (255,))

    fill("grip", lambda x, y: shade((34, 34, 38), 1.25 if y % 3 == 0 else 0.95 + rnd.random() * 0.1))
    fill("shaft", lambda x, y: shade((200, 205, 215), 1.15 if x == 0 else 0.85))
    fill("titanium", lambda x, y: shade((42, 44, 52), 1.0 + 0.25 * (1 - y / 8) + rnd.random() * 0.05))
    fill("red", lambda x, y: (215, 45, 40))
    fill("chrome", lambda x, y: shade((205, 210, 220), 1.1 - 0.25 * abs(y - 3) / 4 + rnd.random() * 0.04))
    fill("chrome_face", lambda x, y: shade((190, 195, 205), 0.75 if y % 2 == 1 else 1.05))
    fill("bronze", lambda x, y: shade((182, 136, 88), 1.1 - 0.2 * abs(y - 3) / 4 + rnd.random() * 0.05))
    fill("bronze_face", lambda x, y: shade((170, 126, 80), 0.75 if y % 2 == 1 else 1.05))
    fill("putter", lambda x, y: shade((70, 72, 80), 1.0 + 0.15 * (1 - y / 8)))
    fill("sight", lambda x, y: (245, 245, 245))
    fill("green", lambda x, y: shade((30, 78, 52), 1.0 + 0.2 * (1 - y / 8)))
    fill("blue", lambda x, y: shade((36, 60, 120), 1.0 + 0.2 * (1 - y / 8)))
    fill("gold", lambda x, y: shade((200, 160, 50), 1.0 + 0.15 * (1 - y / 4)))
    path = os.path.join(ROOT, "textures", "item", "club_parts.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def box(frm, to, region, top=None, front=None):
    """A cuboid with every face mapped to one atlas region (optionally a different top / front face)."""
    uv = list(REGIONS[region])
    faces = {d: {"uv": uv, "texture": "#parts"} for d in ["north", "south", "east", "west", "up", "down"]}
    if top:
        faces["up"] = {"uv": list(REGIONS[top]), "texture": "#parts"}
    if front:
        faces["north"] = {"uv": list(REGIONS[front]), "texture": "#parts"}
    return {"from": frm, "to": to, "faces": faces}


def club_elements(kind):
    els = [
        box([7.35, 21, 7.35], [8.65, 30, 8.65], "grip"),
        box([7.7, 1, 7.7], [8.3, 21, 8.3], "shaft"),
    ]
    if kind == "driver":
        els += [box([5.2, -2.2, 6.0], [11.8, 1.6, 10.6], "titanium", top="titanium"),
                box([5.6, 1.6, 6.6], [11.4, 1.9, 7.2], "red"),
                box([7.5, 1.0, 7.5], [8.5, 2.6, 8.5], "titanium")]
    elif kind in ("wood_3", "wood_5", "hybrid_4"):
        colour = {"wood_3": "green", "wood_5": "blue", "hybrid_4": "gold"}[kind]
        size = {"wood_3": (5.8, 11.2, 6.6, 10.0, -1.8, 1.2), "wood_5": (6.0, 11.0, 6.8, 9.8, -1.7, 1.1), "hybrid_4": (6.3, 10.8, 7.1, 9.5, -1.6, 0.9)}[kind]
        x0, x1, z0, z1, y0, y1 = size
        els += [box([x0, y0, z0], [x1, y1, z1], colour, top=colour),
                box([7.5, 0.6, 7.5], [8.5, 2.2, 8.5], "titanium")]
    elif kind == "putter":
        els += [box([5.0, -1.6, 7.0], [11.4, -0.2, 9.0], "putter", top="sight"),
                box([7.6, -0.4, 7.8], [8.4, 1.4, 8.2], "putter")]
    else:
        wedge = kind in ("gap_wedge", "sand_wedge", "lob_wedge")
        metal, face = ("bronze", "bronze_face") if wedge else ("chrome", "chrome_face")
        height = {"iron_5": 0.6, "iron_6": 0.75, "iron_7": 0.9, "iron_8": 1.05, "iron_9": 1.2, "pitching_wedge": 1.35,
                  "gap_wedge": 1.45, "sand_wedge": 1.6, "lob_wedge": 1.75}[kind]
        els += [box([7.2, -2.2, 7.45], [12.4, -2.2 + 3.0 * height, 8.55], metal, front=face),
                box([7.35, 0.4, 7.6], [8.65, 2.4, 8.4], metal)]
    return els


CLUBS = ["driver", "wood_3", "wood_5", "hybrid_4", "iron_5", "iron_6", "iron_7", "iron_8", "iron_9",
         "pitching_wedge", "gap_wedge", "sand_wedge", "lob_wedge", "putter"]


def main():
    atlas()
    for name in CLUBS:
        identity = {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}
        write_json(f"models/item/{name}_in_hand.json", {
            "textures": {"parts": "golftour:item/club_parts", "particle": f"golftour:item/{name}"},
            "elements": club_elements(name),
            "display": {
                # First person is fully posed and animated by the client (ClubAnimator).
                "firstperson_righthand": identity,
                "firstperson_lefthand": identity,
                # Held by other players: grip in the hand, head down towards the ground.
                # The shaft continues straight out of the fist (grip on the hand), so the head follows the arms.
                "thirdperson_righthand": {"rotation": [90, 0, 0], "translation": [0, 0, -12], "scale": [0.6, 0.6, 0.6]},
                "thirdperson_lefthand": {"rotation": [90, 0, 0], "translation": [0, 0, -12], "scale": [0.6, 0.6, 0.6]},
                "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            },
        })
        write_json(f"items/{name}.json", {"model": {
            "type": "minecraft:select",
            "property": "minecraft:display_context",
            "cases": [{"when": ["gui", "ground", "fixed", "on_shelf"], "model": {"type": "minecraft:model", "model": f"golftour:item/{name}"}}],
            "fallback": {"type": "minecraft:model", "model": f"golftour:item/{name}_in_hand"},
        }})
    print("3D club models written")


if __name__ == "__main__":
    main()
