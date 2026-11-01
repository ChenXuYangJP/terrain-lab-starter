#!/usr/bin/env python3
"""Test check_tnt_support.py on synthetic cores and sample files; needs only Python 3.

    python3 tools/test_tnt_support.py
"""

from __future__ import annotations

import contextlib
import gzip
import io
import json
from pathlib import Path
import tempfile
import unittest

import check_sample_overlap as seeds
import check_tnt_support as tool


STONE, WATER, AIR = "minecraft:stone", "minecraft:water[level=0]", "minecraft:air"


def make_core(block) -> dict:
    """A 25 x 25 x 11 core whose cell at offset (dx, dy, dz) holds block(dx, dy, dz)."""
    palette: list[str] = []
    lookup: dict[str, int] = {}
    indices = []
    for dy in range(-5, 6):
        for dz in range(-12, 13):
            for dx in range(-12, 13):
                data = block(dx, dy, dz)
                if data not in lookup:
                    lookup[data] = len(palette)
                    palette.append(data)
                indices.append(lookup[data])
    return {"shape_yzx": [11, 25, 25], "min_offset_xyz": [-12, -5, -12], "axis_order": "y,z,x",
            "palette": palette, "indices": indices,
            "biome_palette": ["minecraft:plains"], "biome_indices": [0] * len(indices)}


def ground(below: str = STONE, center: dict[int, str] | None = None,
           cells: dict[tuple[int, int, int], str] | None = None):
    """below at dy <= -1, air above; center maps dy to the center column, cells overrides any offset."""
    def block(dx: int, dy: int, dz: int) -> str:
        if cells and (dx, dy, dz) in cells:
            return cells[(dx, dy, dz)]
        if center and dx == 0 and dz == 0 and dy in center:
            return center[dy]
        return below if dy <= -1 else AIR
    return block


def spots(block, radius: int = 8) -> int:
    return tool.villager_spots(make_core(block), tool.disc(radius))[0]


class TntLandingTest(unittest.TestCase):
    def test_water_and_lava_below_the_center_are_unsupported(self) -> None:
        self.assertEqual(tool.tnt_landing(make_core(ground(WATER))), (None, WATER, -1))
        self.assertEqual(tool.tnt_landing(make_core(ground("minecraft:lava[level=0]"))),
                         (None, "minecraft:lava[level=0]", -1))

    def test_falls_through_plants_and_one_snow_layer(self) -> None:
        core = make_core(ground(center={-1: "minecraft:short_grass", -2: "minecraft:grass_block"}))
        self.assertEqual(tool.tnt_landing(core), (-1, "minecraft:grass_block", -2))
        core = make_core(ground(center={-1: "minecraft:snow[layers=1]", -2: "minecraft:snow_block"}))
        self.assertEqual(tool.tnt_landing(core), (-1, "minecraft:snow_block", -2))
        core = make_core(ground(center={-1: "minecraft:snow[layers=2]"}))
        self.assertEqual(tool.tnt_landing(core), (0, "minecraft:snow[layers=2]", -1))

    def test_falls_through_short_grass_into_water(self) -> None:
        core = make_core(ground(center={-1: "minecraft:short_grass", -2: WATER}))
        self.assertEqual(tool.tnt_landing(core), (None, WATER, -2))

    def test_waterlogged_plant_is_liquid_and_waterlogged_slab_is_support(self) -> None:
        vein = "minecraft:sculk_vein[down=true,waterlogged=true]"
        self.assertEqual(tool.tnt_landing(make_core(ground(center={-1: vein}))), (None, vein, -1))
        slab = "minecraft:stone_slab[type=bottom,waterlogged=true]"
        self.assertEqual(tool.tnt_landing(make_core(ground(WATER, center={-1: slab}))), (0, slab, -1))

    def test_vines_down_to_the_bottom_of_the_core_give_no_support(self) -> None:
        vine = "minecraft:vine[east=true,north=false,south=false,up=false,west=false]"
        core = make_core(ground(AIR, center={dy: vine for dy in range(-5, 0)}))
        self.assertEqual(tool.tnt_landing(core), (None, None, None))


class VillagerSpotTest(unittest.TestCase):
    def test_flat_ground_has_a_spot_in_every_column_of_the_disc(self) -> None:
        count, nearest = tool.villager_spots(make_core(ground()), tool.disc(8))
        self.assertEqual(count, 196)
        self.assertEqual(abs(nearest[0]) + abs(nearest[2]), 1)
        self.assertEqual(nearest[1], 0)

    def test_water_plain_with_a_solid_center_has_no_spot(self) -> None:
        block = ground(WATER, center={dy: STONE for dy in range(-5, 0)})
        self.assertIsNotNone(tool.tnt_landing(make_core(block))[0])
        self.assertEqual(spots(block), 0)

    def test_ceiling_one_block_up_leaves_no_headroom(self) -> None:
        self.assertEqual(spots(lambda dx, dy, dz: AIR if dy == 0 else STONE), 0)

    def test_two_block_gap_under_a_ceiling_is_a_spot(self) -> None:
        self.assertEqual(spots(lambda dx, dy, dz: AIR if dy in (0, 1) else STONE), 196)

    def test_distance_is_euclidean(self) -> None:
        self.assertEqual(spots(ground(WATER, cells={(6, -1, 6): STONE})), 0)
        self.assertEqual(spots(ground(WATER, cells={(5, -1, 6): STONE})), 1)
        self.assertEqual(spots(ground(WATER, cells={(9, -1, 0): STONE})), 0)
        self.assertEqual(spots(ground(WATER, cells={(9, -1, 0): STONE}), radius=9), 1)

    def test_standing_block_must_be_safe_and_low(self) -> None:
        for data, expected in (("minecraft:stone_slab[type=bottom,waterlogged=true]", 1),
                               ("minecraft:magma_block", 0), ("minecraft:cactus[age=0]", 0),
                               ("minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]", 0),
                               ("minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]", 0),
                               ("minecraft:potted_spruce_sapling", 1)):
            with self.subTest(data=data):
                self.assertEqual(spots(ground(WATER, cells={(3, -1, 0): data})), expected)

    def test_body_cells_may_hold_plants_but_not_water_or_hazards(self) -> None:
        cells = {(3, -2, 0): STONE, (3, -1, 0): "minecraft:short_grass"}
        self.assertEqual(spots(ground(WATER, cells=cells)), 1)
        cells[(3, -1, 0)] = "minecraft:sweet_berry_bush[age=3]"
        self.assertEqual(spots(ground(WATER, cells=cells)), 0)
        cells[(3, -1, 0)] = "minecraft:seagrass"
        self.assertEqual(spots(ground(WATER, cells=cells)), 0)

    def test_standing_block_range_keeps_the_villager_inside_the_core(self) -> None:
        # Standing on dy 3 puts the head on dy 5, the top layer; dy 4 would leave the core.
        self.assertEqual(spots(lambda dx, dy, dz: STONE if dy <= 3 else AIR), 196)
        self.assertEqual(spots(lambda dx, dy, dz: STONE if dy <= 4 else AIR), 0)


class MoveOutTest(unittest.TestCase):
    TERRAIN = {
        "sample_good": ground(),
        "sample_water": ground(WATER),
        "sample_island": ground(WATER, center={dy: STONE for dy in range(-5, 0)}),
    }
    ENTRIES = (("101", "sample_good", (10, 64, 10)), ("101", "sample_water", (500, 62, 500)),
               ("-7", "sample_island", (0, 63, 0)), ("-7", "sample_missing", (900, 70, 900)))

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.path = root / "samplingseed.yml"
        (root / "samples").mkdir()
        groups: dict[str, list[seeds.Entry]] = {}
        for seed, sample, center in self.ENTRIES:
            groups.setdefault(seed, []).append(
                seeds.Entry(seed, sample, "job_test", "overworld", "minecraft:plains", center, 0))
            if sample in self.TERRAIN:
                snapshot = {"schema_version": 1, "id": sample, "context": None,
                            "core": make_core(self.TERRAIN[sample]),
                            "metadata": {"seed": seed, "dimension": "overworld", "source_origin_xyz": list(center),
                                         "entities_omitted": True, "block_entity_nbt_omitted": True,
                                         "scheduled_ticks_omitted": True, "fidelity": "blockdata_only"}}
                with gzip.open(root / "samples" / f"{sample}.json.gz", "wt", encoding="utf-8") as handle:
                    json.dump(snapshot, handle)
        self.original = seeds.render("# header\n\n", groups)
        self.path.write_text(self.original, encoding="utf-8")

    def tearDown(self) -> None:
        self.directory.cleanup()

    def run_tool(self, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            status = tool.main([str(self.path), *args])
        return status, out.getvalue()

    def samples(self, path: Path) -> list[str]:
        return [entry.sample for entry in seeds.read_centers(path).entries()]

    def test_report_only_leaves_the_file_alone(self) -> None:
        status, out = self.run_tool("--json")
        report = json.loads(out)
        self.assertEqual(status, 1)
        self.assertEqual([item["sample"] for item in report["flagged"]], ["sample_water", "sample_island"])
        self.assertEqual(report["flagged"][0]["tnt"]["block"], WATER)
        self.assertTrue(report["flagged"][1]["tnt"]["supported"])
        self.assertEqual(report["flagged"][1]["villager_spots"], 0)
        self.assertEqual([item["sample"] for item in report["unchecked"]], ["sample_missing"])
        self.assertEqual(self.path.read_text(encoding="utf-8"), self.original)

    def test_moveout_moves_flagged_and_keeps_unchecked_entries(self) -> None:
        status, _ = self.run_tool("-moveout")
        target = self.path.with_name(tool.UNSUPPORTED_FILE)
        self.assertEqual(status, 1)
        self.assertEqual(self.samples(self.path), ["sample_good", "sample_missing"])
        self.assertEqual(self.samples(target), ["sample_water", "sample_island"])
        self.assertTrue(self.path.read_text(encoding="utf-8").startswith("# header\n"))

        # The plugin regenerates samplingseed.yml; a second run must not duplicate moved entries.
        self.path.write_text(self.original, encoding="utf-8")
        self.run_tool("-moveout")
        self.assertEqual(self.samples(target), ["sample_water", "sample_island"])

    def test_clean_file_exits_zero(self) -> None:
        good = seeds.read_centers(self.path).groups["101"][:1]
        self.path.write_text(seeds.render("", {"101": good}), encoding="utf-8")
        self.assertEqual(self.run_tool()[0], 0)

    def test_mismatched_sample_file_is_unchecked(self) -> None:
        self.path.write_text(self.original.replace("[10, 64, 10]", "[11, 64, 10]"), encoding="utf-8")
        report = json.loads(self.run_tool("--json")[1])
        self.assertIn("sample_good", [item["sample"] for item in report["unchecked"]])


if __name__ == "__main__":
    unittest.main()
