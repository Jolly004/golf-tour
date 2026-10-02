# MODLOG: Golf Tour (Minecraft 26.3, Fabric)

Working journal (universal-modder `mod-any-game` loop). Newest notes at the bottom of each section.

## Target
- Minecraft Java **26.3** (Ben's latest launcher profile, and his server tool runs Fabric 26.3).
- Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18.2 (`net.fabricmc.fabric-loom`, no mappings: 26.x ships unobfuscated), Gradle 9.7.1, JDK 25 (Temurin 25.0.1 is JAVA_HOME).
- Idea: PGA 2K25-style golf. An 18-hole course, 14 clubs on the hotbar, shot preview (arc, landing ring, carry), a 3-click swing meter, wind, lies, green slopes, putting preview, scorecard.

## Route
Fabric loader API + a few client mixins. Reasons:
- Gameplay needs custom items, blocks, an entity, networking and a custom dimension. Fabric API covers all of them.
- Mixins are needed only for exact mouse-click timing (swing meter) and the ball camera.

## Source of truth
- `./gradlew genSources` decompiles 26.3. Sources were unzipped to the session scratchpad (not the repo).
- 26.3 API facts learned (2026-10-01):
  - `Identifier` (not ResourceLocation). Items and blocks need `properties.setId(ResourceKey)` before construction.
  - Entity types live in `EntityTypes`. Use `EntityType.Builder.of(...).build(ResourceKey)`.
  - HUD: `HudElementRegistry.addLast(id, (GuiGraphicsExtractor g, DeltaTracker dt) -> ...)`. GUI drawing is "extract" based (`fill`, `text`, `centeredText`).
  - World drawing: `net.minecraft.gizmos.Gizmos.line/circle/point/billboardText`. Gizmos emitted during a client tick persist until the next tick (`Minecraft.collectPerTickGizmos`). They are not debug-gated.
  - Networking: `PayloadTypeRegistry.clientboundPlay()/serverboundPlay()`. Handlers run on the main thread.
  - Mouse: `MouseHandler.onButton(long handle, MouseButtonInfo info, int action)`. The screen is `minecraft.gui.screen()`.
  - Camera: private `alignWithEntity(float)`, protected `setPosition/setRotation`, private `detached`.
  - Chunk generators: abstract `buildTerrain(...)`, `getBaseHeight`, `getBaseColumn`, `getMinY/GenDepth/SeaLevel`, `spawnOriginalMobs`, `addDebugScreenInfo`. Copy `FlatLevelSource` as the template.
  - Time is per-dimension "world clocks" plus "timelines" (`data/<ns>/world_clock`, `data/<ns>/timeline`). `server.clockManager().setTotalTicks/setPaused`.

## Lab
- Dev client (`./gradlew runClient`) uses `run/` inside the project. Ben's real `.minecraft` is never touched.

## Log
- 2026-10-02: Published: https://github.com/Jolly004/golf-tour (release v0.4.0 has the CurseForge zip, the .mrpack and the jars). The NoCubes port is at https://github.com/Jolly004/nocubes-port. A public CurseForge listing is blocked: the two port jars aren't on CurseForge's third-party allow-list. Publishing a release: bump `mod_version`, `./gradlew build`, `python tools/make_modpack.py`, then `gh release create vX dist/... libs/...`.
- 2026-10-02 (0.4.0): Multiplayer matches for 2-4 players (`/golf match create|join|start|leave`): real golf turn order, shared wind, leaderboard, turn banner, friends' ball beacons, tracers and watch-cam, group scorecard, final standings, rejoin on reconnect. e4mc added to the pack for internet play via Open to LAN. Putting fixed (see 22-25) and a caddie picks the club when you step up.
- 2026-10-02 (0.3.0): Rubber-banding fixed. Free roam (walk or drive to your ball and the next tee; R goes there, J calls the buggy). Golf buggy via the Automobility 26.3 port (Quartz Rickshaw frame). PGA 2K-style swing stick replaces the 3-click meter.
- 2026-10-02: Real NoCubes (26.3 port) integrated, with Sodium/Iris support. The swing camera now stays behind the golfer through the finish (Ben's request), and there are fewer, softer birds and a quieter wind.
- 2026-10-01: Project scaffolded and `genSources` OK (2m28s).
- 2026-10-01: Full game playable. Unit tests: 18 (physics, smooth surfaces, course routing). The client game test (`runClientGameTest`, add `-Pshaders` for Iris + Complementary) plays hole 1 to the cup, a water hole, the flyover and a real 3-click swing, and saves screenshots.
- 2026-10-01: CurseForge pack: `python tools/make_modpack.py` writes `dist/GolfTour-Modpack-<v>.zip`. CF ids: Fabric API 306612/8913512, Sodium 394468/8888037, Iris 455508/9015108, Complementary 627557/8884654.

## Gotchas (symptom → cause → fix)
1. Keybinds bound to odd keys → 26.3 uses its own key codes → use `InputConstants.KEY_*`, never GLFW constants.
2. `options.hideGui` missing → moved → `minecraft.gui.hud.isHidden()`.
3. `mulPose(Axis.XP.rotationDegrees(..))` doesn't compile → API changed → `poseStack.rotateDegrees(Axis.XP, deg)`.
4. Python crashes with exit 127, a Windows stack overflow inside soundfile → libsndfile's Vorbis encoder on big writes → stream OGG in 1024-frame blocks.
5. Shot preview "CARRY 0 / TOTAL 370" → the client sim fell through chunks it hadn't loaded yet → for unloaded chunks, `LevelBallWorld` uses the course layout instead of blocks.
6. Preview arc nearly invisible under shaders → shader fog washes out depth-tested gizmos → `.setAlwaysOnTop()` on arc, tracer and green grid.
7. Test player ended up in the overworld → `execute as @a run tp @s ~ ~ ~` uses the console's position and dimension → `execute as @a at @s run tp ...`.
8. Penalty drop on top of a stake ("Cart Path" lie) → MOTION_BLOCKING heightmap stops on thin no-collision posts → scan down with the ball's own cell rules (`groundTop`).
9. Flyover showed sky and missing terrain → chunks stream around the player, not the camera → park the player high over the hole midpoint with a 1.5 s lead-in.
10. Third-person club hung behind the legs → in the held-item frame the arm runs along local -Z → display rotation [90,0,0] plus a grip translation of -12 at scale 0.6.
11. (Superseded 2026-10-02, see 12.) NoCubes had no 26.x build → own system: `TurfBlock` with 16 heights for visuals and walking, plus a continuous `CourseLayout.heightAt` the ball rolls on (bilinear over cached corners). Green slopes are now real geometry, so the virtual `slopeAt` is unused on the course.
12. Ben wanted the real NoCubes → ported it from Cadiboo's LGPL source (same code as the 1.21 release) to 26.3 in `../nocubes-port`, and added `NoCubes.addLayeredSmoothable`. Turf layers become fractional SurfaceNets densities, so the mesh sits at the turf height. Measured against the mesh in the gametest (`smooth-surface.txt`): mean error 0.01–0.03 blocks, worst about 0.2 on steep bunker lips. Golf compiles against `libs/nocubes-port-*.jar` (`-PnoNoCubes` runs without it).
13. NoCubes crash "PositionCollisionContext cannot be cast to EntityCollisionContext" (ticking particle) → 26.3 particles pass a position context → read the entity only from `EntityCollisionContext`.
14. Bird chirps too frequent → biome `ambient_sounds.additions.tick_chance` 0.006 (one every ~8 s) → 0.0015 at volume 0.55.
15. Wind loop too loud → `ambient.course` played at full volume → `volume: 0.4` in sounds.json (and in gen_sounds.py).
16. Rubber-banding on short shots (Ben, live) → the client `setPos`'d the golfer into the stance at the course's ideal height; on NoCubes slopes that's inside the smooth ground, and the 26.3 server rejects moves it can't reproduce ("moved wrongly") → step with `player.move` (real collision, gravity sets height), place players on `getBlockCollisions` tops, and wait 10 ticks after a long teleport before stepping. Gametest sweeps the aim before every shot and walks, drives and teleports: 0 rejections.
17. Gizmo markers vanish beyond the render distance (far plane clips them; test runs at 5 chunks) → `beacon()` pulls far markers in to 56 blocks along the view line, scaled to look the same.
18. Swing stick: `MouseHandler.turnPlayer` is the only place the camera turns; while swinging, its accumulated deltas go to `SwingMeter.onMouse` instead. Full swing = 30% of window height. Path = through-angle + half the back-angle, 0.0055 meter accuracy per degree (±0.22 max, never a duff). Gametest: straight = PERFECT, 25° right = SLICE.
19. Matches: each member keeps their own `GolfRound`; `MatchManager` decides whose turn it is (`TurnOrder`, unit-tested) after every round state change (hooked into `RoundManager.sendState`). Holed-out players wait (no auto next hole); the group moves 80 ticks after the last one finishes. Tee balls sit side by side (1.6 blocks apart), and buggies park in a row.
20. Testing multiplayer with one client: Fabric's `FakePlayer` stands in for friends. They aren't in the player list, so `RoundManager.player()` also checks a FAKES map. All golf sends go through `GolfNetworking.send`, which skips fakes. Fakes get `snapTo` instead of teleports, and no game-mode change (it would broadcast player info for a player that doesn't exist). The match gametest plays 2 holes with "Sam" and "Alex": tee order, out-of-turn refusal, honours and the final result are all asserted.
21. Friends' golfers are animated client-side by `OtherGolfers`: the address pose when it's their turn and they're within 2.4 blocks of their ball, then the follow-through when their shot payload arrives. Nobody else sees the real-time backswing (it isn't sent), and fake players aren't rendered, so this part is untested in the gametest.
22. Putting spun the golfer near the hole → the aim direction was ball→crosshair-hit, the stance followed the aim, and the camera followed the stance: a feedback loop that flipped when the hit point landed beside or behind the ball → the aim is now the camera heading (mouse X). The crosshair only sets the length along that line, and the putting camera sits on the line behind the ball. A gametest measures drift with no input (0.00°) and a nudge (1.8°, then still).
23. Putts all came up short, more so the harder they were hit → `surfaceBelow` looked at the cell just under the ball, and where the smooth green sits a hair above a full turf block that cell is air, which read as ROUGH friction. Now both the simulator and `LevelBallWorld.surfaceUnder` look one block lower before giving up. This affected every roll and some lies on the course.
24. Auto-aim pointed the crosshair at `cup().y` (the top of the green's block), and the ray then ran on past the hole to the real, lower surface → aim at `course.heightAt` instead.
25. "100%" on a putt was a flat-green length, so uphill putts died short → `ShotPlanner.solvePuttLength` finds the flat length that makes a pure putt stop at the crosshair on this green (a putt that drops counts as reaching it), so pulling to the marker gets there. The HUD shows "PLAYS N FT UPHILL/DOWNHILL". Test results: holed from 2.4 and 7.2 ft, and 1.1 ft left from 18 and 36 ft after reading the break, matching the preview each time.
26. NoCubes gives smoothed blocks rough collision boxes, which would read as walls to the ball's obstacle check → for smoothed blocks `LevelBallWorld` uses `state.getShape` (the real turf layer), not `getCollisionShape`.
27. A one-off server crash (fastutil iterator NPE in `ChunkMap.tick`, i.e. the tracked-entity map changed mid-loop) right after a shot with the Automobility buggy nearby. It didn't recur in later full runs. Buggies are now removed with Automobility's `destroyAutomobile` (which takes their hitbox entities too) instead of a bare discard. Watch for it.
