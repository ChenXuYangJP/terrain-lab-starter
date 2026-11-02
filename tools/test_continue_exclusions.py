#!/usr/bin/env python3
"""Test how the plugin reads samplingseed_unsupported_terrain.yml: -continue bucket counts
and the /terrain show -random pools.

Run `mvn package` first, then:
    python3 tools/test_continue_exclusions.py --jdk /path/to/jdk-25

Fixtures are written with the same renderer as tools/check_tnt_support.py -moveout and read
by the compiled SeedFiles.readSampleIds; JobState.existing is called with exclusions, and
SeedFiles.randomPool with indexed, listed and on-disk sample ids.
Dependencies are read from the local Maven repository; use --maven-repo when it is
elsewhere. Nothing is downloaded and Minecraft is not started.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import check_sample_overlap as seeds
import check_tnt_support as tnt


PROJECT = Path(__file__).resolve().parents[1]
JDK: Path | None = None
MAVEN_REPO = Path.home() / ".m2" / "repository"
CLASSES = PROJECT / "target" / "classes"

HARNESS = r'''
import io.github.terrainlab.JobState;
import io.github.terrainlab.SeedFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class ContinueExclusionsHarness {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("read")) {
            try {
                System.out.println("IDS=" + String.join(",", new TreeSet<>(SeedFiles.readSampleIds(Path.of(args[1])))));
            } catch (IllegalArgumentException rejected) {
                System.out.println("REJECTED=" + rejected.getMessage());
            }
            return;
        }
        if (args[0].equals("pool")) {
            // pool <good|dropped> <indexed,...> <unsupported,...> <files,...>
            System.out.println("POOL=" + String.join(",", SeedFiles.randomPool(ids(args[2]),
                    Set.copyOf(ids(args[3])), Set.copyOf(ids(args[4])), args[1].equals("dropped"))));
            return;
        }
        // count <excluded,...> <seed> <dimension> <biome> <id,seed,dimension,biome,x,y,z>...
        Set<String> excluded = Set.copyOf(ids(args[1]));
        List<JobState.SampleRef> samples = new ArrayList<>();
        for (int i = 5; i < args.length; i++) {
            String[] f = args[i].split(",");
            samples.add(new JobState.SampleRef(f[0], f[1], f[2], f[3],
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6])));
        }
        System.out.println("COUNT=" + JobState.existing(samples, excluded, args[2], args[3], args[4]));
    }

    private static List<String> ids(String csv) {
        return csv.isEmpty() ? List.of() : List.of(csv.split(","));
    }
}
'''


def entry(seed: str, sample: str) -> seeds.Entry:
    return seeds.Entry(seed, sample, "job_test", "overworld", "minecraft:plains", (1, 64, -2), 0)


class ContinueExclusionsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        for name in ("SeedFiles", "JobState"):
            if not (CLASSES / f"io/github/terrainlab/{name}.class").is_file():
                raise RuntimeError(f"Compiled {name}.class missing; run mvn package first.")
        java = str(JDK / "bin/java") if JDK else shutil.which("java")
        javac = str(JDK / "bin/javac") if JDK else shutil.which("javac")
        if not java or not javac:
            raise RuntimeError("JDK 25 is required; pass --jdk /path/to/jdk-25.")
        jars = sorted(MAVEN_REPO.rglob("*.jar"))
        if not any(path.name.startswith("paper-api-26.3.") for path in jars):
            raise RuntimeError("Paper 26.3 dependency not found; check --maven-repo.")
        # Put this project's pinned API first if the repository contains other versions.
        jars.sort(key=lambda path: (path.name != "paper-api-26.3.build.218-stable.jar", str(path)))
        cls.temporary = tempfile.TemporaryDirectory(prefix="terrain-continue-exclusions-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.work = Path(cls.temporary.name)
        harness = cls.work / "ContinueExclusionsHarness.java"
        harness.write_text(HARNESS, encoding="utf-8")
        classpath = os.pathsep.join([str(cls.work), str(CLASSES), *(str(path) for path in jars)])
        result = subprocess.run(
            [javac, "--release", "25", "-cp", classpath, "-d", str(cls.work), str(harness)],
            capture_output=True, text=True, timeout=60,
        )
        if result.returncode:
            raise AssertionError("Harness compilation failed:\n" + result.stdout + result.stderr)
        cls.java = java
        cls.classpath = classpath

    def run_harness(self, *args: str) -> str:
        result = subprocess.run([self.java, "-Xmx128m", "-cp", self.classpath, "ContinueExclusionsHarness", *args],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout.strip()

    def read(self, name: str, text: str | None) -> str:
        path = self.work / name
        if text is not None:
            path.write_text(text, encoding="utf-8")
        return self.run_harness("read", str(path))

    def test_missing_file_lists_nothing(self) -> None:
        self.assertEqual(self.read("missing.yml", None), "IDS=")

    def test_reads_ids_written_by_the_moveout_renderer(self) -> None:
        text = seeds.render(tnt.UNSUPPORTED_HEADER, {
            "3996955608640846241": [entry("3996955608640846241", "sample_b2"), entry("3996955608640846241", "sample_a1")],
            "-1041683042631961439": [entry("-1041683042631961439", "sample_c3")],
        })
        self.assertEqual(self.read("rendered.yml", text), "IDS=sample_a1,sample_b2,sample_c3")

    def test_empty_file_lists_nothing(self) -> None:
        self.assertEqual(self.read("empty.yml", seeds.render(tnt.UNSUPPORTED_HEADER, {})), "IDS=")

    def test_rejects_malformed_files(self) -> None:
        fixtures = {
            "bad_id.yml": seeds.render("", {"7": [entry("7", "Sample-X")]}),
            "broken.yml": "world_seeds:\n  \"7\": [\n",
            "no_map.yml": "seeds: []\n",
            "not_list.yml": "world_seeds:\n  \"7\":\n    sample: \"sample_a\"\n",
            "no_sample.yml": "world_seeds:\n  \"7\":\n    - job: \"job_x\"\n",
        }
        for name, text in fixtures.items():
            with self.subTest(name=name):
                self.assertTrue(self.read(name, text).startswith("REJECTED=" + name), name)

    def test_existing_skips_excluded_ids_and_counts_positions_once(self) -> None:
        samples = ["s1,7,overworld,minecraft:plains,0,64,0", "s2,7,overworld,minecraft:plains,100,64,0",
                   "s3,7,overworld,minecraft:plains,100,64,0", "s4,7,overworld,minecraft:desert,0,64,0",
                   "s5,8,overworld,minecraft:plains,0,64,0"]
        bucket = ("7", "overworld", "minecraft:plains")
        self.assertEqual(self.run_harness("count", "", *bucket, *samples), "COUNT=2")
        self.assertEqual(self.run_harness("count", "s2", *bucket, *samples), "COUNT=2")
        self.assertEqual(self.run_harness("count", "s2,s3", *bucket, *samples), "COUNT=1")
        self.assertEqual(self.run_harness("count", "s1,s2,s3,s4,s5", *bucket, *samples), "COUNT=0")

    def test_random_pools_split_on_the_list_and_need_a_sample_file(self) -> None:
        # s4 is listed but no longer indexed; s5 and s6 have no sample file.
        indexed, listed, files = "s3,s1,s2,s5", "s2,s4,s6", "s1,s2,s3,s4,s9"
        self.assertEqual(self.run_harness("pool", "good", indexed, listed, files), "POOL=s1,s3")
        self.assertEqual(self.run_harness("pool", "dropped", indexed, listed, files), "POOL=s2,s4")

    def test_random_pools_can_be_empty(self) -> None:
        self.assertEqual(self.run_harness("pool", "good", "", "", "s1"), "POOL=")
        self.assertEqual(self.run_harness("pool", "dropped", "s1", "", "s1"), "POOL=")
        self.assertEqual(self.run_harness("pool", "good", "s1", "s1", "s1"), "POOL=")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, help="JDK 25 directory (otherwise java/javac from PATH)")
    parser.add_argument("--maven-repo", type=Path, default=MAVEN_REPO,
                        help="Local Maven repository containing the project's existing dependencies")
    parser.add_argument("--classes", type=Path, default=CLASSES,
                        help="Compiled project classes; defaults to target/classes")
    args, unittest_args = parser.parse_known_args()
    JDK = args.jdk.resolve() if args.jdk else None
    MAVEN_REPO = args.maven_repo.resolve()
    CLASSES = args.classes.resolve()
    unittest.main(argv=[__file__, *unittest_args], verbosity=2)
