#!/usr/bin/env python3
"""Exercise real Java snapshot I/O and validate its files with Python's standard library.

Requires JDK 25 and the Gson JAR used by the project, but no Minecraft server.
Example:
    python3 tools/test_snapshot_io.py --jdk /path/to/jdk-25 --gson /path/to/gson-2.14.0.jar
"""

from __future__ import annotations

import argparse
import copy
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zlib

import inspect_terrain as inspector


PROJECT = Path(__file__).resolve().parents[1]
JDK: Path | None = None
GSON: Path | None = None


JAVA_HARNESS = r'''
import io.github.terrainlab.SnapshotIO;
import io.github.terrainlab.TerrainSnapshot;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class SnapshotHarness {
    interface Checked { void run() throws Exception; }
    static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
    static void rejects(Class<? extends Throwable> type, Checked operation) throws Exception {
        try { operation.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Wrong failure: " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    static Map<String,Object> metadata() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("seed", "9223372036854775807");
        result.put("source_origin_xyz", new ArrayList<>(List.of(123, 65, -456)));
        result.put("entities_omitted", true);
        result.put("block_entity_nbt_omitted", true);
        result.put("scheduled_ticks_omitted", true);
        result.put("fidelity", "blockdata_only");
        result.put("note", "森林 \"quotes\", newline\nbackslash\\ emoji 🌲 <tag>");
        result.put("optional", null);
        result.put("large_integer", Long.MAX_VALUE);
        return result;
    }
    static String block(int x, int y, int z) {
        if (y < 0) return "minecraft:stone";
        if (x == 4 && y == 0 && z == 4) return "minecraft:oak_log[axis=x]";
        if (x == 3 && y == 0 && z == 3) return "minecraft:water[level=3]";
        return "minecraft:air";
    }
    static TerrainSnapshot.Grid grid(int sx, int sy, int sz, int mx, int my, int mz, boolean reverse) {
        List<String> blocks = new ArrayList<>(List.of("minecraft:air", "minecraft:stone",
                "minecraft:oak_log[axis=x]", "minecraft:water[level=3]"));
        List<String> biomes = new ArrayList<>(List.of("minecraft:plains", "minecraft:forest"));
        if (reverse) { Collections.reverse(blocks); Collections.reverse(biomes); }
        int[] cells = new int[sx*sy*sz];
        int[] labels = new int[cells.length];
        int i = 0;
        for (int y=my; y<my+sy; y++) {
            for (int z=mz; z<mz+sz; z++) {
                for (int x=mx; x<mx+sx; x++) {
                    cells[i] = blocks.indexOf(block(x,y,z));
                    labels[i++] = biomes.indexOf(x < 0 ? "minecraft:forest" : "minecraft:plains");
                }
            }
        }
        TerrainSnapshot.Grid result = new TerrainSnapshot.Grid(sx,sy,sz,mx,my,mz,blocks,cells,biomes,labels);
        // Mutating the inputs must not alter the snapshot.
        cells[0] = -1;
        labels[0] = -1;
        blocks.clear();
        biomes.clear();
        result.validate();
        return result;
    }
    static TerrainSnapshot.Grid core(boolean reverse) { return grid(25,11,25,-12,-5,-12,reverse); }
    static TerrainSnapshot.Grid context() { return grid(41,25,41,-20,-12,-20,true); }
    static TerrainSnapshot sample(String id) {
        return new TerrainSnapshot(1,id,metadata(),core(false),context());
    }
    public static void main(String[] args) throws Exception {
        SnapshotIO io = new SnapshotIO(Path.of(args[1]));
        if (args[0].equals("load")) {
            TerrainSnapshot loaded = io.load(args[2]);
            System.out.println(SnapshotIO.terrainHash(loaded.core()));
            return;
        }
        Map<String,Object> originalMetadata = metadata();
        TerrainSnapshot.Grid initialContext = context();
        originalMetadata.put("terrain_sha256",SnapshotIO.terrainHash(initialContext));
        TerrainSnapshot snapshot = new TerrainSnapshot(1,"sample_roundtrip",originalMetadata,core(false),initialContext);
        ((List<Integer>) originalMetadata.get("source_origin_xyz")).set(0,999);
        originalMetadata.put("seed", "0");
        check(snapshot.metadata().get("seed").equals("9223372036854775807"), "metadata not detached");
        check(((List<?>) snapshot.metadata().get("source_origin_xyz")).get(0).equals(123), "nested metadata not detached");
        rejects(UnsupportedOperationException.class, () -> snapshot.metadata().put("x",1));
        rejects(UnsupportedOperationException.class,
                () -> ((List<Object>) snapshot.metadata().get("source_origin_xyz")).set(0,1));
        int[] detached = snapshot.core().blocks(); detached[0] = -1;
        int[] detachedBiomes = snapshot.core().biomes(); detachedBiomes[0] = -1;
        snapshot.validate();
        check(snapshot.core().volume() == 6875, "core volume");
        check(snapshot.context().volume() == 42025, "context volume");
        check(snapshot.core().indexAt(-12,-5,-12) == 0, "first index");
        check(snapshot.core().indexAt(12,5,12) == 6874, "last index");
        rejects(IndexOutOfBoundsException.class, () -> snapshot.core().indexAt(13,0,0));
        check(SnapshotIO.terrainHash(snapshot.core()).equals(SnapshotIO.terrainHash(core(true))), "palette order affects hash");
        Path saved = io.save(snapshot);
        byte[] before = Files.readAllBytes(saved);
        rejects(FileAlreadyExistsException.class, () -> io.save(snapshot));
        check(Arrays.equals(before, Files.readAllBytes(saved)), "overwrite changed bytes");
        TerrainSnapshot loaded = io.load(snapshot.id());
        check(loaded.metadata().get("seed").equals(snapshot.metadata().get("seed")), "seed lost precision");
        check(loaded.metadata().get("large_integer").equals(Long.MAX_VALUE), "metadata long lost precision");
        check(loaded.metadata().get("note").equals(snapshot.metadata().get("note")), "string escaping roundtrip");
        check(loaded.metadata().containsKey("optional") && loaded.metadata().get("optional") == null, "null metadata omitted");
        check(loaded.core().blockAt(4,0,4).equals("minecraft:oak_log[axis=x]"), "BlockData state changed");

        int[] mismatched = snapshot.core().blocks();
        mismatched[snapshot.core().indexAt(0,0,0)] = snapshot.core().palette().indexOf("minecraft:stone");
        TerrainSnapshot.Grid wrongCore = new TerrainSnapshot.Grid(25,11,25,-12,-5,-12,
                snapshot.core().palette(),mismatched,snapshot.core().biomePalette(),snapshot.core().biomes());
        TerrainSnapshot bad = new TerrainSnapshot(1,"sample_invalid",metadata(),wrongCore,snapshot.context());
        rejects(IllegalArgumentException.class, () -> io.save(bad));
        check(!Files.exists(io.root().resolve("sample_invalid.json.gz")), "invalid save published output");
        int[] negative = snapshot.core().blocks(); negative[0] = -1;
        rejects(IllegalArgumentException.class, () -> new TerrainSnapshot.Grid(25,11,25,-12,-5,-12,
                snapshot.core().palette(),negative,snapshot.core().biomePalette(),snapshot.core().biomes()).validate());
        Map<String,Object> nonfinite = metadata(); nonfinite.put("value",Double.NaN);
        rejects(IllegalArgumentException.class, () -> new TerrainSnapshot(1,"sample_nan",nonfinite,core(false),null));
        Map<String,Object> numericSeed = metadata(); numericSeed.put("seed",Long.MAX_VALUE);
        rejects(IllegalArgumentException.class, () -> new TerrainSnapshot(1,"sample_seed",numericSeed,core(false),null).validate());
        Map<String,Object> cyclic = metadata(); cyclic.put("self",cyclic);
        rejects(IllegalArgumentException.class, () -> new TerrainSnapshot(1,"sample_cycle",cyclic,core(false),null));
        rejects(IllegalArgumentException.class, () -> io.load("../escape"));

        io.save(new TerrainSnapshot(1,"sample_core_only",metadata(),core(false),null));
        check(io.load("sample_core_only").context() == null, "null context roundtrip");
        io.save(new TerrainSnapshot(1,"sample_same_context",metadata(),core(false),core(true)));
        io.load("sample_same_context").validate();

        // Two concurrent writers must publish exactly one complete file and preserve the winner.
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> writer = () -> {
                gate.await();
                try { io.save(sample("sample_race")); return true; }
                catch (FileAlreadyExistsException expected) { return false; }
            };
            Future<Boolean> a = pool.submit(writer), b = pool.submit(writer);
            gate.countDown();
            check(a.get() != b.get(), "both concurrent saves succeeded or both failed");
        }
        io.load("sample_race").validate();
        Files.writeString(io.root().resolve("unrecognized.json.gz"),"ignored");
        Files.createDirectory(io.root().resolve("sample_directory.json.gz"));
        check(io.listIds().equals(List.of("sample_core_only","sample_race","sample_roundtrip","sample_same_context")),
                "listIds should include only safely named regular files in sorted order");
        try (var listing = Files.list(io.root())) {
            check(listing.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "temporary files leaked");
        }
        System.out.println("core_hash=" + SnapshotIO.terrainHash(snapshot.core()));
        System.out.println("context_hash=" + SnapshotIO.terrainHash(snapshot.context()));
        System.out.println("java_checks=passed");
    }
}
'''


class SnapshotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory(prefix="terrain-snapshot-test-")
        cls.work = Path(cls.temporary.name)
        cls.classes = cls.work / "classes"
        cls.classes.mkdir()
        cls.samples = cls.work / "samples"
        harness = cls.work / "SnapshotHarness.java"
        harness.write_text(JAVA_HARNESS, encoding="utf-8")
        javac = str(JDK / "bin" / "javac") if JDK else shutil.which("javac")
        cls.java = str(JDK / "bin" / "java") if JDK else shutil.which("java")
        if not javac or not cls.java:
            raise RuntimeError("JDK not found; pass --jdk /path/to/jdk-25")
        if GSON is None or not GSON.is_file():
            raise RuntimeError("Gson JAR not found; pass --gson /path/to/gson-2.14.0.jar")
        cls.classpath = os.pathsep.join((str(cls.classes), str(GSON)))
        sources = PROJECT / "src/main/java/io/github/terrainlab"
        compiled = subprocess.run(
            [javac, "--release", "25", "-encoding", "UTF-8", "-cp", str(GSON), "-d", str(cls.classes),
             str(sources / "TerrainSnapshot.java"), str(sources / "SnapshotIO.java"), str(harness)],
            capture_output=True, text=True,
        )
        if compiled.returncode:
            raise AssertionError("Java compilation failed:\n" + compiled.stdout + compiled.stderr)
        made = cls.run_java("make", cls.samples)
        if made.returncode:
            raise AssertionError("Java data/atomicity checks failed:\n" + made.stdout + made.stderr)
        cls.java_output = dict(line.split("=", 1) for line in made.stdout.splitlines() if "=" in line)
        cls.original = inspector.read_snapshot(cls.samples / "sample_roundtrip.json.gz")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    @classmethod
    def run_java(cls, *args: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run([cls.java, "-cp", cls.classpath, "SnapshotHarness", *(str(v) for v in args)],
                              capture_output=True, text=True)

    def mutated_file(self, suffix: str, change) -> Path:
        snapshot = copy.deepcopy(self.original)
        identifier = "sample_mutated_" + suffix
        snapshot["id"] = identifier
        change(snapshot)
        target = self.work / f"{identifier}.json.gz"
        with gzip.open(target, "wt", encoding="utf-8") as stream:
            json.dump(snapshot, stream, ensure_ascii=False, allow_nan=True)
        return target

    def test_cross_language_roundtrip_and_hash(self) -> None:
        data = self.original
        self.assertEqual(data["metadata"]["seed"], "9223372036854775807")
        self.assertEqual(data["metadata"]["large_integer"], 2**63 - 1)
        self.assertEqual(data["metadata"]["source_origin_xyz"], [123, 65, -456])
        self.assertEqual(data["metadata"]["note"], '森林 "quotes", newline\nbackslash\\ emoji 🌲 <tag>')
        self.assertIn("optional", data["metadata"])
        self.assertIsNone(data["metadata"]["optional"])
        self.assertEqual(inspector.terrain_hash(data["core"]), self.java_output["core_hash"])
        self.assertEqual(inspector.terrain_hash(data["context"]), self.java_output["context_hash"])
        self.assertEqual(inspector.biome_at(data["core"], 0, 0, 0), "minecraft:plains")
        self.assertEqual(inspector.block_at(data["core"], 4, 0, 4), "minecraft:oak_log[axis=x]")

    def test_index_bijection_and_palette_independence(self) -> None:
        core = self.original["core"]
        indices = {inspector.index_at(core, x, y, z)
                   for y in range(-5, 6) for z in range(-12, 13) for x in range(-12, 13)}
        self.assertEqual(indices, set(range(6875)))
        reordered = copy.deepcopy(core)
        n = len(core["palette"])
        reordered["palette"].reverse()
        reordered["indices"] = [n - 1 - index for index in core["indices"]]
        inspector.validate_grid(reordered, "reordered")
        self.assertEqual(inspector.terrain_hash(core), inspector.terrain_hash(reordered))

    def test_java_rejections_defensive_copies_and_atomic_publication(self) -> None:
        self.assertEqual(self.java_output["java_checks"], "passed")
        self.assertFalse(list(self.samples.glob("*.tmp")))
        for identifier in ("sample_roundtrip", "sample_core_only", "sample_same_context", "sample_race"):
            inspector.read_snapshot(self.samples / f"{identifier}.json.gz")
        self.assertFalse((self.samples / "sample_invalid.json.gz").exists())

    def test_optional_and_same_shape_context(self) -> None:
        core_only = inspector.read_snapshot(self.samples / "sample_core_only.json.gz")
        self.assertIsNone(core_only["context"])
        same = inspector.read_snapshot(self.samples / "sample_same_context.json.gz")
        self.assertNotEqual(same["core"]["palette"], same["context"]["palette"])
        self.assertEqual(inspector.terrain_hash(same["core"]), inspector.terrain_hash(same["context"]))

    def test_malformed_gzip_rejected_by_java_and_python(self) -> None:
        original = (self.samples / "sample_roundtrip.json.gz").read_bytes()
        for suffix, contents in (("truncated", original[:-8]), ("crc", original[:-1] + bytes([original[-1] ^ 0xFF]))):
            with self.subTest(kind=suffix):
                target = self.work / f"sample_{suffix}.json.gz"
                target.write_bytes(contents)
                with self.assertRaises((OSError, EOFError, ValueError, zlib.error)):
                    inspector.read_snapshot(target)
                loaded = self.run_java("load", self.work, "sample_" + suffix)
                self.assertNotEqual(loaded.returncode, 0)

    def test_bad_schema_rejected_by_java_and_python(self) -> None:
        mutations = {
            "negative": lambda s: s["core"]["indices"].__setitem__(0, -1),
            "boolean_index": lambda s: s["core"]["indices"].__setitem__(0, True),
            "short": lambda s: s["context"]["indices"].pop(),
            "shape": lambda s: s["core"].__setitem__("shape_yzx", [25, 11, 25]),
            "crop_block": lambda s: s["core"]["indices"].__setitem__(inspector.index_at(s["core"], 0, 0, 0), 1),
            "crop_biome": lambda s: s["core"]["biome_indices"].__setitem__(inspector.index_at(s["core"], 0, 0, 0), 1),
            "number_seed": lambda s: s["metadata"].__setitem__("seed", 123),
            "origin_float": lambda s: s["metadata"]["source_origin_xyz"].__setitem__(0, 123.0),
            "nan": lambda s: s["metadata"].__setitem__("bad", float("nan")),
            "flag": lambda s: s["metadata"].__setitem__("entities_omitted", False),
            "blank": lambda s: s["core"]["palette"].__setitem__(0, ""),
            "version": lambda s: s.__setitem__("schema_version", 2),
            "missing_context": lambda s: s.pop("context"),
            "hash": lambda s: s["metadata"].__setitem__("terrain_sha256", "0" * 64),
        }
        for name, mutation in mutations.items():
            with self.subTest(kind=name):
                target = self.mutated_file(name, mutation)
                with self.assertRaises((ValueError, OSError)):
                    inspector.read_snapshot(target)
                loaded = self.run_java("load", target.parent, target.name[:-len(".json.gz")])
                self.assertNotEqual(loaded.returncode, 0, loaded.stdout)

    def test_duplicate_keys_and_filename_id_mismatch(self) -> None:
        target = self.work / "sample_duplicate.json.gz"
        data = copy.deepcopy(self.original)
        data["id"] = "sample_duplicate"
        raw = json.dumps(data, separators=(",", ":"))
        raw = raw.replace('"schema_version":1', '"schema_version":1,"schema_version":1', 1)
        with gzip.open(target, "wt", encoding="utf-8") as stream:
            stream.write(raw)
        with self.assertRaises(ValueError):
            inspector.read_snapshot(target)
        self.assertNotEqual(self.run_java("load", self.work, "sample_duplicate").returncode, 0)
        mismatch = self.work / "sample_wrong_name.json.gz"
        shutil.copyfile(self.samples / "sample_roundtrip.json.gz", mismatch)
        with self.assertRaises(ValueError):
            inspector.read_snapshot(mismatch)
        self.assertNotEqual(self.run_java("load", self.work, "sample_wrong_name").returncode, 0)

    def test_cli(self) -> None:
        output = subprocess.run([sys.executable, str(PROJECT / "tools/inspect_terrain.py"),
                                 str(self.samples / "sample_roundtrip.json.gz"), "--json"],
                                capture_output=True, text=True)
        self.assertEqual(output.returncode, 0, output.stderr)
        summary = json.loads(output.stdout)
        self.assertTrue(summary["structurally_valid"])
        self.assertEqual(summary["core"]["volume"], 6875)
        self.assertEqual(summary["context"]["volume"], 42025)
        self.assertEqual(summary["core"]["terrain_hash_sha256"], self.java_output["core_hash"])


def main() -> None:
    global JDK, GSON
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jdk", type=Path, default=Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else None)
    parser.add_argument("--gson", type=Path)
    args = parser.parse_args()
    JDK = args.jdk
    candidates = [
        args.gson,
        PROJECT.parent / ".build-tools/m2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar",
        Path.home() / ".m2/repository/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar",
    ]
    GSON = next((path for path in candidates if path is not None and path.is_file()), None)
    unittest.main(argv=[sys.argv[0]], verbosity=2)


if __name__ == "__main__":
    main()
