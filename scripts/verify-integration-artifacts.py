#!/usr/bin/env python3
"""Verify downloaded Link libraries against an immutable integration source."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

MODULES = ("link-core", "link-transport", "link-client")
REVISION = re.compile(r"[0-9a-f]{40}")


def manifest_revision(path):
    with zipfile.ZipFile(path) as archive:
        raw = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    lines = []
    for line in raw.splitlines():
        if line.startswith(" ") and lines:
            lines[-1] += line[1:]
        else:
            lines.append(line)
    values = [line.split(": ", 1)[1] for line in lines if line.startswith("JLShell-Build-Revision: ")]
    if len(values) != 1:
        raise ValueError("missing or duplicate source provenance")
    return values[0]


def verify(repository, revision):
    if not REVISION.fullmatch(revision):
        raise ValueError("expected a full immutable source revision")
    version = "0.0.0-internal.g" + revision
    artifacts = []
    for module in MODULES:
        path = repository / "com/jlshell/link" / module / version / f"{module}-{version}.jar"
        if not path.is_file() or path.is_symlink() or manifest_revision(path) != revision:
            raise ValueError("downloaded library provenance does not match integration source")
        artifacts.append({"module": module, "size": path.stat().st_size,
                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return {"schemaVersion": 1, "linkSourceRevision": revision, "linkVersion": version,
            "libraries": artifacts, "scope": "CI library provenance; real platform/business acceptance is separate"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(args.repository, args.revision)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, UnicodeError, zipfile.BadZipFile):
        raise SystemExit("Link integration artifact verification failed; no raw artifact paths or contents are logged.") from None
    print("Immutable Link integration library provenance verified.")


if __name__ == "__main__":
    main()
