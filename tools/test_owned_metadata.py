#!/usr/bin/env python3
"""Test the compiled OwnedWorlds UUID reader using independently encoded NBT.

Run `mvn package` first, then:
    python3 tools/test_owned_metadata.py --jdk /path/to/jdk-25

Dependencies are read from the local Maven repository; use --maven-repo when it
is elsewhere. This script does not download anything or start Minecraft. Java
reflection invokes only the private, static, disk-metadata reader. All fixtures
and the small Java harness live in an automatically removed temporary directory.
"""

from __future__ import annotations

import argparse
import gzip
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import uuid


PROJECT = Path(__file__).resolve().parents[1]
EXPECTED = uuid.UUID("fedcba98-7654-3210-89ab-cdef01234567")
JDK: Path | None = None
MAVEN_REPO = Path.home() / ".m2" / "repository"
CLASSES = PROJECT / "target" / "classes"

HARNESS = r'''
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;

public final class OwnedMetadataHarness {
    public static void main(String[] args) throws Exception {
        Class<?> owner = Class.forName("io.github.terrainlab.OwnedWorlds");
        Method reader = owner.getDeclaredMethod("readDiskUuid", Path.class);
        reader.setAccessible(true);
        try {
            UUID actual = (UUID) reader.invoke(null, Path.of(args[0]));
            if (args[1].equals("REJECT")) {
                throw new AssertionError("Unsafe metadata was accepted: " + actual);
            }
            if (!actual.toString().equals(args[1])) {
                throw new AssertionError("UUID mismatch: " + actual + " != " + args[1]);
            }
            System.out.println("UUID=" + actual);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (args[1].equals("REJECT") && cause instanceof IOException) {
                System.out.println("REJECTED=" + cause.getClass().getName() + ": " + cause.getMessage());
                return;
            }
            throw new AssertionError("Unexpected reader failure", cause);
        }
    }
}
'''


def utf(value: str) -> bytes:
    # Fixture names/strings are ASCII, a common subset of Java NBT's UTF format.
    encoded = value.encode("ascii")
    return struct.pack(">H", len(encoded)) + encoded


def named(tag_type: int, name: str, payload: bytes) -> bytes:
    return bytes([tag_type]) + utf(name) + payload


def compound(*children: bytes) -> bytes:
    return b"".join(children) + b"\x00"


def uuid_tag(length: int = 4) -> bytes:
    parts = [(EXPECTED.int >> shift) & 0xFFFFFFFF for shift in (96, 64, 32, 0)]
    parts = (parts + [0] * max(0, length - 4))[:length]
    return named(11, "uuid", struct.pack(">i", length)
                 + b"".join(struct.pack(">I", part) for part in parts))


def metadata(*data_fields: bytes, extras: bool = False) -> bytes:
    fields = list(data_fields)
    if extras:
        # Every legal non-END NBT tag type appears, independently of the UUID.
        fields.extend([
            named(1, "byte", struct.pack(">b", -7)),
            named(2, "short", struct.pack(">h", -300)),
            named(3, "int", struct.pack(">i", 123456)),
            named(4, "long", struct.pack(">q", -9223372036854775807)),
            named(5, "float", struct.pack(">f", 1.25)),
            named(6, "double", struct.pack(">d", -0.125)),
            named(7, "bytes", struct.pack(">i", 3) + b"\x00\x7f\xff"),
            named(8, "string", utf("unrelated metadata")),
            named(9, "list", b"\x08" + struct.pack(">i", 2) + utf("one") + utf("two")),
            named(10, "nested", compound(named(8, "note", utf("preserved only by the fixture")))),
            named(11, "ints", struct.pack(">iii", 2, -1, 7)),
            named(12, "longs", struct.pack(">iqq", 2, -1, 9223372036854775807)),
        ])
    return named(10, "", compound(
        named(3, "DataVersion", struct.pack(">i", 9999)),
        named(10, "data", compound(*fields)),
    ))


class OwnedMetadataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        target = CLASSES / "io/github/terrainlab/OwnedWorlds.class"
        if not target.is_file():
            raise RuntimeError("Compiled OwnedWorlds.class missing; run mvn package first.")
        java = str(JDK / "bin/java") if JDK else shutil.which("java")
        javac = str(JDK / "bin/javac") if JDK else shutil.which("javac")
        if not java or not javac:
            raise RuntimeError("JDK 25 is required; pass --jdk /path/to/jdk-25.")
        jars = sorted(MAVEN_REPO.rglob("*.jar"))
        if not any(path.name.startswith("paper-api-26.3.") for path in jars):
            raise RuntimeError("Paper 26.3 dependency not found; check --maven-repo.")
        # Put this project's pinned API first if the repository contains other versions.
        jars.sort(key=lambda path: (path.name != "paper-api-26.3.build.218-stable.jar", str(path)))
        cls.temporary = tempfile.TemporaryDirectory(prefix="terrain-owned-metadata-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.work = Path(cls.temporary.name)
        harness = cls.work / "OwnedMetadataHarness.java"
        harness.write_text(HARNESS, encoding="utf-8")
        result = subprocess.run(
            [javac, "--release", "25", "-d", str(cls.work), str(harness)],
            capture_output=True, text=True, timeout=30,
        )
        if result.returncode:
            raise AssertionError("Harness compilation failed:\n" + result.stdout + result.stderr)
        cls.java = java
        cls.classpath = os.pathsep.join([str(cls.work), str(CLASSES), *(str(path) for path in jars)])

    def check_fixture(self, name: str, payload: bytes | None, expected: str = "REJECT") -> str:
        folder = self.work / name
        metadata_file = folder / "data/paper/metadata.dat"
        metadata_file.parent.mkdir(parents=True)
        if payload is not None:
            metadata_file.write_bytes(payload)
        result = subprocess.run(
            [self.java, "-Xmx128m", "-cp", self.classpath,
             "OwnedMetadataHarness", str(folder), expected],
            capture_output=True, text=True, timeout=15,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(result.stdout.startswith("REJECTED=" if expected == "REJECT" else "UUID="),
                        result.stdout + result.stderr)
        return result.stdout

    def test_valid_uncompressed_all_tag_types(self) -> None:
        self.check_fixture("valid_raw", metadata(uuid_tag(), extras=True), str(EXPECTED))

    def test_valid_gzip_all_tag_types(self) -> None:
        self.check_fixture("valid_gzip", gzip.compress(metadata(uuid_tag(), extras=True), mtime=0),
                           str(EXPECTED))

    def test_missing_file(self) -> None:
        self.check_fixture("missing_file", None)

    def test_missing_uuid(self) -> None:
        self.check_fixture("missing_uuid", metadata(extras=True))

    def test_wrong_uuid_lengths(self) -> None:
        for length in (0, 3, 5):
            with self.subTest(length=length):
                self.check_fixture(f"wrong_length_{length}", metadata(uuid_tag(length)))

    def test_wrong_uuid_type(self) -> None:
        self.check_fixture("uuid_string", metadata(named(8, "uuid", utf(str(EXPECTED)))))

    def test_bad_gzip(self) -> None:
        compressed = gzip.compress(metadata(uuid_tag()), mtime=0)
        self.check_fixture("gzip_truncated", compressed[:-5])
        damaged = bytearray(compressed)
        damaged[-8] ^= 0xFF  # Incorrect CRC must not silently supply a UUID.
        self.check_fixture("gzip_bad_crc", bytes(damaged))

    def test_oversized_array(self) -> None:
        self.check_fixture("huge_array", metadata(
            uuid_tag(), named(11, "oversized", struct.pack(">i", 65537))))

    def test_duplicate_uuid(self) -> None:
        self.check_fixture("duplicate_uuid", metadata(uuid_tag(), uuid_tag()))

    def test_truncated_nbt(self) -> None:
        self.check_fixture("truncated_nbt", metadata(uuid_tag())[:-2])

    def test_compressed_size_limit(self) -> None:
        self.check_fixture("large_file", b"\x00" * 1_048_577)

    def test_decoded_size_limit(self) -> None:
        self.check_fixture("decompression_limit", gzip.compress(b"\x00" * 1_048_577, mtime=0))

    def test_excessive_depth(self) -> None:
        nested = compound(named(8, "leaf", utf("value")))
        for _ in range(40):
            nested = compound(named(10, "next", nested))
        self.check_fixture("deep_nesting", metadata(uuid_tag(), named(10, "deep", nested)))


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
