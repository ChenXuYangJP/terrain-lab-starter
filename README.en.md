# TerrainLab: Natural Terrain Capture and In-Game Preview

[中文](README.md) | English

TerrainLab is a standalone **Paper 26.3 / Java 25** plugin. It first builds a terrain dataset, then prepares scenes for later NPC hazard-avoidance experiments. At this stage it captures only blocks and biome labels: collection does not create experiment NPCs, does not detonate TNT, and does not save animals, monsters or any other source entities. Only the preview sandbox can spawn TNT, through the explicit `/terrain tnt` command, and nothing from the sandbox is saved. It uses a different plugin name, commands and data directory from the earlier `NpcLab`, so both can be installed together.

Each sample contains a **25×25×11 core region** with the candidate center as its origin, plus a default **41×41×25 context region**. Samples are saved as gzip-compressed UTF-8 JSON files. They can be read directly in Python, viewed one at a time in game, or paged through in a 3×3 grid.

**This data describes local terrain. The gallery provides a visual preview and is not currently a strict replay of vanilla physics.** For example, an open spot above the sea surface can be captured, but there is no guarantee that a villager can stand there, or that lit TNT will stay at the center.

## 1. Installation and first capture

### Environment

- Server: Paper 26.3. The project's compile dependency is pinned to `paper-api:26.3.build.218-stable`.
- Java: JDK 25. Paper's current installation docs map 26.1 and later to Java 25. [Paper installation guide](https://docs.papermc.io/paper/getting-started/)
- Client: a Minecraft Java Edition client connected to that server.
- Permission: OP, or the permission node `terrainlab.admin`. Capture and status commands can also be run from the server console; preview commands must be run in game.

### Using the provided JAR

Download the prebuilt `terrain-lab-0.3.0.jar` and `SHA256SUMS.txt` from [GitHub Releases](https://github.com/ChenXuYangJP/terrain-lab-starter/releases/tag/v0.3.0). With both in the same directory, you can verify the file first:

```bash
sha256sum -c SHA256SUMS.txt
```

Run `stop` in the server console, wait for the server to exit cleanly, then copy the JAR into the server's `plugins` directory:

```bash
cp terrain-lab-0.3.0.jar /path/to/paper-server/plugins/
```

Change the example path to your own server directory and start Paper as usual. The first start generates `plugins/TerrainLab/config.yml`. The server log should show `TerrainLab ready`.

When upgrading from an older version, delete the old JAR (such as `terrain-lab-0.2.0.jar`) from `plugins` before copying the new JAR; otherwise Paper finds two plugins with the same name and refuses to load both. The config, samples and job files in `plugins/TerrainLab/` are kept, and jobs started with an older version can still be resumed.

This JAR is a server plugin. It goes in the Paper server's `plugins` directory, not into the client. A regular Minecraft client can connect and watch.

### Capture one sample first

Run these in game, in order:

```text
/terrain catalog
/terrain start 101 1 overworld minecraft:plains
/terrain status
```

The first command lists how many target biomes the running server has and writes `plugins/TerrainLab/catalog.json`. The second creates a new job: using seed `101`, try to save one qualifying sample in the Overworld's `minecraft:plains` only. Once `Saved sample_...` appears, run:

```text
/terrain list 1
/terrain show sample_<actual_id> core
```

Replace `sample_<actual_id>` with the full ID printed by `/terrain list 1`. When you are done previewing, run:

```text
/terrain leave
```

Confirm this whole flow succeeds before scaling up collection. "Success" here means the gzip is saved, it can be read and inspected, and you can enter and leave the preview. It does not merely mean the server accepted the command.

## 2. Collection scale and biome count

Do not hard-code the earlier "54 Overworld + 5 Nether + 5 End" into the dataset. 26.2 added Sulfur Caves and 26.3 added Dappled Forest; versions, feature flags and data packs all affect what is available. [26.2 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2) · [26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)

The plugin reads entries from the running server's biome registry, then uses the `minecraft:is_overworld`, `minecraft:is_nether` and `minecraft:is_end` tags to form collection targets, excluding `minecraft:the_void`. **Being registered, belonging to a dimension tag, and being findable at a qualifying spot in a given world are three different things.** A biome being in a tag does not guarantee that it can be found within the limited search budget, nor that the world's generator actually produces it. [Paper Biome API](https://jd.papermc.io/paper/26.3/org/bukkit/block/Biome.html) · [Registry docs](https://docs.papermc.io/paper/dev/registries/)

With default 26.3 content, you can budget for 56 Overworld, 5 Nether and 5 End biomes, i.e. **66 candidate targets**. The real count is whatever your `/terrain catalog` prints. All numbers below are planned quotas, and the final samples may fall short.

| Stage | Command | Planned count with 66 targets |
|---|---|---:|
| Single-sample check | `/terrain start 101 1 overworld minecraft:plains` | 1 |
| Trial run over all types | `/terrain start 101 1` | 66 |
| About 500 samples | `/terrain start 101 8` | 528 |
| Five seeds, ten per type | `/terrain start 101,202,303,404,505 10` | 3,300 |

Full syntax of `/terrain start`:

```text
/terrain start <seed1,seed2,...> <per_biome_count> [all|overworld|nether|end] [biome_key]
```

Seeds are decimal signed 64-bit integers. Up to 20 distinct seeds are accepted, and the per-type quota is 1–100. If the dimension is omitted, the dimension list from the config is used. When entering multiple seeds, do not put spaces around the commas.

Every `start` creates a new `job_id` and uses that job's own source worlds. Even with the same seed, a new job does not continue the old job's counts. To continue an old job, use `resume`.

### Reading seeds from worldseeds.yml

You can also keep the seeds in `plugins/TerrainLab/worldseeds.yml` and start a job with `startyml`:

```text
/terrain startyml <per_biome_count> [all|overworld|nether|end] [biome_key]
```

The file is created the first time the plugin starts. It contains only comments and a commented-out sample. Uncomment and edit the `seeds` list:

```yaml
seeds:
  - "101"
  - "202"
  - "303"
  - "404"
  - "505"
```

With that list, `/terrain startyml 10` is exactly the same as `/terrain start 101,202,303,404,505 10`, and the optional dimension and biome arguments work the same way. The seed rules match `start`: decimal signed 64-bit integers, 1–20 of them, with duplicates ignored. Quote every seed: YAML reads an unquoted number with a leading zero as octal, and very large numbers may not be read as integers at all. The file is re-read every time `startyml` runs, so no server restart is needed.

### Continuing collection: -continue

Without `-continue`, a new job is independent of earlier ones: its per-biome count covers only that job, and the candidate search is seeded only by world seed, biome and dimension. A new job with the same seeds therefore retraces the earlier job's search and collects the same positions first. This is deliberate, for reproducibility.

Add `-continue` at the end of `start` or `startyml` to build on the samples you already have:

```text
/terrain startyml 9 -continue
/terrain start 101,202 9 overworld -continue
```

- **Top up to N.** Existing samples count toward the per-biome number, and each seed × dimension × biome only collects the difference. For example, if seed 101 already has 1 plains sample, `startyml 9 -continue` collects 8 more, for 9 in total; combinations that already have 9 or more are skipped. Combinations that fell short last time because no good position was found are retried, with a fresh candidate budget. Repeated positions count once. If every combination is already full, the command refuses to start.
- **Unsupported samples don't count.** Samples listed in `samplingseed_unsupported_terrain.yml` (written by `tools/check_tnt_support.py -moveout`, see below) are left out of the per-biome count, so their seed × dimension × biome is topped up again. Their positions are still avoided. If that file exists but cannot be read, the command refuses to start.
- **Avoid existing centers.** The existing samples are the ones listed in `samplingseed.yml` (from the job records in `jobs/`). New centers follow the same rules against them as within a job: at least 48 blocks apart in the same dimension, with no overlapping context regions, regardless of biome. Candidates rejected for this appear in the report CSV as `near_or_overlapping_earlier_job_sample`.
- **A fresh search order.** A continuation job uses a new random salt, so it doesn't retrace earlier jobs. The salt is saved in the job file, so `resume` behaves the same.

Right after the server starts, the plugin first reads all job records; until then, `-continue` asks you to retry shortly. After `clearsamples` there are no existing samples, so `-continue` behaves like a plain job (with a fresh search order).

### samplingseed.yml: record of sampling centers

The plugin maintains `plugins/TerrainLab/samplingseed.yml`, which lists the center coordinate and terrain type of every saved sample, grouped by world seed:

```yaml
world_seeds:
  "101":
    - sample: "sample_a0535c4b703a358872cadc05"
      job: "job_mhf2k3ab_1a2b3c4d"
      dimension: "overworld"
      biome: "minecraft:plains"
      center: [1234, 72, -567]
```

`center` is the block position of the sample center in that seed's dimension, the same as `source_origin_xyz` in the sample metadata; `biome` is the requested biome the center was accepted for. The file is built from the job checkpoints in `jobs/`, covering every job started with `start` or `startyml`. It is rewritten in full at server start and after every saved sample, so manual edits are overwritten, and deleting a sample file does not remove its entry. To find the terrain again by coordinates in another world, you also need the same Minecraft version, data packs and generation settings (see section 7).

To check whether any saved centers are too close together, run the overlap checker on this file. It needs only Python 3:

```bash
python3 tools/check_sample_overlap.py /path/to/paper-server/plugins/TerrainLab/samplingseed.yml
python3 tools/check_sample_overlap.py /path/to/samplingseed.yml --json
python3 tools/check_sample_overlap.py /path/to/samplingseed.yml -moveout
```

Two samples overlap when they share a world seed and a dimension and their 25 × 25 × 11 core boxes share at least one block. The default mode lists every overlapping pair with the line numbers; `--json` prints the same pairs as JSON. `-moveout` keeps the first sample of each pair (in file order) and moves the others into `samplingseed_overlaped.yml` next to it, appending if that file exists. Because the plugin rewrites `samplingseed.yml` at server start and after every saved sample, the moved entries come back afterwards; sample files and job checkpoints are never touched. Exit status: 0 no overlaps (or `-moveout` succeeded), 1 overlaps found, 2 unreadable or malformed file or failed write.

To find centers that cannot host a TNT and villager test, run the support checker. It reads each entry's sample file from `samples/` next to the file (or `--samples DIR`) and also needs only Python 3:

```bash
python3 tools/check_tnt_support.py /path/to/paper-server/plugins/TerrainLab/samplingseed.yml
python3 tools/check_tnt_support.py /path/to/samplingseed.yml --json
python3 tools/check_tnt_support.py /path/to/samplingseed.yml -moveout
```

Only the 25 × 25 × 11 core is inspected. A sample is flagged when either check fails:

- **TNT:** a TNT at the center falls through blocks without collision, such as grass, flowers, a single snow layer or vines. The check fails if the TNT lands in water, lava or another liquid, or finds nothing to rest on down to the bottom of the core.
- **Villager:** a villager needs a column 1–8 blocks from the center (horizontal Euclidean distance; change it with `--radius`) with a standing block between dy −5 and +3 and two free cells above it. That keeps the villager inside the core. The standing block must have collision and must not be liquid, magma, cactus, a campfire, a fence or a wall. The two free cells must have no collision, liquid or hazard.

Collision comes from a name table in the script, not from Minecraft's shapes; block names it does not know are treated as solid. `-moveout` moves the flagged entries into `samplingseed_unsupported_terrain.yml` next to it, appending if that file exists. Entries whose sample file is missing or does not match are reported and kept. A later `start` or `startyml` with `-continue` reads that file and does not count its samples, so their buckets are topped up again. As with the overlap checker, the plugin's next rewrite of `samplingseed.yml` brings moved entries back. Exit status: 0 nothing flagged (or `-moveout` succeeded) and every entry checked, 1 flagged entries left in place or entries that could not be checked, 2 unreadable or malformed file or failed write.

## 3. What site filtering actually guarantees

By default, the filter requires that **a 3×3 horizontal area around the center, 2 blocks high, is entirely air**, and that the block directly below the center is not air. The whole 25×25 region does not need to be open, so surrounding slopes, trees, cliffs, water surfaces and caves are kept.

The default is `site.require-solid-support: false`, so the block directly below may be water, lava, leaves or another natural block. This keeps water and vegetation scenes, and avoids building artificial platforms just to fill biome quotas. Samples record:

- `capture_eligible: true`: meets this capture's terrain rules.
- `support_blockdata`, `support_is_liquid`, `support_kind`: the actual block below the center and its classification.
- `require_solid_support`, `clearance_radius`, `clearance_height`: the filter settings used by this job.

These fields **do not mean that an NPC can stand there, that an escape path exists, or that TNT can be placed stably**. When NPCs are created later, you will need extra checks for entity hitboxes, footing, headroom, liquids and dangerous blocks, reachable paths and starting distance.

If you want to accept only conservative full-block support, set this in `plugins/TerrainLab/config.yml`:

```yaml
site:
  clearance-radius: 1
  clearance-height: 2
  require-solid-support: true
```

Support is then judged by a full-cube collision shape rather than just `Material.isSolid()`. This is still only a local geometric condition. It does not automatically exclude every damaging or special-behavior block, and it does not generate a "safe NPC start". After you change the config and restart the server, new jobs use the new settings; resuming an existing job uses the settings saved in its checkpoint.

Candidate centers leave room above and below for the full context, to avoid silently padding with air outside the world height limits. Nether candidate centers are additionally limited to `Y ≤ 115`, to avoid treating the large air space above the bedrock ceiling as a regular Nether scene.

Oceans can yield air positions above the water surface. However, the core region only includes 5 blocks below the center, so **it cannot be used to infer the depth of a deep ocean, its seabed structure or the full water column**. The default context also reaches only 12 blocks down. If your research question depends on deeper surroundings, you need a larger context or to keep the original source world.

## 4. Sampling, shortfalls and independence

Sampling uses a limited budget. It first picks a random search origin and calls biome location, then picks candidate X/Z columns near the located result. For each column it checks every layer in the valid Y range and uses reservoir sampling to pick one of that column's qualifying heights. This can cover underground biomes instead of always taking the topmost surface.

**This is not uniform random sampling over the whole world or the whole biome.** The locate algorithm, search range, neighborhood radius and the "open air spot" eligibility condition all affect the distribution. Reservoir sampling is uniform only over the qualifying heights in the column already chosen.

By default, each `seed × dimension × biome` bucket tries at most 160 candidates, and locate calls are capped too. Failing to find the target, unqualified candidates, centers that are too close, overlapping contexts or an exhausted budget can all cause shortfalls. The program never modifies terrain to make up numbers. You can use:

```text
/terrain status
```

to see current targets, attempt counts and rejection counts. Each job also writes:

```text
plugins/TerrainLab/jobs/<job_id>-report.csv
```

The CSV contains `seed, dimension, biome, target, accepted, shortfall, candidates, locates, finished, rejections`. A job showing `complete` means every bucket has finished trying; it does not guarantee that every bucket reached its quota.

The default minimum center distance is 48 blocks, and overlapping context bounding boxes are rejected as well. These checks apply only to existing samples **in the same job, with the same seed and dimension**. They reduce spatial duplication but cannot prove statistical independence; different jobs may still produce identical or similar terrain.

`terrain_sha256` identifies identical context block data. Samples are currently not dropped automatically just because their hashes match. The same flat ground can appear in different biomes, and whether to merge such samples depends on your research goal. This hash does not include biome labels and does not measure "near-identical".

When you later split data into training/validation/test sets, group by seed or by complete scene first. Different NPC starts, different actions and repeated explosion trials on the same terrain should stay in the same group. Do not randomly split adjacent ticks or different trials of the same scene across training and test. Equal per-biome quotas are good for comparing coverage but do not represent how often biomes occur in natural worlds. When reporting results, distinguish per-biome averages from weighting by the actual deployment distribution.

## 5. Pause, resume and job files

```text
/terrain pause
/terrain resume
/terrain resume job_<job_id>
/terrain status
```

`pause` stops further sampling, but the sample already in progress can still be read, compressed and saved. Wait until the status no longer shows busy before starting a new job. `stop` is a command alias for `pause` and does not delete any data.

`resume` without an ID reads the job pointed to by `latest-job.txt`; with an ID, it resumes that checkpoint. If the program published a complete sample but had not yet counted it in the job, the resume logic checks the sample against the job information and records it. Temporary files that were never published do not count as samples.

Job files store the collection settings and the Minecraft version. Resume never silently adopts a new config. When the Minecraft version changes, start a new job to avoid mixing versions.

| Path | Contents |
|---|---|
| `plugins/TerrainLab/config.yml` | Default settings for new jobs |
| `plugins/TerrainLab/catalog.json` | Most recently exported registry and dimension tags |
| `plugins/TerrainLab/samples/sample_*.json.gz` | Complete terrain samples |
| `plugins/TerrainLab/jobs/job_*.json.gz` | Job checkpoints |
| `plugins/TerrainLab/jobs/job_*-report.csv` | Per-bucket yield, shortfalls and rejection counts |
| `plugins/TerrainLab/latest-job.txt` | Most recent job ID |
| `plugins/TerrainLab/gallery-state.yml` | Preview region recovery information |
| `plugins/TerrainLab/worldseeds.yml` | World seed list for `startyml` (edited by you) |
| `plugins/TerrainLab/samplingseed.yml` | Sampling center coordinates and terrain types, grouped by world seed (generated from job checkpoints) |

Source worlds and the preview world also take up space in Minecraft's world save. The plugin manages these worlds with its own world keys, ownership markers and UUID checks, and refuses to take over an existing world whose ownership it cannot confirm. Pausing, changing pages and finishing jobs do not automatically delete source saves.

### Clearing all samples

```text
/terrain clearsamples
/terrain clearsamples confirm <code>
/terrain clearsamples cancel
```

The first step lists how many files will be deleted and gives a 6-character confirmation code. The code is valid for 60 seconds and only for the same player (or the console). Nothing is deleted until the second step repeats the code; a wrong or expired code cancels the request, and you start again from the first step. Clearing is refused while a job is running or busy, so run `/terrain pause` and wait first.

Deletion is **permanent** and covers: every sample in `samples/` (including temporary files left by interrupted saves), the job checkpoints and report CSVs in `jobs/`, and `latest-job.txt`. `samplingseed.yml` is then rewritten empty, and `/terrain resume` has no job left to resume. `worldseeds.yml`, `config.yml`, `catalog.json` and the preview state are kept. The source and preview world saves are not deleted either; freeing that disk space has to be done separately. Blocks already pasted in the preview stay until the next `show` or `gallery`.

## 6. Core and context regions

Sizes in the table below are given as **X × Z × Y**. Array shapes in the file are recorded as **Y, Z, X**.

| Region | Spatial size | Range relative to center | `shape_yzx` | Cells |
|---|---|---|---|---:|
| core | 25×25×11 | X/Z: −12 to 12; Y: −5 to 5 | `[11,25,25]` | 6,875 |
| context | 41×41×25 | X/Z: −20 to 20; Y: −12 to 12 | `[25,41,41]` | 42,025 |

**The core is entirely contained in the context.** The unique space covered by default is 42,025 cells; do not add 6,875 on top as extra space. To make it easy for models to read the core window directly, the file stores both arrays, so the indices for the core are stored twice.

25×25×11 can be used as a fixed model input, but its size alone does not justify claiming that it covers every TNT effect. In the Paper API, `power=4F` corresponds to TNT; that does not mean all block destruction, entity damage, knockback and later terrain changes are strictly confined to a "4-block radius". [Paper World API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)

In particular, the 5 blocks above and below cut off part of cave ceilings, lower strata, fluids and support structures. Escape paths may route outside the horizontal boundary, and blocks that fall, flow or are pulled by gravity after an explosion can carry boundary effects back in. **41×41×25 is also only the current engineering starting point for the saved context, with no verified guarantee that it is "large enough".** Future control experiments should use the original location or a larger context, record whether the NPC, paths and block changes touch the edges, and enlarge the range or mark the trial as boundary-limited when needed.

## 7. File format and fidelity

Each sample is `sample_<safe_id>.json.gz`. After gzip decompression it is UTF-8 JSON with these fixed top-level keys:

```text
schema_version
id
metadata
core
context
```

The current `schema_version` is `1`. Each grid has these keys:

| Field | Meaning |
|---|---|
| `shape_yzx` | `[sizeY,sizeZ,sizeX]` |
| `min_offset_xyz` | Minimum relative coordinates `[minDx,minDy,minDz]` |
| `axis_order` | Always `"y,z,x"`; X varies fastest |
| `palette` | List of full BlockData strings, e.g. `minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]` |
| `indices` | Non-negative integer per cell, pointing into `palette` |
| `biome_palette` | List of namespaced biome keys |
| `biome_indices` | Biome label index for each cell |

Given relative coordinates `(dx,dy,dz)`, the index is:

```python
index = ((dy - minDy) * sizeZ + (dz - minDz)) * sizeX + (dx - minDx)
blockdata = palette[indices[index]]
```

For the core, this can be written directly as:

```python
index = ((dy + 5) * 25 + (dz + 12)) * 25 + (dx + 12)
```

Do not treat palette numbers from different grids as global block IDs. Index `0` may point to different blocks in two files. Look up each grid's own palette first, and compare the actual strings when checking core/context consistency.

### Metadata

`metadata` stores the seed, original world and dimension, absolute center coordinates, Minecraft/Paper/data version, capture start and end time and tick, world time and weather, difficulty, gamerules, feature flags, enabled data pack names, plugin list, filter rules, sampling settings and more. The seed is always a **decimal string**, so JSON readers in other languages that parse numbers as floating point do not round it.

Data packs are currently saved by name only, with no content digest; `datapack_content_fingerprint` is explicitly recorded as unknown. So "same seed" does not mean the same world can be rebuilt under different versions, data packs or generation settings.

There are two related biome concepts. Center sampling eligibility is judged by `getComputedBiome` and saved as `center_computed_biome`. The per-cell arrays store the underlying noise biome labels returned by `World.getBiome`, and the center's label is also saved as `center_noise_biome`. The two can differ near borders, so do not treat the arrays as the per-cell F3 display without telling them apart. [Paper RegionAccessor API](https://jd.papermc.io/paper/26.3/org/bukkit/RegionAccessor.html)

The following fields are explicit constraints of the current format:

```json
{
  "entities_omitted": true,
  "block_entity_nbt_omitted": true,
  "scheduled_ticks_omitted": true,
  "fidelity": "blockdata_only",
  "capture_atomic": false
}
```

BlockData such as block facing, stair shape and waterlogging is saved. Extra block entity NBT, such as chest inventories, sign text and spawner settings, is not saved. Entities, scheduled block/fluid ticks and runtime random state are not saved either. Voxel capture runs in batches across multiple server ticks, so start and end ticks are recorded, and the result cannot be called an atomic world snapshot of a single instant.

Each file is written completely as a temporary gzip in the same directory, closed, and then published with a non-overwriting hard link. Readers never see a half-written file as a finished sample, and an existing sample ID is never overwritten. This requires the samples' filesystem to support hard links. Disk durability after a sudden power loss is not guaranteed.

`metadata.terrain_sha256` is a SHA-256 over the full context's size, offset and BlockData decoded in Y/Z/X order; if there is no context, it covers the core instead. It does not depend on palette order, and it does not include biomes or other metadata. Both the Java and Python readers verify the declared hash.

## 8. Inspecting samples with Python

You don't need NumPy or Minecraft; Python 3 is enough:

```bash
python3 tools/inspect_terrain.py /path/to/paper-server/plugins/TerrainLab/samples/sample_<actual_id>.json.gz
```

For a machine-readable summary:

```bash
python3 tools/inspect_terrain.py /path/to/sample_<actual_id>.json.gz --json
```

The tool checks gzip integrity, JSON fields, seed and coordinate types, array sizes, palette indices, cell-by-cell core/context consistency and the declared terrain hash. It prints the center block, the center noise biome, the sizes of both layers and the hash. A successful check exits with `0`; invalid input exits with `2`. Passing the structural check does not mean that Minecraft physics replay has been verified.

Reading the core array in your own Python program:

```python
import gzip
import json

with gzip.open("sample_<actual_id>.json.gz", "rt", encoding="utf-8") as f:
    sample = json.load(f)

grid = sample["core"]
dy, dz, dx = 0, 0, 0
index = ((dy + 5) * 25 + (dz + 12)) * 25 + (dx + 12)
center_block = grid["palette"][grid["indices"][index]]
center_biome = grid["biome_palette"][grid["biome_indices"][index]]
seed = int(sample["metadata"]["seed"])
print(center_block, center_biome, seed)
```

This short example only demonstrates reading fields. Validate inputs with the inspector before batch processing.

## 9. Single view and 3×3 gallery

```text
/terrain list 1
/terrain show sample_<actual_id> core
/terrain show sample_<actual_id> context
/terrain gallery 1 core
/terrain gallery 2 core
/terrain gallery 1 context
/terrain goto 5
/terrain leave
```

`list` shows 10 IDs per page. `gallery` places at most 9 samples per page, in slots 1–9. Pages start at 1, and samples are sorted by ID, which is not guaranteed to match capture order. `show` displays only the given sample and defaults to `core` when the mode is omitted. `goto` jumps to the given slot on the current page, and `leave` restores the position and game mode you had before entering the preview.

The preview uses a single shared, dedicated NORMAL world. Samples from all source dimensions are placed at a display height of about `Y=96`, and each actual anchor keeps the source coordinates' alignment modulo 4. Grid centers are 64 blocks apart. Players watch in spectator mode (except in the `play` sandbox below). The plugin pastes blocks in batches and suppresses explosions, placing and breaking, flow and block updates in the preview as far as it can. Changing pages updates the shared display; players do not each get a separate scene.

Keeping biome cells aligned helps the display, but the preview world's seed, absolute coordinates, dimension and surroundings have all changed, so F3's computed biome, color blending, sky and lighting may still differ. To check the original labels, look at the sample metadata and `biome_indices`.

The preview omits extra NBT and scheduled ticks, and does not copy the full runtime state of the source dimension. Turning off random ticks, cancelling some events and pasting with `applyPhysics=false` cannot prove that every vanilla mechanic is frozen. **Use it to observe terrain shape, compare occlusion and check sampling quality; future explosion experiments need a dedicated replay and reset process.**

### Sandbox play mode

```text
/terrain play
/terrain play 3
/terrain tnt
/terrain tnt 1 20
/terrain villagerai
/terrain villagerhp
/terrain reset
/terrain goto 1
/terrain leave
```

Once a preview has finished, `play [slot]` opens a sandbox in the shared preview. It first lays a bedrock shell around every sample on the current page, then switches you to creative and teleports you to the center of the given slot's sample (slot 1 by default). From there you can place and break blocks, pour water and spawn mobs with spawn eggs, and block physics follows the normal rules: gravity blocks fall, fluids flow, pistons push, TNT and creepers break blocks, and fire spreads. While the sandbox is open, mob griefing, fire spread and drops are on. Random ticks stay at 0, so crops don't grow and leaves cut off from their trees don't decay. Natural mob spawning, the day cycle and weather stay off.

The bedrock shell is one block thick. The floor sits directly under the sample's bottom layer, and the four walls stand on the floor and rise 2 blocks above the sample's top layer. The top is open. The shell exists only in the preview world and is not part of the sample data.

Block changes in the sandbox are allowed only inside the "edit box": the sample's own volume plus 16 blocks of building room above it. Block changes outside the edit box are cancelled, including changes to the shell itself, fluids spilling over the wall top and neighbouring slots, so even a creative player cannot break the shell. Entities and players can leave the edit box; mobs that fall into the surrounding void are lost.

`tnt [slot] [fuse_ticks]` spawns one primed TNT at the center of the given slot's sample, which is the candidate center at capture time. The defaults are slot 1 and an 80-tick fuse (about 4 seconds), and the fuse range is 0–600. It works only while the sandbox is open. The explosion breaks only blocks inside the edit box, and the plugin records nothing about the result.

By default, villagers spawned in the preview world do not use their vanilla behaviour. The plugin marks them unaware, so they don't wander, work, sleep, gather or flee on their own, but gravity, pushing, damage and knockback still apply to them (Paper notes that unaware mobs may also have other unspecified behaviours disabled, such as drowning). `villagerai [on|off]` changes this setting, and without an argument it toggles between on and off. The change applies at once to villagers already in the preview world and to villagers spawned afterwards. The default is off, and it returns to off when the server restarts. Only villagers are affected, not other mobs, and not other worlds.

`villagerhp [on|off]` shows each preview villager's current health above its head, for example `❤ 17.5 / 20`: green above 50 %, yellow above 25 %, red otherwise. After damage or healing, the label updates on the next tick; on death it shows 0. It toggles the same way as `villagerai`: without an argument it switches between on and off, and the change applies at once to existing villagers and to villagers spawned afterwards. The default is off, and it returns to off when the server restarts. The label uses the villager's name display, so while it is on it replaces names set with a name tag; turning it off clears only the labels the plugin set. Only villagers are affected.

**Sandbox edits are never written back to `samples/`.** The gallery only reads sample files, and edits happen only in the preview world. `reset` re-reads the samples currently shown from disk and pastes them again, clearing all edits, the shells, mobs and dropped items. Any new `show` or `gallery` also ends the sandbox first and does the same cleanup. The shells and building room are recorded in `gallery-state.yml` before any bedrock is placed, so even if the server crashes during play, the next display still clears them. When the scene is rebuilt, players in the preview are switched back to spectator, and `leave` still restores the position and game mode they had before entering.

The sandbox is a shared scene: every player in the preview sees the same edits. `goto` returns to the spectator camera; running `play` again takes you straight back into the sandbox without rebuilding the shells.

The shell is an artificial boundary, so fluids, gravity blocks and lighting at the sample edges behave differently from the source world. For example, water at a cut face is held back by the wall, and gravity blocks on the bottom layer rest on the bedrock floor instead of the original strata. The sandbox is still not a strict replay of vanilla physics. TNT in the sandbox is for visual checks only and is not the replay and reset process that future explosion experiments need.

## 10. Performance, storage and configuration

Chunk generation, saving source worlds and biome search are usually heavier than compressing one local JSON file. Source world saves can be far larger than the sample files, and each new job creates new source worlds. Without data on machine performance, target distribution and actual runs, no completion time can be promised for 528 or 3,300 samples.

Defaults include:

| Setting | Default | Purpose |
|---|---:|---|
| `collection.max-candidate-attempts` | 160 | Candidate budget per bucket |
| `collection.candidates-per-locate` | 16 | Candidates near each locate result |
| `collection.locate-radius` | 4096 | Radius of a single locate call |
| `collection.random-origin-range` | 60000 | Range of random locate origins |
| `collection.candidate-radius` | 96 | Candidate range around a located point |
| `collection.minimum-center-distance` | 48 | Minimum center distance within the same job, seed and dimension |
| `collection.capture-blocks-per-tick` | 1000 | Max voxel reads per tick |
| `collection.concurrent-chunk-loads` | 2 | Max parallel chunk loads |
| `collection.cooldown-ticks` | 10 | Throttle between collection steps |
| `preview.blocks-per-tick` | 2000 | Max preview block pastes per tick |

Voxel reads are also bounded by a per-tick time budget. World and block access stays on the server main thread; the background only compresses, verifies and writes data that has already been copied out. However, `locateNearestBiome` is itself a synchronous call, and limiting the radius or adding cooldown cannot guarantee that a single call won't cause lag. [Paper World API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)

If a single sample already causes noticeable lag, first shrink the locate radius or the candidate search scale, watch the server, and then decide whether to scale up. A smaller budget may also increase shortfalls. Do not raise every budget at once to chase full quotas.

While a job runs, the plugin temporarily stops Paper from auto-pausing when the server is empty, so collection does not stop. This is released when the job pauses or finishes, or when the plugin shuts down. The server process must still keep running.

The context X/Z can be set to odd numbers from 25 to 59 and Y to odd numbers from 11 to 63, so it always contains the fixed core and fits the current gallery. Changing the range changes storage, generation load and sample selection conditions, so start a new job to record the new settings.

## 11. Editing and building in VS Code

Open the whole `terrain-lab-starter` folder in VS Code and edit the `.java` files, `config.yml` or `plugin.yml`. The JAR is a build artifact; you normally don't edit the bytecode inside it.

| File | Main responsibility |
|---|---|
| `src/main/java/io/github/terrainlab/TerrainLabPlugin.java` | Commands, collection state machine, checkpoints |
| `BiomeCatalog.java` | Runtime biome registry and dimension tags |
| `SiteFinder.java` | Candidate centers, air space and support filtering |
| `OwnedWorlds.java` | Dedicated source/preview worlds and ownership verification |
| `TerrainSnapshot.java` | Pure data model, coordinate indexing and consistency validation |
| `SnapshotIO.java` | gzip JSON, atomic publishing, hashing and reading |
| `SeedFiles.java` | Reads `worldseeds.yml` and generates `samplingseed.yml` |
| `Gallery.java` | In-game single-sample and paged display, sandbox play mode |
| `tools/inspect_terrain.py` | Sample inspection with no Minecraft dependency |
| `tools/check_sample_overlap.py` | Finds sampling centers in `samplingseed.yml` whose core boxes overlap |
| `tools/check_tnt_support.py` | Finds sampling centers that cannot hold a TNT or have no villager spot 1–8 blocks away |
| `tools/test_tnt_support.py` | Self-tests for the TNT/villager support checker, Python only |
| `tools/test_continue_exclusions.py` | Self-tests for how `-continue` reads `samplingseed_unsupported_terrain.yml` and counts buckets |
| `tools/test_snapshot_io.py` | Java/Python format and file behavior self-tests |
| `tools/test_owned_metadata.py` | Self-tests for parsing and rejecting Paper world identity metadata NBT |
| `pom.xml` | Java, Paper API and Gson build dependencies |
| `README.en.md` | English version of `README.md` (this file) |

Make sure `java -version`, `javac -version` and `mvn -version` all use JDK 25, then run this in the project root:

```bash
mvn package
```

The new JAR is at:

```text
target/terrain-lab-0.3.0.jar
```

After changing code, install by copying this new file from `target`. The prebuilt copy in `dist` is not updated when you edit the source.

```bash
cp target/terrain-lab-0.3.0.jar /path/to/paper-server/plugins/
```

Stop the server cleanly before copying, and restart it afterwards. Compiling from source depends on Gson 2.14.0 and the Paper API; at runtime, Paper provides these dependencies.

You can run the format self-tests, which don't depend on Bukkit, on their own:

```bash
python3 tools/test_snapshot_io.py --jdk /path/to/jdk-25 --gson /path/to/gson-2.14.0.jar
```

If `JAVA_HOME` already points to JDK 25 and Gson 2.14.0 is in the Maven cache, `python3 tools/test_snapshot_io.py` usually works as is. The test actually compiles two Java data classes in a temporary directory and writes samples, then reads them back with Python. It does not touch any server world.

## 12. Verification scope and future experiments

The verification scope of the current delivery is project compilation plus pure-data Java/Python self-tests. The self-tests cover gzip/UTF-8 round trips, 64-bit seeds, raw BlockData, all core indices, hash invariance under palette reordering, crop consistency, bad gzip, wrong schema, negative indices, NaN, duplicate JSON keys, overwrite refusal and concurrent atomic publishing.

A Maven build has actually been completed with JDK 25 and Paper API `26.3.build.218-stable`. All 8 snapshot format tests pass. Another 13 world identity metadata parsing tests pass, covering UUID reading from standard NBT and rejection of corrupt, oversized and overly deep input. The latter can be run after a Maven build:

```bash
python3 tools/test_owned_metadata.py --jdk /path/to/jdk-25 --maven-repo /path/to/local-maven-repository
```

The `-continue` exclusion tests also run after a Maven build, and the support checker's tests need only Python:

```bash
python3 tools/test_continue_exclusions.py --jdk /path/to/jdk-25 --maven-repo /path/to/local-maven-repository
python3 tools/test_tnt_support.py
```

The metadata tests use temporary synthetic NBT files and the actual compiled classes. They do not start a server, and passing them does not mean real world creation and reloading have been verified.

**No end-to-end run has been completed on a real Paper server, and no real sampled data ships with the project.** The synthetic data in the tests is only a temporary format fixture, not training scenes captured from a Minecraft world. So start with the single plains sample from section 1 to verify your local version, world generation, saving, preview and return flow.

0.3.0 passes the Maven build and the self-tests above. On a local Paper 26.3 server, `startyml`, `-continue` and `clearsamples` have been run, and `tools/check_sample_overlap.py` has been run on the resulting `samplingseed.yml`. The preview sandbox commands `play`, `tnt`, `villagerai`, `villagerhp` and `reset` have also been run there. The server log shows no TerrainLab errors. The bedrock shell, the edit range and explosions have not been systematically verified. When you first use the sandbox, follow section 9 on a single sample to confirm it behaves as expected.

When NPC hazard avoidance is added later, it will need: start and goal points where an NPC can actually stand, records of entity and path boundaries, an experiment world that keeps the original dimension and absolute height, a full reset for every trial, the initial state of TNT and NPCs, a distinction between requested actions and actual motion, real health after damage, termination reasons, and repeated trials. Scenes involving water, lava, gravity blocks, redstone or extra NBT also need a dedicated, repeatable replay strategy.

A fixed seed helps record where the world generation came from, but on its own it does not guarantee that runtime AI, random explosion processes and scheduled updates are identical. Only once replay conditions, missing information and boundary effects are all built into the trials will the action outcomes collected later be suitable for reliable comparison.

## Official references

- [Minecraft Java Edition 26.2: Sulfur Caves](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2)
- [Minecraft Java Edition 26.3: Dappled Forest](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
- [Paper installation and Java version requirements](https://docs.papermc.io/paper/getting-started/)
- [Paper Registry API](https://docs.papermc.io/paper/dev/registries/)
- [Paper 26.3 Biome](https://jd.papermc.io/paper/26.3/org/bukkit/block/Biome.html)
- [Paper 26.3 BiomeTagKeys](https://jd.papermc.io/paper/26.3/io/papermc/paper/registry/keys/tags/BiomeTagKeys.html)
- [Paper 26.3 World: locate, chunk, explosion and other APIs](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)
- [Paper 26.3 RegionAccessor: noise/computed biome](https://jd.papermc.io/paper/26.3/org/bukkit/RegionAccessor.html)
- [Paper 26.1: multi-world storage changes](https://papermc.io/news/26-1/)
