# MC:Source (Fabric)

A Fabric mod for Minecraft Java 26.3 that aims to make Minecraft look like a
Source engine game (Half-Life 2 era) while playing exactly like Minecraft.

This first step adds **smooth terrain with matching collision** and a test
world to try it in.

## What it does so far

- **Smooth terrain.** Natural blocks (grass, dirt, stone, sand and the others
  in `data/mcsource/tags/block/smooth_terrain.json`) form one continuous
  surface: every grid corner where blocks meet moves up or down by up to half a
  block depending on how much terrain surrounds it. Every one-block step,
  straight or diagonal, on the ground or on a cave ceiling, becomes a ramp,
  flat ground stays flat, and textures, grass tint and
  side overlays bend with the surface. Each corner also gets a smooth normal
  and light from the open air around it, so slopes shade evenly across block
  borders.
- **Sharp built blocks.** Anything not in that tag (planks, bricks, cobblestone,
  glass and so on) stays a sharp cube. Corners touching a built block or a
  liquid don't move, so the two meet cleanly.
- **Matching collision.** The same corner heights drive collision, the block
  outline and ray picking on both the client and the server, so you walk up the
  slope you see. Collision is built from 8×8 columns that follow the drawn
  surface to within an eighth of a block; the steps between columns are small
  enough that you walk up slopes without jumping.
- **Plants and snow follow the ground.** Grass, flowers, saplings, snow layers
  and similar blocks bend to the surface of the ground under them. A single
  snow layer is drawn as a thin sheet lying on the ground without the little
  walls round its edges.
- **No stairs on slopes.** Movement is applied in pieces shorter than a
  collision column, so you walk up slopes at full speed instead of stalling on
  every little ledge, and walking down keeps you on the ground, as in Source.
  Smoothed ground has no block outline.
- **Source-style lighting.** Torches and other lamps light a warm pool that
  falls off with the square of distance, whiter near the flame and orange at
  the edge. Daylight is a warm sun from a fixed direction, like a Source map's
  light_environment, plus a cooler blue sky fill. Surfaces facing away from the
  sun or in the shadow of trees, hills and buildings keep only the blue fill;
  shadows have soft edges and leaves let some light through. Bright light rolls off softly instead of clipping. Mobs,
  players and items use half-Lambert shading, so light wraps around them and
  their dark side keeps its shape.
- **Source movement.** On land and in the air the player moves like in a
  Source game: you speed up to 190 units/s running or 320 sprinting (one block
  counts as 40 units), ground friction brings you to a quick stop, and in the
  air you keep your momentum and can only steer by strafing. Air strafing and
  bunny hopping (hold jump to hop on landing with no friction) both work.
  Movement is worked out at 60 steps a second, close to Source's 66, and uses
  Half-Life 2's gravity; the jump is tuned to clear one block. Sprint-jumping
  no longer gives a free burst of speed. Swimming, flying, ladders and elytra
  are unchanged.
- **Half-Life 1 style textures.** Block textures are smoothly filtered up close
  like the GoldSrc renderer's, and their colours are muted, warmed and reduced
  to a paletted look. This is done in the shader from Minecraft's own textures.
- **Source-style HUD.** Laid out like Half-Life 2's HUD on its 640x480
  reference screen and scaled to the window: health, suit (armour) and food as
  large glowing yellow numbers in rounded dark panels at the bottom left, the
  held item's count or durability as AMMO at the bottom right, an oxygen meter underwater, a weapon
  selection row along the top when you change slot, and a small crosshair.
  The HUD font is DejaVu Sans Bold (license in
  `assets/mcsource/font/dejavu_license.txt`).
- **Half-Life 2 style sky and haze.** Minecraft's blocky clouds are replaced
  by a soft painted cloud layer in the sky, and distant terrain fades into the
  sky colour with distance haze.
- **Suit hand.** Your first-person arm is a rigged hand model: an orange
  hazard-suit sleeve with an armour plate, a ribbed cuff, and a black glove
  with a full finger skeleton (17 bones) resting in a slowly breathing curl.
- **Zombie.** Zombies are a rigged zombie scientist in a torn lab coat with a
  parasite on its head and clawed hands, with its own idle, walk, attack and
  death animations; the walk keeps pace with how fast it moves.
- **Kart.** A drivable go-kart, found in the Tools & Utilities tab. Use it on
  the ground to place it, use the kart to get in, then drive with W and S and
  steer with A and D; it slides a little through fast corners and climbs
  one-block steps. Sneak to get out; hit it to pick it back up.
- All three models are this mod's own, built by the scripts in
  `tools/models` and shipped inside the mod jar. No Valve or Nintendo models,
  textures or code are used.
- **Test world.** A new world type, *MC:Source Test Hills*: rolling grassy
  hills with a stone brick house near spawn, to compare smooth terrain against
  sharp built blocks.

## Install on your Mac

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for
   Minecraft **26.3** with the Fabric installer. This adds a "fabric-loader-26.3"
   profile to the normal Minecraft Launcher.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3.
3. Get `mcsource-<version>.jar`: either download the `mcsource-mod` artifact
   from the latest run on the repository's **Actions** tab, or build it yourself
   (below).
4. Put both jars in `~/Library/Application Support/minecraft/mods`.
5. Start the Fabric profile, choose **Create New World → More → World Type →
   MC:Source Test Hills**, and create the world.

## Build

Needs JDK 25.

```sh
./gradlew build
```

The mod jar is written to `build/libs/`. `./gradlew runClient` starts a dev
client with the mod loaded.

## How it works

- `SmoothMath` holds the surface math shared by both sides: each grid corner's
  offset from how many of its eight surrounding blocks are terrain, the
  surface between corners, and the collision column heights.
- `SmoothTerrain` reads the blocks around a block and computes its corner
  offsets. It only reads block states, so the client and server always agree,
  and every block sharing a corner moves it the same way, so there are no gaps.
- `BlockStateBaseMixin` returns the smooth shape from `getCollisionShape` and
  `getShape` for smoothed blocks, and marks them as reaching outside their cell
  so raised ground is checked for collision.
- `SmoothBlockModel` (client) wraps every block model and, for smoothed blocks,
  moves each vertex up or down with its corners before it is meshed.
- `assets/minecraft/shaders/core/lightmap.fsh` replaces the light colour table
  every block and mob is lit through, and
  `assets/minecraft/shaders/include/light.glsl` replaces the directional
  light used on mobs and items.
- `SunLight` (client) traces a few rays from each block vertex towards the
  sun while the chunk is meshed and darkens the vertex colour where the sun is
  blocked or the surface faces away.
- `SourceMovement` holds the friction and acceleration math, and
  `LivingEntityMixin` uses it in place of Minecraft's walking and air
  movement for your own player.
- `SourceHud` (client) replaces the vanilla health, armour, food, air,
  experience and hotbar HUD elements, and the crosshair.
- `assets/minecraft/shaders/include/texture_sampling.glsl` replaces how block
  textures are sampled, for the Half-Life 1 look.
- `GoldSrcModel` reads models in the Half-Life 1 `.mdl` layout (bones,
  meshes, paletted textures and run-length encoded animations), written from
  public descriptions of the format. The mod's own models use that layout.
  `SourceRigs` (client) lists which mobs get which model and which animations
  stand for idle, walk, attack and death; adding a mob there is all a new rig
  needs. `SourceMobRenderer` poses and skins the model each frame, and
  `LivingEntityRendererMixin` draws it in place of the Minecraft model.
- `tools/models` builds the models: shapes sculpted from blended distance
  fields, meshed with surface nets, bound to a skeleton, painted with
  procedural textures and animated in code. Run `python3 hand.py`,
  `zombie.py` or `kart.py` with an output path (needs numpy).
- `KartEntity` holds the kart's driving: throttle, braking, steering that
  turns the driver with it, and grip that lets it slide when fast.
- `TestHillsGenerator` is the test world's chunk generator.

## Known limits

- Natural block types are always smoothed, including ones a player places.
  Telling placed blocks from generated ones needs per-block data synced to
  clients, which is a later step.
- Cliffs taller than one block keep a vertical middle; only their top and
  foot are rounded off.
- The sun direction is fixed and doesn't follow the sun in the sky. Sun
  shadows reach about 14 blocks, and mobs aren't shadowed by it yet.
- Lamp light still comes from Minecraft's light levels. Blocks keep
  Minecraft's fixed face shading on top of the sun.
- Mobs and items still use Minecraft's sharp textures; only blocks get the
  Half-Life 1 look so far. Menus are still Minecraft's.
- The rest of the Source look (materials, bloom and full HDR, view model,
  menus) comes in later steps of the plan. Valve's SDK shader code is never copied in;
  techniques are rebuilt from Valve's public talks and documentation.
