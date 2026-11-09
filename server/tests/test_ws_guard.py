"""Le vrai DotsAdapter._handle_ws, modules de Hermes simulés : la limite des jetons refusés de bout en bout.

Demande aiohttp (pip install aiohttp) ; sans lui, le test est ignoré. Lancer depuis la racine du dépôt :
    python -m unittest discover -s server/tests -v
"""

import asyncio
import importlib.util
import pathlib
import sys
import tempfile
import types
import unittest

try:
    import aiohttp  # noqa: F401
    from aiohttp import WSServerHandshakeError, web
    from aiohttp.test_utils import TestClient, TestServer
    HAVE_AIOHTTP = True
except ImportError:
    HAVE_AIOHTTP = False

PLUGIN = pathlib.Path(__file__).resolve().parents[1] / "hermes-plugin-dots"
TOKEN = "a" * 48


def _install_hermes_stubs(home: pathlib.Path) -> None:
    """Le strict nécessaire des modules de Hermes importés par adapter.py."""

    def mod(name, **attrs):
        m = types.ModuleType(name)
        m.__dict__.update(attrs)
        sys.modules[name] = m

    class PlatformConfig:
        def __init__(self, extra=None):
            self.extra = extra or {}

    class BasePlatformAdapter:
        def __init__(self, config, platform):
            self.config, self.platform, self.name = config, platform, "dots"

    class _AnyAttr(type):
        def __getattr__(cls, name):
            return name

    class MessageType(metaclass=_AnyAttr):
        pass

    class MessageDeduplicator:
        def __init__(self, **_):
            self.seen = set()

        def is_duplicate(self, key):
            seen = key in self.seen
            self.seen.add(key)
            return seen

    mod("gateway")
    mod("gateway.platforms")
    mod("gateway.config", Platform=lambda s: s, PlatformConfig=PlatformConfig)
    mod("gateway.platforms._shared",
        extra_or_secret=lambda extra, key, env, default=None: extra.get(key, default),
        get_scoped_secret=lambda *a: "", seed_extra_from_env=lambda *a, **k: None, send_error=lambda *a, **k: None)
    mod("gateway.platforms.base", BasePlatformAdapter=BasePlatformAdapter, SendResult=object, cache_media_bytes=None)
    mod("gateway.platforms.event", MessageEvent=object, MessageType=MessageType)
    mod("gateway.platforms.helpers", MessageDeduplicator=MessageDeduplicator)
    mod("hermes_constants", get_hermes_home=lambda: home)


def _load_adapter():
    _install_hermes_stubs(pathlib.Path(tempfile.mkdtemp()))
    spec = importlib.util.spec_from_file_location(
        "dotsplugin_under_test", PLUGIN / "__init__.py", submodule_search_locations=[str(PLUGIN)])
    pkg = importlib.util.module_from_spec(spec)
    sys.modules["dotsplugin_under_test"] = pkg
    spec.loader.exec_module(pkg)
    return sys.modules["dotsplugin_under_test.adapter"]


@unittest.skipUnless(HAVE_AIOHTTP, "aiohttp absent")
class WebSocketGuardTest(unittest.TestCase):
    def setUp(self):
        self.adapter_mod = _load_adapter()

    def run_async(self, coro):
        return asyncio.run(coro)

    async def attempt(self, client, token=None, xff=None, web_token=None):
        """Statut de la poignée de main : 101 (accepté), 401, 429, ou 4401 (jeton web refusé)."""
        headers = {}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if xff:
            headers["X-Forwarded-For"] = xff
        protocols = ("dots.v1", f"dots.token.{web_token}") if web_token else ()
        try:
            ws = await client.ws_connect(self.adapter_mod.WS_PATH, headers=headers, protocols=protocols)
        except WSServerHandshakeError as e:
            return e.status
        msg = await ws.receive(timeout=2) if web_token else None
        await ws.close()
        if msg is not None and msg.type.name == "TEXT" and "unauthorized" in msg.data:
            return 4401
        return 101

    def test_rate_limiting_end_to_end(self):
        async def scenario():
            adapter = self.adapter_mod.DotsAdapter(sys.modules["gateway.config"].PlatformConfig({"token": TOKEN}))
            app = web.Application()
            app.router.add_get(self.adapter_mod.WS_PATH, adapter._handle_ws)
            async with TestClient(TestServer(app)) as client:
                owner, attacker = "198.51.100.1", "203.0.113.66"
                self.assertEqual(await self.attempt(client, TOKEN, xff=owner), 101)
                self.assertEqual([await self.attempt(client, "wrong", xff=attacker) for _ in range(10)], [401] * 10)
                self.assertEqual(await self.attempt(client, "wrong", xff=attacker), 429)
                self.assertEqual(await self.attempt(client, TOKEN, xff=attacker), 429, "blocked even with the right token")
                self.assertEqual(await self.attempt(client, TOKEN, xff=owner), 101, "other addresses unaffected")
                # Seule la dernière entrée (ajoutée par le proxy) compte ; les précédentes viennent du client.
                self.assertEqual(await self.attempt(client, TOKEN, xff=f"{attacker}, 198.51.100.200"), 101)
                # Connexion locale sans en-tête de proxy : jamais bloquée (on ne peut pas la distinguer du propriétaire).
                self.assertEqual([await self.attempt(client, "wrong") for _ in range(15)], [401] * 15)
                self.assertEqual(await self.attempt(client, TOKEN), 101)
                # Les jetons web refusés comptent aussi.
                browser = "192.0.2.9"
                self.assertEqual([await self.attempt(client, xff=browser, web_token="bogus") for _ in range(10)], [4401] * 10)
                self.assertEqual(await self.attempt(client, xff=browser, web_token="bogus"), 429)
                r = await client.get(self.adapter_mod.WS_PATH, headers={"X-Forwarded-For": attacker})
                self.assertEqual(r.status, 429)
                self.assertGreater(int(r.headers.get("Retry-After", "0")), 0)

        self.run_async(scenario())


if __name__ == "__main__":
    unittest.main()
