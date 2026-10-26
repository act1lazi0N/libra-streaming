"""Record tool version strings only; missing or failing tools invalidate the result."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit, urlunsplit


def version(command, label):
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError:
        raise ValueError(f"Missing or unavailable tool: {label}") from None
    if result.returncode != 0:
        raise ValueError(f"Version command failed: {label}")
    lines = (result.stdout + result.stderr).splitlines()
    for line in lines:
        line = line.strip()
        if "Picked up " in line:
            continue
        matches = re.match(r'^(openjdk|java) version "', line) if label == "java" else "version" in line
        if matches:
            return line
    raise ValueError(f"Missing version string: {label}")


def executable(name, environment=None, directory=None):
    if environment is not None and os.environ.get(environment, "").strip():
        path = os.environ[environment]
        if not Path(path).is_file():
            raise ValueError(f"Configured tool is not an existing file: {name}")
        return path
    filename = name + ".exe" if os.name == "nt" else name
    if name == "ffprobe":
        for entry in os.environ.get("PATH", "").split(os.pathsep):
            if entry.strip():
                candidate = Path(entry) / filename
                if candidate.is_file():
                    return str(candidate)
    if directory is not None:
        candidate = directory / filename
        if candidate.is_file():
            return str(candidate)
    path = shutil.which(name)
    if path is None:
        raise ValueError(f"Missing tool on PATH: {name}")
    return path


def record(root):
    output = root / "target/verification/toolchain.json"
    output.unlink(missing_ok=True)
    if "JAVA_HOME" in os.environ:
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not os.environ["JAVA_HOME"] or not java.is_file():
            raise ValueError("Configured tool is not an existing file: java")
        java = str(java)
    else:
        java = executable("java")
    ffprobe = os.path.abspath(executable("ffprobe", "LIBRA_FFPROBE_PATH"))
    ffmpeg = executable("ffmpeg", "LIBRA_FFMPEG_PATH", Path(ffprobe).parent)
    tools = {
        "java": [java, "-version"],
        "docker": [executable("docker"), "--version"],
        "dockerCompose": [executable("docker"), "compose", "version"],
        "ffmpeg": [ffmpeg, "-version"],
        "ffprobe": [ffprobe, "-version"],
    }
    versions = {name: version(command, name) for name, command in tools.items()}
    versions["python"] = sys.version.splitlines()[0]
    properties = {}
    for line in (root / ".mvn/wrapper/maven-wrapper.properties").read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() in ("wrapperVersion", "distributionUrl"):
            properties[key.strip()] = value.strip()
    for key in ("wrapperVersion", "distributionUrl"):
        if not properties.get(key):
            raise ValueError(f"Missing Maven wrapper property: {key}")
    distribution = urlsplit(properties["distributionUrl"])
    properties["distributionUrl"] = urlunsplit(distribution._replace(
        netloc=distribution.netloc.rsplit("@", 1)[-1], query="", fragment=""))
    versions.update(properties)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(versions, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    record(args.root)
    print("PASS: required toolchain versions recorded")


if __name__ == "__main__":
    main()
