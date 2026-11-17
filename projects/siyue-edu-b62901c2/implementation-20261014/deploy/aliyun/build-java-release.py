#!/usr/bin/env python3
"""Package a verified Java artifact; never connect to or activate a server."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def build(jar, source, output, release_id, expected_sha256):
    jar, source, output = Path(jar), Path(source), Path(output)
    if not all(p.is_absolute() for p in (jar, source, output)):
        raise ValueError("All paths must be absolute")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", release_id):
        raise ValueError("Invalid release ID")
    if not re.fullmatch(r"[a-f0-9]{64}", expected_sha256):
        raise ValueError("Expected JAR SHA256 is required")
    if jar.is_symlink() or not jar.is_file() or not source.is_dir() or not output.is_dir():
        raise ValueError("Regular JAR, frozen source directory and output directory required")
    source, output = source.resolve(), output.resolve()
    if output == source or source in output.parents:
        raise ValueError("Output must be outside frozen source")
    destination = output / release_id
    if destination.exists() or destination.is_symlink():
        raise ValueError("Release already exists")
    data = jar.read_bytes()
    if digest(data) != expected_sha256:
        raise ValueError("JAR checksum differs from verified artifact")
    migrations = source / "src/main/resources/db/migration"
    expected = {}
    for file in sorted(migrations.glob("*.sql")):
        if file.is_symlink() or file.resolve().parent != migrations.resolve():
            raise ValueError("Migration symlinks are forbidden")
        expected[file.name] = digest(file.read_bytes())
    if not expected:
        raise ValueError("Frozen migration source is missing")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate JAR entries")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        if "Main-Class: org.springframework.boot.loader.launch.JarLauncher" not in manifest or "Start-Class: cn.siyue.core.SiyueApplication" not in manifest:
            raise ValueError("Expected executable Siyue Spring Boot JAR")
        prefix = "BOOT-INF/classes/db/migration/"
        embedded = {name[len(prefix):]: digest(archive.read(name)) for name in names
                    if name.startswith(prefix) and name.endswith(".sql")}
        if embedded != expected:
            raise ValueError("Packaged migrations differ from frozen source")
    metadata = {"schema": "siyue.java-release/v1", "releaseId": release_id,
                "jarSha256": expected_sha256, "migrations": expected,
                "activationPerformed": False,
                "acceptance": "Artifact identity only; production migration and browser acceptance required"}
    temporary = Path(tempfile.mkdtemp(prefix=".java-package-", dir=output))
    try:
        os.chmod(temporary, 0o700)
        (temporary / "siyue-core.jar").write_bytes(data)
        (temporary / "release-manifest.json").write_text(json.dumps(metadata, indent=2) + "\n")
        checksums = []
        for name in ("siyue-core.jar", "release-manifest.json"):
            os.chmod(temporary / name, 0o600)
            checksums.append(digest((temporary / name).read_bytes()) + "  " + name)
        (temporary / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
        os.chmod(temporary / "SHA256SUMS", 0o600)
        # mkdir reserves destination exclusively; never overwrite an existing release.
        destination.mkdir(mode=0o700)
        for file in temporary.iterdir():
            shutil.move(str(file), destination / file.name)
    finally:
        shutil.rmtree(temporary)
    return {"releaseDirectory": str(destination), "jarSha256": expected_sha256,
            "migrationCount": len(expected), "activationPerformed": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("jar", "source", "out", "release-id", "expected-sha256"):
        parser.add_argument("--" + option, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.jar, args.source, args.out, args.release_id, args.expected_sha256)))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, str(error) + "\n")
