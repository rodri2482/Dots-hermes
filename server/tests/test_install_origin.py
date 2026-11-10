"""Vérifie les origines annoncées par l'installeur, sans installation Hermes réelle."""

import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
INSTALLER = ROOT / "server" / "install.sh"
UPSTREAM = "https://dots-hermes.vercel.app"


def find_bash():
    if os.name == "nt":
        for root in (os.environ.get("ProgramFiles", "C:/Program Files"),
                     os.environ.get("ProgramW6432", "C:/Program Files")):
            candidate = pathlib.Path(root) / "Git" / "bin" / "bash.exe"
            if candidate.is_file():
                return str(candidate)
    return shutil.which("bash")


BASH = find_bash()


@unittest.skipUnless(BASH, "bash absent")
class InstallerOriginTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="claydot-installer-")
        self.addCleanup(self.temp.cleanup)
        self.home = pathlib.Path(self.temp.name) / "fake-hermes"
        self.home.mkdir()
        self.env_file = self.home / ".env"
        # Ce jeton est un simple marqueur de test, jamais un identifiant réel.
        self.env_file.write_text(
            "DOTS_TOKEN=fixture-not-a-real-gateway-token\n"
            "DOTS_BIND=127.0.0.1:8787\n"
            "DOTS_ALLOWED_USERS=owner\n"
            "DOTS_HOME_CHANNEL=owner\n",
            encoding="utf-8", newline="\n")
        self.hermes = pathlib.Path(self.temp.name) / "hermes-stub"
        self.hermes.write_text(
            "#!/usr/bin/env bash\n"
            "if [ \"$1\" = --version ]; then printf 'Hermes 0.22.2\\n'; "
            "else printf 'Unexpected Hermes action\\n' >&2; exit 97; fi\n",
            encoding="utf-8", newline="\n")
        self.hermes.chmod(0o755)
        self.env = os.environ.copy()
        self.env.pop("CLAYDOT_WEB_ORIGIN", None)
        self.env["HERMES_HOME"] = self.home.as_posix()
        self.env["HERMES_BIN"] = self.hermes.as_posix()

    def run_installer(self, *arguments):
        before = {p.relative_to(self.home): p.read_bytes()
                  for p in self.home.rglob("*") if p.is_file()}
        result = subprocess.run(
            [BASH, INSTALLER.as_posix(), "--dry-run", "--no-restart", *arguments],
            env=self.env, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        after = {p.relative_to(self.home): p.read_bytes()
                 for p in self.home.rglob("*") if p.is_file()}
        self.assertEqual(before, after, "Le dry-run a modifié le faux profil")
        self.assertFalse((self.home / "plugins").exists())
        self.assertNotIn("fixture-not-a-real-gateway-token", result.stdout)
        return result.stdout

    def assert_origins(self, output, expected):
        allowed = next(line for line in output.splitlines()
                       if "app web autorisée depuis " in line)
        self.assertEqual(allowed.split("app web autorisée depuis ", 1)[1], expected)

    def test_default_keeps_upstream_fallback(self):
        output = self.run_installer()
        self.assert_origins(output, UPSTREAM)
        self.assertIn(f"App web  {UPSTREAM} (", output)

    def test_environment_selects_and_normalizes_the_default_origin(self):
        self.env["CLAYDOT_WEB_ORIGIN"] = " HTTPS://CLAYDOT.EXAMPLE/// "
        output = self.run_installer()
        self.assert_origins(output, "https://claydot.example")
        self.assertIn("App web  https://claydot.example (", output)
        self.assertNotIn(UPSTREAM, output)

    def test_cli_default_takes_precedence_over_environment(self):
        self.env["CLAYDOT_WEB_ORIGIN"] = "https://environment.example"
        output = self.run_installer("--default-web-origin", "https://cli.example/")
        self.assert_origins(output, "https://cli.example")
        self.assertIn("App web  https://cli.example (", output)
        self.assertNotIn("https://environment.example", output)

    def test_existing_and_additional_origins_are_preserved_and_deduplicated(self):
        with self.env_file.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(f"DOTS_WEB_ORIGINS={UPSTREAM},https://saved.example\n")
        output = self.run_installer(
            "--default-web-origin", "https://claydot.example",
            "--web-origin", "HTTPS://SAVED.EXAMPLE/",
            "--web-origin", "http://localhost:5173")
        self.assert_origins(
            output, f"https://claydot.example,{UPSTREAM},https://saved.example,http://localhost:5173")
        self.assertIn("App web  https://claydot.example (", output)

    def test_empty_environment_uses_the_fallback(self):
        self.env["CLAYDOT_WEB_ORIGIN"] = ""
        self.assert_origins(self.run_installer(), UPSTREAM)


if __name__ == "__main__":
    unittest.main()
