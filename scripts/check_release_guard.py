"""Vérifie les garde-fous release avec un environnement sans secrets de signature."""
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ENV = {key: value for key, value in os.environ.items() if not key.startswith("SIGNING_")}
LAUNCH = ["cmd", "/c", str(ROOT / "gradlew.bat")] if os.name == "nt" else ["bash", str(ROOT / "gradlew")]


def gradle(*args):
    return subprocess.run(LAUNCH + list(args) + ["--max-workers=2", "--console=plain"],
                          cwd=ROOT, env=ENV, text=True, capture_output=True, timeout=300)


def main():
    result = gradle(":app:verifyReleaseSigning")
    output = result.stdout + result.stderr
    if result.returncode == 0 or "Release signing is not configured" not in output:
        raise RuntimeError("Release must fail safely when owner signing is absent.")
    print("PASS: credential-free release signing fails safely")
    for task in (":app:assembleRelease", ":app:bundleRelease"):
        result = gradle(task, "--dry-run")
        if result.returncode != 0 or ":app:verifyReleaseSigning SKIPPED" not in result.stdout:
            raise RuntimeError("Release packaging graph is missing its signing gate: " + task)
        print("PASS: signing gate in packaging graph " + task)


if __name__ == "__main__":
    main()
