#!/usr/bin/env python3
"""Validate a TerrainLab .json.gz snapshot without Minecraft, Bukkit, or third-party packages.

Usage:
    python3 tools/inspect_terrain.py /path/to/sample_example.json.gz
    python3 tools/inspect_terrain.py /path/to/sample_example.json.gz --json

A successful result means that the file has a valid gzip stream and consistent data.
It does not establish that a blockdata-only preview faithfully replays Minecraft physics.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import sys
from typing import Any
import zlib


ID_PATTERN = re.compile(r"sample_[a-z0-9_]{1,100}\Z")
SEED_PATTERN = re.compile(r"-?(0|[1-9][0-9]*)\Z")
BIOME_PATTERN = re.compile(r"[a-z0-9._-]+:[a-z0-9/._-]+\Z")
HASH_DOMAIN = b"terrain-lab:blockdata:v1\n"
MAX_JSON_BYTES = 64 * 1024 * 1024
SNAPSHOT_KEYS = {"schema_version", "id", "metadata", "core", "context"}
GRID_KEYS = {"shape_yzx", "min_offset_xyz", "axis_order", "palette", "indices", "biome_palette", "biome_indices"}


class ValidationError(ValueError):
    """The file is readable but does not satisfy the snapshot contract."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def is_int(value: Any) -> bool:
    # bool is a subclass of int in Python, but is not an integer in this schema.
    return type(value) is int and -(2**31) <= value < 2**31


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(token: str) -> None:
    raise ValidationError(f"Non-finite JSON number: {token}")


def _validate_json_value(value: Any, depth: int = 0) -> None:
    require(depth <= 64, "Metadata nesting exceeds 64 levels")
    if value is None or type(value) in (str, bool, int):
        return
    if type(value) is float:
        require(math.isfinite(value), "Metadata contains a non-finite number")
        return
    if type(value) is list:
        for element in value:
            _validate_json_value(element, depth + 1)
        return
    if type(value) is dict:
        for key, element in value.items():
            require(type(key) is str, "Metadata object keys must be strings")
            _validate_json_value(element, depth + 1)
        return
    raise ValidationError(f"Unsupported metadata value: {type(value).__name__}")


def validate_grid(grid: Any, name: str) -> None:
    require(type(grid) is dict, f"{name} must be an object")
    require(set(grid) == GRID_KEYS, f"{name} must contain exactly {sorted(GRID_KEYS)}")
    require(grid["axis_order"] == "y,z,x", f"{name}.axis_order must be y,z,x")
    shape = grid["shape_yzx"]
    offsets = grid["min_offset_xyz"]
    require(type(shape) is list and len(shape) == 3 and all(is_int(v) and v > 0 for v in shape),
            f"{name}.shape_yzx must contain three positive integers")
    require(type(offsets) is list and len(offsets) == 3 and all(is_int(v) for v in offsets),
            f"{name}.min_offset_xyz must contain three signed 32-bit integers")
    sy, sz, sx = shape
    volume = sx * sy * sz
    require(volume < 2**31, f"{name} volume exceeds the signed 32-bit array limit")
    for minimum, size in zip(offsets, (sx, sy, sz)):
        require(minimum + size - 1 < 2**31, f"{name} maximum offset overflows a signed integer")

    palette = grid["palette"]
    biome_palette = grid["biome_palette"]
    require(type(palette) is list and len(palette) > 0
            and all(type(item) is str and bool(item.strip()) for item in palette),
            f"{name}.palette must contain nonblank BlockData strings")
    require(type(biome_palette) is list and len(biome_palette) > 0
            and all(type(item) is str and BIOME_PATTERN.fullmatch(item) for item in biome_palette),
            f"{name}.biome_palette must contain namespaced biome keys")
    for key, palette_size in (("indices", len(palette)), ("biome_indices", len(biome_palette))):
        indices = grid[key]
        require(type(indices) is list and len(indices) == volume,
                f"{name}.{key} must have exactly {volume} entries")
        for position, index in enumerate(indices):
            require(is_int(index) and 0 <= index < palette_size,
                    f"{name}.{key}[{position}] is outside its palette")


def index_at(grid: dict[str, Any], dx: int, dy: int, dz: int) -> int:
    sy, sz, sx = grid["shape_yzx"]
    mx, my, mz = grid["min_offset_xyz"]
    rx, ry, rz = dx - mx, dy - my, dz - mz
    require(0 <= rx < sx and 0 <= ry < sy and 0 <= rz < sz,
            f"Offset {dx},{dy},{dz} is outside the grid")
    return (ry * sz + rz) * sx + rx


def block_at(grid: dict[str, Any], dx: int, dy: int, dz: int) -> str:
    return grid["palette"][grid["indices"][index_at(grid, dx, dy, dz)]]


def biome_at(grid: dict[str, Any], dx: int, dy: int, dz: int) -> str:
    return grid["biome_palette"][grid["biome_indices"][index_at(grid, dx, dy, dz)]]


def terrain_hash(grid: dict[str, Any]) -> str:
    """SHA-256 equivalent of SnapshotIO.terrainHash; grid must already be validated."""
    sy, sz, sx = grid["shape_yzx"]
    digest = hashlib.sha256(HASH_DOMAIN)
    digest.update(struct.pack(">6i", sx, sy, sz, *grid["min_offset_xyz"]))
    encoded = [entry.encode("utf-8") for entry in grid["palette"]]
    packed_lengths = [struct.pack(">i", len(entry)) for entry in encoded]
    for index in grid["indices"]:
        digest.update(packed_lengths[index])
        digest.update(encoded[index])
    return digest.hexdigest()


def _verify_declared_hash(snapshot: dict[str, Any]) -> None:
    metadata = snapshot["metadata"]
    if "terrain_sha256" in metadata:
        declared = metadata["terrain_sha256"]
        grid = snapshot["context"] if snapshot["context"] is not None else snapshot["core"]
        require(type(declared) is str and declared == terrain_hash(grid),
                "metadata.terrain_sha256 does not match decoded context (or core if context is absent)")


def validate_snapshot(snapshot: Any, expected_id: str | None = None) -> None:
    require(type(snapshot) is dict, "Snapshot must be an object")
    require(set(snapshot) == SNAPSHOT_KEYS, f"Snapshot must contain exactly {sorted(SNAPSHOT_KEYS)}")
    require(type(snapshot["schema_version"]) is int and snapshot["schema_version"] == 1,
            "Unsupported schema_version; expected 1")
    identifier = snapshot["id"]
    require(type(identifier) is str and bool(ID_PATTERN.fullmatch(identifier)), "Invalid snapshot id")
    require(expected_id is None or identifier == expected_id, "Snapshot id does not match its filename")
    metadata = snapshot["metadata"]
    require(type(metadata) is dict, "metadata must be an object")
    _validate_json_value(metadata)
    seed = metadata.get("seed")
    require(type(seed) is str and bool(SEED_PATTERN.fullmatch(seed)),
            "metadata.seed must be a decimal string, never a JSON number")
    require(-(2**63) <= int(seed) < 2**63, "metadata.seed must fit a signed 64-bit integer")
    origin = metadata.get("source_origin_xyz")
    require(type(origin) is list and len(origin) == 3 and all(is_int(v) for v in origin),
            "metadata.source_origin_xyz must contain exactly three signed 32-bit integers")
    for key in ("entities_omitted", "block_entity_nbt_omitted", "scheduled_ticks_omitted"):
        require(metadata.get(key) is True, f"metadata.{key} must be true")
    require(metadata.get("fidelity") == "blockdata_only", "metadata.fidelity must be blockdata_only")

    core, context = snapshot["core"], snapshot["context"]
    validate_grid(core, "core")
    require(core["shape_yzx"] == [11, 25, 25] and core["min_offset_xyz"] == [-12, -5, -12],
            "core must have shape_yzx [11,25,25] and min_offset_xyz [-12,-5,-12]")
    if context is None:
        _verify_declared_hash(snapshot)
        return
    validate_grid(context, "context")
    csy, csz, csx = context["shape_yzx"]
    cmx, cmy, cmz = context["min_offset_xyz"]
    require(cmx <= -12 and cmy <= -5 and cmz <= -12
            and cmx + csx >= 13 and cmy + csy >= 6 and cmz + csz >= 13,
            "context must contain the complete core")
    for dy in range(-5, 6):
        for dz in range(-12, 13):
            for dx in range(-12, 13):
                require(block_at(core, dx, dy, dz) == block_at(context, dx, dy, dz),
                        f"core/context block mismatch at offset {dx},{dy},{dz}")
                require(biome_at(core, dx, dy, dz) == biome_at(context, dx, dy, dz),
                        f"core/context biome mismatch at offset {dx},{dy},{dz}")
    _verify_declared_hash(snapshot)


def read_snapshot(path: Path) -> dict[str, Any]:
    require(path.name.endswith(".json.gz"), "Snapshot filename must end in .json.gz")
    expected_id = path.name[: -len(".json.gz")]
    require(bool(ID_PATTERN.fullmatch(expected_id)), "Invalid snapshot filename")
    require(path.is_file() and not path.is_symlink(), "Snapshot must be a regular file, not a symlink")
    with gzip.open(path, "rb") as stream:
        raw = stream.read(MAX_JSON_BYTES + 1)
    require(len(raw) <= MAX_JSON_BYTES, "Snapshot JSON exceeds the 64 MiB limit")
    snapshot = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    validate_snapshot(snapshot, expected_id)
    return snapshot


def summarize(snapshot: dict[str, Any]) -> dict[str, Any]:
    metadata = snapshot["metadata"]
    grids: dict[str, Any] = {}
    for name in ("core", "context"):
        grid = snapshot[name]
        if grid is None:
            grids[name] = None
            continue
        grids[name] = {
            "shape_yzx": grid["shape_yzx"],
            "min_offset_xyz": grid["min_offset_xyz"],
            "volume": math.prod(grid["shape_yzx"]),
            "block_palette_size": len(grid["palette"]),
            "biome_palette_size": len(grid["biome_palette"]),
            "center_block": block_at(grid, 0, 0, 0),
            "center_biome": biome_at(grid, 0, 0, 0),
            "terrain_hash_sha256": terrain_hash(grid),
        }
    return {
        "structurally_valid": True,
        "id": snapshot["id"],
        "schema_version": snapshot["schema_version"],
        "seed": metadata["seed"],
        "source_origin_xyz": metadata["source_origin_xyz"],
        "fidelity": metadata["fidelity"],
        "entities_omitted": metadata["entities_omitted"],
        "block_entity_nbt_omitted": metadata["block_entity_nbt_omitted"],
        "scheduled_ticks_omitted": metadata["scheduled_ticks_omitted"],
        "core_matches_context": True if snapshot["context"] is not None else None,
        "declared_terrain_hash_verified": True if "terrain_sha256" in metadata else None,
        **grids,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("snapshot", type=Path, help="A sample_*.json.gz file")
    parser.add_argument("--json", action="store_true", help="Print the validation summary as JSON")
    args = parser.parse_args(argv)
    try:
        summary = summarize(read_snapshot(args.snapshot))
    except (OSError, EOFError, UnicodeError, ValueError, RecursionError, zlib.error) as exc:
        print(f"INVALID: {exc}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False))
    else:
        print(f"VALID terrain snapshot: {summary['id']}")
        print(f"Seed: {summary['seed']}")
        print(f"Source origin xyz: {summary['source_origin_xyz']}")
        print(f"Fidelity: {summary['fidelity']}; entities, block-entity NBT, and scheduled ticks are omitted")
        for name in ("core", "context"):
            grid = summary[name]
            if grid is None:
                print(f"{name}: absent")
                continue
            print(f"{name}: shape_yzx={grid['shape_yzx']}; offsets_xyz={grid['min_offset_xyz']}; "
                  f"{grid['volume']:,} cells; block/biome palettes="
                  f"{grid['block_palette_size']}/{grid['biome_palette_size']}")
            print(f"  center: {grid['center_block']}; biome: {grid['center_biome']}")
            print(f"  SHA-256: {grid['terrain_hash_sha256']}")
        if summary["context"] is None:
            print("Integrity: gzip and schema validated; no context was supplied for crop comparison.")
        else:
            print("Integrity: gzip and schema validated; core/context decoded BlockData and biome labels agree.")
        print("Physics equivalence is not established by this structural check.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
