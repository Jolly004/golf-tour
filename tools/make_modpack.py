"""Builds dist/GolfTour-Modpack-<version>.zip, a CurseForge modpack you can import into the CurseForge app, and
dist/GolfTour-<version>.mrpack, the same pack for the Modrinth app and Prism Launcher.

Fabric API, Sodium, Iris and Complementary Reimagined are referenced by CurseForge project/file id (the app
downloads them from CurseForge); the Golf Tour jar, the NoCubes 26.3 port (with its LGPL source and licence)
and the shader settings ship in overrides/.
Run after ./gradlew build:
    python tools/make_modpack.py
"""
import hashlib
import json
import os
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def prop(name):
    with open(os.path.join(ROOT, "gradle.properties"), encoding="utf-8") as f:
        for line in f:
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    raise KeyError(name)


VERSION = prop("mod_version")
MC = prop("minecraft_version")
LOADER = prop("loader_version")
SHADER_PACK = "ComplementaryReimagined_r5.9.3.zip"
NOCUBES = "nocubes-port-0.5.2-port.1+26.3"
NOCUBES_DIR = os.path.join(ROOT, "..", "nocubes-port")
# Golf buggy: Automobility, unofficial 26.3 port (MIT, not on CurseForge): ships in overrides with its licence.
AUTOMOBILITY = "automobility-0.5.0-unofficial.36+26.3-fabric"

# (name, CurseForge project id, file id, page)
FILES = [
    ("Fabric API 0.161.0+26.3", 306612, 8913512, "https://www.curseforge.com/minecraft/mc-mods/fabric-api"),
    ("Sodium 0.9.2 (Fabric)", 394468, 8888037, "https://www.curseforge.com/minecraft/mc-mods/sodium"),
    ("Iris Shaders 1.11.7 (Fabric)", 455508, 9015108, "https://www.curseforge.com/minecraft/mc-mods/irisshaders"),
    ("Complementary Shaders - Reimagined r5.9.3", 627557, 8884654, "https://www.curseforge.com/minecraft/shaders/complementary-reimagined"),
    # Lets friends join an "Open to LAN" world from anywhere, for matches.
    ("e4mc 6.2.2 (Fabric)", 849519, 8990751, "https://www.curseforge.com/minecraft/mc-mods/e4mc"),
]


NOCUBES_README = """NoCubes by Cadiboo (https://github.com/Cadiboo/NoCubes), LGPL-3.0.
This is a port to Minecraft 26.3 Fabric. The complete source of the included jar is in the sources jar here.
Changes from upstream: 26.3 API and rendering updates, Sodium 0.9 support, a fix for non-entity collision
contexts, and NoCubes.addLayeredSmoothable (fractional densities for partial-height blocks).
"""


# The .mrpack downloads these from Modrinth: (project slug, exact version, client, server).
MODRINTH = [
    ("fabric-api", "0.161.0+26.3", "required", "required"),
    ("sodium", "mc26.3-0.9.2-fabric", "required", "unsupported"),
    ("iris", "1.11.7+26.3-fabric", "required", "unsupported"),
    ("e4mc", "6.2.2-fabric-modern", "required", "unsupported"),
    ("automobility-unofficial-port", "0.5.0-unofficial.36+26.3", "required", "required"),
    ("complementary-reimagined", "r5.9.3", "required", "unsupported"),
]


def modrinth_file(slug, version):
    url = f"https://api.modrinth.com/v2/project/{slug}/version"
    req = urllib.request.Request(url, headers={"User-Agent": "golftour-modpack-builder (github.com/Jolly004)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        versions = json.load(r)
    for v in versions:
        if v["version_number"] == version:
            files = [f for f in v["files"] if f["primary"]] or v["files"]
            return v, files[0]
    raise SystemExit(f"{slug} {version} not found on Modrinth")


def make_mrpack(jar, nocubes_jar, nocubes_src, nocubes_licence, iris, options):
    files = []
    for slug, version, client, server in MODRINTH:
        v, f = modrinth_file(slug, version)
        folder = "shaderpacks" if slug == "complementary-reimagined" else "mods"
        files.append({
            "path": f"{folder}/{f['filename']}",
            "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
            "env": {"client": client, "server": server},
            "downloads": [f["url"]],
            "fileSize": f["size"],
        })
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": VERSION,
        "name": "Golf Tour",
        "summary": "PGA-style golf on an 18-hole course with smooth terrain, a mouse swing, buggies and multiplayer matches.",
        "files": files,
        "dependencies": {"minecraft": MC, "fabric-loader": LOADER},
    }
    out = os.path.join(ROOT, "dist", f"GolfTour-{VERSION}.mrpack")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
        z.write(jar, f"overrides/mods/golftour-{VERSION}.jar")
        z.write(nocubes_jar, f"overrides/mods/{NOCUBES}.jar")
        z.write(nocubes_src, f"overrides/nocubes-source/{NOCUBES}-sources.jar")
        z.write(nocubes_licence, "overrides/nocubes-source/LICENSE")
        z.writestr("overrides/nocubes-source/README.txt", NOCUBES_README)
        z.writestr("client-overrides/config/iris.properties", iris)
        z.writestr("client-overrides/options.txt", options)
    print("wrote", out, os.path.getsize(out) // 1024, "KB")


def main():
    jar = os.path.join(ROOT, "build", "libs", f"golftour-{VERSION}.jar")
    if not os.path.exists(jar):
        raise SystemExit(f"Build the mod first: {jar} not found")
    nocubes_jar = os.path.join(ROOT, "libs", f"{NOCUBES}.jar")
    nocubes_src = os.path.join(NOCUBES_DIR, "build", "libs", f"{NOCUBES}-sources.jar")
    nocubes_licence = os.path.join(NOCUBES_DIR, "LICENSE")
    automobility_jar = os.path.join(ROOT, "libs", f"{AUTOMOBILITY}.jar")
    for f in (nocubes_jar, nocubes_src, nocubes_licence, automobility_jar):
        if not os.path.exists(f):
            raise SystemExit(f"Build ../nocubes-port first: {f} not found")
    manifest = {
        "minecraft": {"version": MC, "modLoaders": [{"id": f"fabric-{LOADER}", "primary": True}]},
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": "Golf Tour",
        "version": VERSION,
        "author": "Ben Walker",
        "files": [{"projectID": pid, "fileID": fid, "required": True} for _, pid, fid, _ in FILES],
        "overrides": "overrides",
    }
    rows = "\n".join(f'<li><a href="{url}">{name}</a></li>' for name, _, _, url in FILES)
    modlist = (f"<ul>\n<li>Golf Tour {VERSION} (included)</li>\n"
               '<li>NoCubes 26.3 port (included; <a href="https://github.com/Cadiboo/NoCubes">NoCubes</a> by Cadiboo, '
               "LGPL-3.0, source in nocubes-source/)</li>\n"
               '<li>Automobility unofficial 26.3 port (included; <a href="https://modrinth.com/mod/automobility-unofficial-port">Modrinth</a>, '
               "MIT, by FoundationGames and port contributors): the golf buggy</li>\n"
               f"{rows}\n</ul>\n")
    iris = f"enableShaders=true\nshaderPack={SHADER_PACK}\n"
    # A few defaults that suit the course: long view distance for flyovers and approach shots.
    options = "renderDistance:16\nsimulationDistance:12\nentityShadows:true\n"

    os.makedirs(os.path.join(ROOT, "dist"), exist_ok=True)
    out = os.path.join(ROOT, "dist", f"GolfTour-Modpack-{VERSION}.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("modlist.html", modlist)
        z.write(jar, f"overrides/mods/golftour-{VERSION}.jar")
        z.write(nocubes_jar, f"overrides/mods/{NOCUBES}.jar")
        z.write(automobility_jar, f"overrides/mods/{AUTOMOBILITY}.jar")
        z.write(nocubes_src, f"overrides/nocubes-source/{NOCUBES}-sources.jar")
        z.write(nocubes_licence, "overrides/nocubes-source/LICENSE")
        z.writestr("overrides/nocubes-source/README.txt", NOCUBES_README)
        z.writestr("overrides/config/iris.properties", iris)
        z.writestr("overrides/options.txt", options)
    print("wrote", out, os.path.getsize(out) // 1024, "KB")
    make_mrpack(jar, nocubes_jar, nocubes_src, nocubes_licence, iris, options)


if __name__ == "__main__":
    main()
