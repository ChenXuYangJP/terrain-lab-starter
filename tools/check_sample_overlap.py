#!/usr/bin/env python3
"""Find TerrainLab sampling centers whose core boxes overlap, without third-party packages.

Usage:
    python3 tools/check_sample_overlap.py /path/to/samplingseed.yml
    python3 tools/check_sample_overlap.py /path/to/samplingseed.yml --json
    python3 tools/check_sample_overlap.py /path/to/samplingseed.yml -moveout

Two samples overlap when they share a world seed and a dimension and their 25 x 25 x 11
(x,z,y) core boxes share at least one block, i.e. |dx| <= 24, |dz| <= 24 and |dy| <= 10.

-moveout keeps the first sample of every overlapping pair (in file order) in samplingseed.yml
and moves the others to samplingseed_overlaped.yml next to it, appending to that file if it
exists. The plugin regenerates samplingseed.yml from the job checkpoints at server start and
after every saved sample, which brings moved entries back; sample files and job checkpoints
are never touched.

Exit status: 0 no overlaps (or -moveout succeeded), 1 overlaps found, 2 unreadable or
malformed file or failed write.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Any


OVERLAP_FILE = "samplingseed_overlaped.yml"
SEED_PATTERN = re.compile(r"-?(0|[1-9][0-9]*)\Z")
SEED_LINE = re.compile(r"  (\".*\"):\Z")
FIELD_LINE = re.compile(r"(    - |      )(sample|job|dimension|biome|center): (.+)\Z")
FIELDS = ("sample", "job", "dimension", "biome", "center")
OVERLAP_HEADER = """\
# TerrainLab sampling centers moved out of samplingseed.yml by
# tools/check_sample_overlap.py -moveout, grouped by world seed.
# Each entry's core box overlaps a sample that was kept in samplingseed.yml under the
# same seed and dimension. The plugin never reads or writes this file.
#
# center is the block position [x, y, z] of the sample center in that seed's
# dimension (metadata.source_origin_xyz). biome is the requested terrain type the
# center was accepted for.

"""


class FormatError(ValueError):
    """The file is readable but does not have the layout SeedFiles writes."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise FormatError(message)


@dataclass(frozen=True)
class Entry:
    seed: str
    sample: str
    job: str
    dimension: str
    biome: str
    center: tuple[int, int, int]
    line: int

    def summary(self) -> dict[str, Any]:
        return {"sample": self.sample, "job": self.job, "biome": self.biome,
                "center_xyz": list(self.center), "line": self.line}


@dataclass
class CenterFile:
    header: str
    groups: dict[str, list[Entry]]
    stamp: tuple[int, int]

    def entries(self) -> list[Entry]:
        return [entry for entries in self.groups.values() for entry in entries]


def _value(raw: str, number: int) -> Any:
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise FormatError(f"line {number}: {exc.msg}: {raw}") from None


def _entry(seed: str, fields: dict[str, Any], lines: dict[str, int]) -> Entry:
    number = lines["sample"]
    missing = [key for key in FIELDS if key not in fields]
    require(not missing, f"line {number}: sample entry is missing {', '.join(missing)}")
    for key in FIELDS[:-1]:
        require(isinstance(fields[key], str), f"line {lines[key]}: {key} must be a string")
    center = fields["center"]
    # bool is a subclass of int in Python, but is not a coordinate.
    require(isinstance(center, list) and len(center) == 3 and all(type(v) is int for v in center),
            f"line {lines['center']}: center must be [x, y, z] integers")
    return Entry(seed, fields["sample"], fields["job"], fields["dimension"], fields["biome"],
                 (center[0], center[1], center[2]), number)


def read_centers(path: Path) -> CenterFile:
    """Parses the fixed layout of SeedFiles.renderSamplingCenters; every value is a JSON literal."""
    info = path.stat()
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    index = 0
    while index < len(lines) and (not lines[index].strip() or lines[index].lstrip().startswith("#")):
        index += 1
    header = "".join(lines[:index])
    require(index < len(lines), "missing world_seeds")
    top = lines[index].rstrip("\r\n")
    groups: dict[str, list[Entry]] = {}
    seed: str | None = None
    fields: dict[str, Any] | None = None
    field_lines: dict[str, int] = {}
    require(top in ("world_seeds:", "world_seeds: {}"), f"line {index + 1}: expected world_seeds:, got {top}")
    for number, raw_line in enumerate(lines[index + 1:], start=index + 2):
        line = raw_line.rstrip("\r\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        require(top == "world_seeds:", f"line {number}: content after world_seeds: {{}}")
        if match := SEED_LINE.match(line):
            if fields is not None:
                groups[seed].append(_entry(seed, fields, field_lines))
                fields = None
            seed = _value(match.group(1), number)
            require(isinstance(seed, str) and SEED_PATTERN.match(seed) is not None,
                    f"line {number}: seed must be a decimal string")
            require(seed not in groups, f"line {number}: seed {seed} appears twice")
            groups[seed] = []
            continue
        match = FIELD_LINE.match(line)
        require(match is not None and seed is not None, f"line {number}: unexpected line: {line}")
        indent, key, raw = match.groups()
        if indent == "    - ":
            require(key == "sample", f"line {number}: an entry must start with sample:")
            if fields is not None:
                groups[seed].append(_entry(seed, fields, field_lines))
            fields, field_lines = {}, {}
        else:
            require(fields is not None and key != "sample", f"line {number}: {key} outside a sample entry")
            require(key not in fields, f"line {number}: duplicate {key}")
        fields[key] = _value(raw, number)
        field_lines[key] = number
    if fields is not None:
        groups[seed].append(_entry(seed, fields, field_lines))
    return CenterFile(header, groups, (info.st_mtime_ns, info.st_size))


def overlaps(a: Entry, b: Entry, reach: tuple[int, int, int]) -> bool:
    return all(abs(p - q) <= r for p, q, r in zip(a.center, b.center, reach))


def find_pairs(entries: list[Entry], reach: tuple[int, int, int]) -> tuple[list[tuple[Entry, Entry]], int]:
    """Overlapping pairs ordered (earlier, later) by file line, and the number of seed/dimension groups."""
    worlds: dict[tuple[str, str], list[Entry]] = {}
    for entry in entries:
        worlds.setdefault((entry.seed, entry.dimension), []).append(entry)
    pairs = []
    for world in worlds.values():
        ordered = sorted(world, key=lambda entry: entry.center[0])
        for i, a in enumerate(ordered):
            for b in ordered[i + 1:]:
                if b.center[0] - a.center[0] > reach[0]:
                    break
                if overlaps(a, b, reach):
                    pairs.append((a, b) if a.line < b.line else (b, a))
    pairs.sort(key=lambda pair: (pair[0].line, pair[1].line))
    return pairs, len(worlds)


def split_overlaps(groups: dict[str, list[Entry]], pairs: list[tuple[Entry, Entry]]):
    """Walks entries in file order and moves each one that overlaps an earlier entry that was kept."""
    earlier: dict[Entry, list[Entry]] = {}
    for first, second in pairs:
        earlier.setdefault(second, []).append(first)
    kept: dict[str, list[Entry]] = {}
    moved: dict[str, list[Entry]] = {}
    moved_set: set[Entry] = set()
    for seed, entries in groups.items():
        for entry in entries:
            if any(other not in moved_set for other in earlier.get(entry, ())):
                moved_set.add(entry)
                moved.setdefault(seed, []).append(entry)
            else:
                kept.setdefault(seed, []).append(entry)
    return kept, moved


def quote(value: str) -> str:
    # Matches SeedFiles.quote: a JSON string literal is also a valid YAML double-quoted scalar.
    return json.dumps(value, ensure_ascii=False)


def render(header: str, groups: dict[str, list[Entry]]) -> str:
    out = [header]
    if not any(groups.values()):
        out.append("world_seeds: {}\n")
        return "".join(out)
    out.append("world_seeds:\n")
    for seed, entries in groups.items():
        if not entries:
            continue
        out.append(f"  {quote(seed)}:\n")
        for entry in entries:
            x, y, z = entry.center
            out.append(f"    - sample: {quote(entry.sample)}\n"
                       f"      job: {quote(entry.job)}\n"
                       f"      dimension: {quote(entry.dimension)}\n"
                       f"      biome: {quote(entry.biome)}\n"
                       f"      center: [{x}, {y}, {z}]\n")
    return "".join(out)


def write_atomic(path: Path, text: str) -> None:
    if path.exists():
        mode = path.stat().st_mode & 0o7777
    else:
        umask = os.umask(0)
        os.umask(umask)
        mode = 0o666 & ~umask
    descriptor, temp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        os.chmod(temp, mode)
        os.replace(temp, path)
    except BaseException:
        Path(temp).unlink(missing_ok=True)
        raise


def move_entries(path: Path, centers: CenterFile, kept: dict[str, list[Entry]],
                 moved: dict[str, list[Entry]], target_name: str, header: str) -> dict[str, Any]:
    """Appends moved entries to target_name next to path, skipping known samples, then rewrites path."""
    target = path.with_name(target_name)
    require(target.resolve() != path.resolve(), f"{path} is already {target_name}")
    merged = read_centers(target).groups if target.exists() else {}
    known = {entry.sample for entries in merged.values() for entry in entries}
    added = 0
    for seed, entries in moved.items():
        for entry in entries:
            if entry.sample not in known:
                known.add(entry.sample)
                merged.setdefault(seed, []).append(entry)
                added += 1
    info = path.stat()
    require((info.st_mtime_ns, info.st_size) == centers.stamp, f"{path} changed while it was checked; run again")
    # The target file goes first, so an interrupted run never drops an entry from both files.
    write_atomic(target, render(header, merged))
    write_atomic(path, render(centers.header, kept))
    return {"target_file": str(target), "moved": sum(map(len, moved.values())), "added": added,
            "kept": sum(map(len, kept.values()))}


def move_out(path: Path, centers: CenterFile, pairs: list[tuple[Entry, Entry]]) -> dict[str, Any]:
    kept, moved = split_overlaps(centers.groups, pairs)
    result = move_entries(path, centers, kept, moved, OVERLAP_FILE, OVERLAP_HEADER)
    return {"overlap_file": result.pop("target_file"), **result}


def odd_size(text: str) -> int:
    value = int(text)
    if value < 1 or value % 2 != 1:
        raise argparse.ArgumentTypeError("must be a positive odd number")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("path", type=Path, help="A samplingseed.yml file")
    parser.add_argument("--size-x", type=odd_size, default=25, help="Core box size along x (default 25)")
    parser.add_argument("--size-z", type=odd_size, default=25, help="Core box size along z (default 25)")
    parser.add_argument("--size-y", type=odd_size, default=11, help="Core box size along y (default 11)")
    parser.add_argument("--json", action="store_true", help="Print the overlapping pairs as JSON")
    parser.add_argument("-moveout", "--moveout", action="store_true",
                        help=f"Keep the first sample of each overlapping pair and move the rest to {OVERLAP_FILE}")
    args = parser.parse_args(argv)
    reach = (args.size_x - 1, args.size_y - 1, args.size_z - 1)
    try:
        centers = read_centers(args.path)
        entries = centers.entries()
        pairs, worlds = find_pairs(entries, reach)
        result = move_out(args.path, centers, pairs) if args.moveout and pairs else None
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps({
            "path": str(args.path), "core_size_xzy": [args.size_x, args.size_z, args.size_y],
            "entries": len(entries), "seed_dimension_groups": worlds,
            "pairs": [{"seed": a.seed, "dimension": a.dimension,
                       "delta_xyz": [q - p for p, q in zip(a.center, b.center)],
                       "first": a.summary(), "second": b.summary()} for a, b in pairs],
            "moveout": result,
        }, ensure_ascii=False, indent=2))
    else:
        for a, b in pairs:
            dx, dy, dz = (q - p for p, q in zip(a.center, b.center))
            print(f"seed {a.seed} / {a.dimension}: delta xyz ({dx:+d}, {dy:+d}, {dz:+d})")
            for label, entry in (("first ", a), ("second", b)):
                print(f"  {label}: {entry.sample}  {entry.job}  {entry.biome}  {list(entry.center)}  (line {entry.line})")
        print(f"Checked {len(entries)} sample entries in {worlds} seed/dimension groups against a "
              f"{args.size_x} x {args.size_z} x {args.size_y} (x,z,y) core box.")
        if not pairs:
            print("No overlapping sampling positions.")
        else:
            print(f"Found {len(pairs)} overlapping pairs.")
        if result is not None:
            print(f"Moved {result['moved']} sample entries to {result['overlap_file']} "
                  f"({result['added']} new there); kept {result['kept']} in {args.path}.")
            print("Note: the plugin regenerates samplingseed.yml from jobs/ at server start and after "
                  "every saved sample, which brings moved entries back.")
    if result is not None or not pairs:
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
