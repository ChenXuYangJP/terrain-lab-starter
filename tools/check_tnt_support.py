#!/usr/bin/env python3
"""Find TerrainLab sampling centers that cannot host a TNT and villager test, without third-party packages.

Usage:
    python3 tools/check_tnt_support.py /path/to/samplingseed.yml
    python3 tools/check_tnt_support.py /path/to/samplingseed.yml --json
    python3 tools/check_tnt_support.py /path/to/samplingseed.yml -moveout

Every entry's sample file (samples/<sample>.json.gz next to samplingseed.yml, or under
--samples) is read and only its 25 x 25 x 11 core is inspected and validated; use
inspect_terrain.py for a full file check. Offsets are relative to the center, the air block
at dy 0; the core spans dx and dz -12..12 and dy -5..5.

TNT: a TNT at the center falls through blocks without collision (grass, flowers, one snow
layer, vines...). It is unsupported when it lands in water, lava or another liquid, or finds
nothing to rest on down to dy -5.

Villager: some column with 1 <= dx^2 + dz^2 <= radius^2 (radius 8 by default, the TNT
entity-damage reach) must have a standing block at dy -5..3 with two free cells above it for
the villager's feet and head, so that the villager stays inside the core. The standing block
must have collision and must not be liquid, hazardous (magma, cactus, campfire...) or a
1.5-block fence or wall. A free cell has no collision, liquid, waterlogging or hazard.

Collision comes from block names, not Minecraft's shapes: the names in NO_COLLISION pass
through, and every other block, including names this table does not know, collides.

-moveout moves every flagged sample to samplingseed_unsupported_terrain.yml next to it,
appending to that file if it exists; -continue jobs read it and do not count its samples
toward per-biome, while still avoiding their positions. Entries whose sample file is
missing, invalid or does not match its entry are reported and kept. The plugin regenerates
samplingseed.yml from the job checkpoints at server start and after every saved sample,
which brings moved entries back; sample files and job checkpoints are never touched.

Exit status: 0 nothing flagged (or -moveout succeeded) and every entry checked, 1 flagged
entries left in place or entries that could not be checked, 2 unreadable or malformed
samplingseed.yml or failed write.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import functools
import gzip
import json
from pathlib import Path
import sys
from typing import Any
import zlib

import check_sample_overlap as seeds
import inspect_terrain as inspector


UNSUPPORTED_FILE = "samplingseed_unsupported_terrain.yml"
UNSUPPORTED_HEADER = """\
# TerrainLab sampling centers moved out of samplingseed.yml by
# tools/check_tnt_support.py -moveout, grouped by world seed.
# A TNT at each entry's center lands in a liquid or has no support inside the
# 25 x 25 x 11 core, or the core has no villager standing spot within the checked
# horizontal radius of the center. The plugin never writes this file; /terrain start
# and startyml -continue read it, so these samples do not count toward per-biome
# (their positions are still avoided).
#
# center is the block position [x, y, z] of the sample center in that seed's
# dimension (metadata.source_origin_xyz). biome is the requested terrain type the
# center was accepted for.

"""
CORE_BOTTOM, CORE_TOP, CORE_HALF_WIDTH = -5, 5, 12
# The villager's feet and head take the two cells above its standing block.
STAND_DY = range(CORE_BOTTOM, CORE_TOP - 1)

LIQUIDS = frozenset({"water", "lava", "bubble_column", "seagrass", "tall_seagrass", "kelp", "kelp_plant"})
NO_COLLISION = LIQUIDS | frozenset({
    "air", "cave_air", "void_air", "light", "structure_void",
    "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "bush", "short_dry_grass",
    "tall_dry_grass", "firefly_bush", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
    "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower", "open_eyeblossom",
    "closed_eyeblossom", "cactus_flower", "sunflower", "lilac", "rose_bush", "peony", "pitcher_plant",
    "pink_petals", "wildflowers", "leaf_litter", "spore_blossom", "mangrove_propagule", "frogspawn",
    "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots",
    "nether_sprouts", "hanging_roots", "pale_hanging_moss", "vine", "glow_lichen", "sculk_vein",
    "resin_clump", "cave_vines", "cave_vines_plant", "twisting_vines", "twisting_vines_plant",
    "weeping_vines", "weeping_vines_plant", "sugar_cane", "small_dripleaf", "big_dripleaf_stem",
    "wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "attached_melon_stem",
    "attached_pumpkin_stem", "torchflower_crop", "nether_wart", "sweet_berry_bush", "cobweb",
    "fire", "soul_fire", "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch",
    "redstone_wall_torch", "copper_torch", "copper_wall_torch", "redstone_wire", "tripwire",
    "tripwire_hook", "rail", "powered_rail", "detector_rail", "activator_rail", "lever",
    "nether_portal", "end_portal", "end_gateway",
    # Mobs sink into powder snow; only leather boots and falling blocks stand on it.
    "powder_snow",
})
NO_COLLISION_SUFFIXES = ("_sapling", "_tulip", "_button", "_pressure_plate", "_sign", "_banner",
                         "_coral", "_coral_fan", "_coral_wall_fan")
HAZARDS = frozenset({"magma_block", "cactus", "campfire", "soul_campfire", "fire", "soul_fire",
                     "sweet_berry_bush", "cobweb", "wither_rose", "powder_snow", "lava",
                     "nether_portal", "end_portal", "end_gateway"})
TALL_SUFFIXES = ("_fence", "_wall", "_fence_gate")


@dataclass(frozen=True)
class Block:
    collides: bool
    liquid: bool
    waterlogged: bool
    hazard: bool
    tall: bool

    def standable(self) -> bool:
        return self.collides and not (self.liquid or self.hazard or self.tall)

    def free(self) -> bool:
        return not (self.collides or self.liquid or self.waterlogged or self.hazard)


def parse_block(data: str) -> tuple[str, dict[str, str]]:
    """Splits BlockData such as minecraft:snow[layers=1] into its name and state properties."""
    name, _, state = data.partition("[")
    pairs = (item.partition("=") for item in state.rstrip("]").split(",") if item)
    return name, {key: value for key, _, value in pairs}


@functools.lru_cache(maxsize=None)
def classify(data: str) -> Block:
    name, state = parse_block(data)
    # Other namespaces keep their prefix, match nothing below, and collide.
    base = name.removeprefix("minecraft:")
    if base == "snow":
        collides = state.get("layers", "1") != "1"
    elif base.startswith("potted_"):
        collides = True
    elif base.endswith("_fence_gate"):
        collides = state.get("open") != "true"
    else:
        collides = base not in NO_COLLISION and not base.endswith(NO_COLLISION_SUFFIXES)
    waterlogged = state.get("waterlogged") == "true"
    return Block(collides=collides,
                 liquid=base in LIQUIDS or (waterlogged and not collides),
                 waterlogged=waterlogged,
                 hazard=base in HAZARDS,
                 tall=collides and base.endswith(TALL_SUFFIXES))


def tnt_landing(core: dict[str, Any]) -> tuple[int | None, str | None, int | None]:
    """(rest dy, block, block dy) for a TNT dropped at the center; rest dy is None when it is unsupported."""
    for dy in range(-1, CORE_BOTTOM - 1, -1):
        data = inspector.block_at(core, 0, dy, 0)
        block = classify(data)
        if block.liquid:
            return None, data, dy
        if block.collides:
            return dy + 1, data, dy
    return None, None, None


def disc(radius: int) -> list[tuple[int, int]]:
    """Columns (dx, dz) 1..radius blocks from the center column."""
    reach = radius * radius
    return [(dx, dz) for dx in range(-radius, radius + 1) for dz in range(-radius, radius + 1)
            if 0 < dx * dx + dz * dz <= reach]


def villager_spots(core: dict[str, Any], columns: list[tuple[int, int]]) -> tuple[int, tuple[int, int, int] | None]:
    """The number of villager spots and the feet offset (dx, dy, dz) of the nearest one."""
    count = 0
    nearest: tuple[int, int, int] | None = None
    best: tuple[int, int] | None = None
    for dx, dz in columns:
        for dy in STAND_DY:
            if (classify(inspector.block_at(core, dx, dy, dz)).standable()
                    and classify(inspector.block_at(core, dx, dy + 1, dz)).free()
                    and classify(inspector.block_at(core, dx, dy + 2, dz)).free()):
                count += 1
                key = (dx * dx + dz * dz, abs(dy + 1))
                if best is None or key < best:
                    best, nearest = key, (dx, dy + 1, dz)
    return count, nearest


@dataclass(frozen=True)
class Check:
    entry: seeds.Entry
    error: str | None = None
    tnt_rest_dy: int | None = None
    tnt_block: str | None = None
    tnt_block_dy: int | None = None
    spots: int = 0
    nearest: tuple[int, int, int] | None = None

    @property
    def tnt_supported(self) -> bool:
        return self.tnt_rest_dy is not None

    @property
    def flagged(self) -> bool:
        return self.error is None and (not self.tnt_supported or self.spots == 0)

    def reasons(self, radius: int) -> list[str]:
        reasons = []
        if self.tnt_block is not None and not self.tnt_supported:
            reasons.append(f"TNT lands in {self.tnt_block} at dy {self.tnt_block_dy}")
        elif not self.tnt_supported:
            reasons.append(f"TNT finds no support from dy -1 to {CORE_BOTTOM}")
        if self.spots == 0:
            reasons.append(f"no villager standing spot 1-{radius} blocks away inside the core")
        return reasons

    def summary(self, radius: int) -> dict[str, Any]:
        return {"seed": self.entry.seed, "dimension": self.entry.dimension, **self.entry.summary(),
                "reasons": self.reasons(radius),
                "tnt": {"supported": self.tnt_supported, "rest_dy": self.tnt_rest_dy,
                        "block": self.tnt_block, "block_dy": self.tnt_block_dy},
                "villager_spots": self.spots,
                "nearest_villager_feet_dxyz": list(self.nearest) if self.nearest else None}


def read_core(path: Path) -> dict[str, Any]:
    """A snapshot with only its metadata and core checked; inspect_terrain.read_snapshot is about 6x slower."""
    with gzip.open(path, "rb") as stream:
        raw = stream.read(inspector.MAX_JSON_BYTES + 1)
    inspector.require(len(raw) <= inspector.MAX_JSON_BYTES, "Snapshot JSON exceeds the 64 MiB limit")
    snapshot = json.loads(raw.decode("utf-8"))
    inspector.require(type(snapshot) is dict and type(snapshot.get("metadata")) is dict,
                      "Snapshot must be an object with a metadata object")
    core = snapshot.get("core")
    inspector.validate_grid(core, "core")
    inspector.require(core["shape_yzx"] == [11, 25, 25] and core["min_offset_xyz"] == [-12, -5, -12],
                      "core must have shape_yzx [11,25,25] and min_offset_xyz [-12,-5,-12]")
    return snapshot


def check(entry: seeds.Entry, samples: Path, columns: list[tuple[int, int]]) -> Check:
    path = samples / f"{entry.sample}.json.gz"
    if not path.is_file():
        return Check(entry, error=f"sample file not found: {path}")
    try:
        snapshot = read_core(path)
    except (OSError, EOFError, UnicodeError, ValueError, RecursionError, zlib.error) as exc:
        return Check(entry, error=f"invalid sample file {path}: {exc}")
    metadata = snapshot["metadata"]
    if (snapshot.get("id") != entry.sample or metadata.get("seed") != entry.seed
            or metadata.get("dimension") != entry.dimension
            or metadata.get("source_origin_xyz") != list(entry.center)):
        return Check(entry, error=f"id, seed, dimension or center in {path} differ from the entry")
    core = snapshot["core"]
    rest, block, block_dy = tnt_landing(core)
    spots, nearest = villager_spots(core, columns)
    return Check(entry, tnt_rest_dy=rest, tnt_block=block, tnt_block_dy=block_dy, spots=spots, nearest=nearest)


def move_out(path: Path, centers: seeds.CenterFile, flagged: set[seeds.Entry]) -> dict[str, Any]:
    kept: dict[str, list[seeds.Entry]] = {}
    moved: dict[str, list[seeds.Entry]] = {}
    for seed, entries in centers.groups.items():
        for entry in entries:
            (moved if entry in flagged else kept).setdefault(seed, []).append(entry)
    return seeds.move_entries(path, centers, kept, moved, UNSUPPORTED_FILE, UNSUPPORTED_HEADER)


def radius_value(text: str) -> int:
    value = int(text)
    if not 1 <= value <= CORE_HALF_WIDTH:
        raise argparse.ArgumentTypeError(f"must be between 1 and {CORE_HALF_WIDTH}")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("path", type=Path, help="A samplingseed.yml file")
    parser.add_argument("--samples", type=Path, help="Directory of sample_*.json.gz files (default: samples/ next to path)")
    parser.add_argument("--radius", type=radius_value, default=8,
                        help=f"Largest horizontal villager distance in blocks, 1..{CORE_HALF_WIDTH} (default 8)")
    parser.add_argument("--json", action="store_true", help="Print the flagged and unchecked entries as JSON")
    parser.add_argument("-moveout", "--moveout", action="store_true",
                        help=f"Move every flagged sample to {UNSUPPORTED_FILE}")
    args = parser.parse_args(argv)
    samples = args.samples if args.samples is not None else args.path.parent / "samples"
    columns = disc(args.radius)
    try:
        centers = seeds.read_centers(args.path)
        checks = [check(entry, samples, columns) for entry in centers.entries()]
        flagged = [result for result in checks if result.flagged]
        unchecked = [result for result in checks if result.error is not None]
        moved = move_out(args.path, centers, {result.entry for result in flagged}) if args.moveout and flagged else None
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    no_tnt = sum(1 for result in flagged if not result.tnt_supported)
    no_villager = sum(1 for result in flagged if result.spots == 0)
    if args.json:
        print(json.dumps({
            "path": str(args.path), "samples": str(samples), "villager_radius": args.radius,
            "entries": len(checks), "checked": len(checks) - len(unchecked),
            "tnt_unsupported": no_tnt, "no_villager_spot": no_villager,
            "flagged": [result.summary(args.radius) for result in flagged],
            "unchecked": [{**result.entry.summary(), "error": result.error} for result in unchecked],
            "moveout": moved,
        }, ensure_ascii=False, indent=2))
    else:
        for result in unchecked:
            print(f"WARNING: {result.entry.sample} (line {result.entry.line}) not checked: {result.error}",
                  file=sys.stderr)
        for result in flagged:
            entry = result.entry
            print(f"seed {entry.seed} / {entry.dimension}: {entry.sample}  {entry.job}  {entry.biome}  "
                  f"{list(entry.center)}  (line {entry.line})")
            for reason in result.reasons(args.radius):
                print(f"  {reason}")
        print(f"Checked {len(checks) - len(unchecked)} of {len(checks)} sample entries in the 25 x 25 x 11 core: "
              f"{no_tnt} cannot support a TNT, {no_villager} have no villager spot 1-{args.radius} blocks away.")
        if not flagged:
            print("No unsupported sampling positions.")
        else:
            print(f"Found {len(flagged)} unsupported sampling positions.")
        if unchecked:
            print(f"{len(unchecked)} entries could not be checked and stay in {args.path}.")
        if moved is not None:
            print(f"Moved {moved['moved']} sample entries to {moved['target_file']} "
                  f"({moved['added']} new there); kept {moved['kept']} in {args.path}.")
            print("Note: the plugin regenerates samplingseed.yml from jobs/ at server start and after "
                  "every saved sample, which brings moved entries back.")
    if unchecked:
        return 1
    if moved is not None or not flagged:
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
