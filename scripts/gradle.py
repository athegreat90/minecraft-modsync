#!/usr/bin/env python3
"""Pinned, checksum-verified Gradle bootstrap (not the official wrapper JAR)."""
import hashlib, os, pathlib, subprocess, sys, tempfile, urllib.request, zipfile
VERSION = "9.9.1"
SHA256 = "d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228"
root = pathlib.Path(__file__).resolve().parents[1]
cache = root / ".gradle" / "bootstrap"
gradle = cache / ("gradle-" + VERSION) / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")
if not gradle.exists():
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=cache) as temp:
        archive = pathlib.Path(temp) / "gradle.zip"
        urllib.request.urlretrieve("https://services.gradle.org/distributions/gradle-" + VERSION + "-bin.zip", archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
            raise SystemExit("Gradle distribution checksum mismatch")
        with zipfile.ZipFile(archive) as source:
            source.extractall(cache)
    gradle.chmod(0o755)
args = [str(gradle), "--no-daemon", *sys.argv[1:]]
raise SystemExit(subprocess.call(args, cwd=root))
