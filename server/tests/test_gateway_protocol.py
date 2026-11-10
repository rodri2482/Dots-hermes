"""Trames réelles sur une socket aiohttp, avec seulement l'interface Hermes simulée."""
import base64
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import AsyncMock

import test_ws_guard as harness


@unittest.skipUnless(harness.HAVE_AIOHTTP, "aiohttp absent")
class GatewayProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="claydot-protocol-")
        self.home = pathlib.Path(self.temp.name)
        self.mod = harness._load_adapter(self.home)
        sys.modules["gateway.platforms.event"].MessageEvent = types.SimpleNamespace
        # adapter.py a déjà importé MessageEvent : remplacer aussi sa référence au stub Hermes.
        self.mod.MessageEvent = types.SimpleNamespace
        self.adapter = self.mod.DotsAdapter(sys.modules["gateway.config"].PlatformConfig({"token": harness.TOKEN}))
        self.adapter.handle_message = AsyncMock()
        self.adapter.build_source = lambda **values: types.SimpleNamespace(**values)
        self.cached = []

        def cache(data, **_):
            self.cached.append(data)
            return types.SimpleNamespace(path=self.home / "cache", kind="document", media_type="text/plain")

        sys.modules["gateway.platforms.base"].cache_media_bytes = cache
        app = harness.web.Application()
        app.router.add_get(self.mod.WS_PATH, self.adapter._handle_ws)
        self.client = harness.TestClient(harness.TestServer(app))
        await self.client.start_server()
        self.ws = await self.client.ws_connect(self.mod.WS_PATH, headers={"Authorization": f"Bearer {harness.TOKEN}"})

    async def asyncTearDown(self):
        await self.ws.close()
        await self.client.close()
        self.adapter._history._db.close()
        self.adapter._usage._db.close()
        self.temp.cleanup()

    async def test_unsupported_voice_is_silently_dropped(self):
        await self.ws.send_json({"type": "voice", "client_id": "unsupported", "mime": "audio/unsupported", "audio": "aGk="})
        # La réponse à hello prouve que la trame précédente a été entièrement traitée, sans temporisation.
        await self.ws.send_json({"type": "hello"})
        reply = await self.ws.receive_json(timeout=2)
        self.assertEqual(reply["type"], "welcome")
        self.assertEqual(reply["history"], [])
        self.assertEqual(list(self.adapter._voice_dir.iterdir()), [])
        self.adapter.handle_message.assert_not_called()

    async def assert_bad_upload(self, kind, payload):
        await self.ws.send_json({"type": kind, "client_id": f"bad-{kind}", **payload})
        # Le cas limite transfère ~28 Mio sur la vraie socket ; tolérer les CI/émulateurs concurrents.
        reply = await self.ws.receive_json(timeout=30)
        self.assertEqual(reply, {"type": "error", "code": f"bad_{kind}", "client_id": f"bad-{kind}"})
        self.assertEqual(self.adapter._history.seq, 0)
        self.assertEqual(list(self.adapter._voice_dir.iterdir()), [])
        self.assertEqual(list(self.adapter._files_dir.iterdir()), [])
        self.assertEqual(self.cached, [])
        self.adapter.handle_message.assert_not_called()

    async def test_oversized_voice_is_rejected_before_storage(self):
        await self.assert_bad_upload("voice", {"mime": "audio/webm", "audio": base64.b64encode(b"x" * (self.mod.MAX_VOICE_BYTES + 1)).decode()})

    async def test_oversized_file_is_rejected_before_caching(self):
        await self.assert_bad_upload("file", {"name": "large.txt", "data": base64.b64encode(b"x" * (self.mod.MAX_FILE_BYTES + 1)).decode()})

    async def test_invalid_voice_base64_is_rejected(self):
        await self.assert_bad_upload("voice", {"mime": "audio/webm", "audio": "not base64!"})

    async def test_invalid_voice_duration_is_rejected_without_orphaning_audio(self):
        await self.assert_bad_upload("voice", {"mime": "audio/webm", "audio": "aGk=", "duration_ms": "invalid"})

    async def test_invalid_voice_waveform_is_rejected_without_orphaning_audio(self):
        await self.assert_bad_upload("voice", {"mime": "audio/webm", "audio": "aGk=", "waveform": ["invalid"]})

    async def test_nonfinite_voice_waveform_is_rejected(self):
        await self.assert_bad_upload("voice", {"mime": "audio/webm", "audio": "aGk=", "waveform": [float("nan")]})

    async def test_nonobject_json_does_not_close_the_connection(self):
        await self.ws.send_json(["not", "a", "frame"])
        reply = await self.ws.receive_json(timeout=2)
        self.assertEqual(reply, {"type": "error", "code": "bad_frame"})
        await self.ws.send_json({"type": "hello"})
        self.assertEqual((await self.ws.receive_json(timeout=2))["type"], "welcome")

    async def test_invalid_hello_does_not_close_the_connection(self):
        registered = []
        self.adapter._push_sender.register = registered.append
        await self.ws.send_json({"type": "hello", "last_seq": "invalid", "foreground": False, "lang": "fr", "push_token": "fixture"})
        self.assertEqual(await self.ws.receive_json(timeout=2), {"type": "error", "code": "bad_frame"})
        self.assertEqual(self.adapter._lang, "en")
        self.assertTrue(next(iter(self.adapter._clients.values()))["foreground"])
        self.assertEqual(registered, [])
        await self.ws.send_json({"type": "hello"})
        self.assertEqual((await self.ws.receive_json(timeout=2))["type"], "welcome")

    async def test_oversized_hello_sequence_is_rejected_before_mutating_session(self):
        await self.ws.send_json({"type": "hello", "last_seq": 2 ** 63, "lang": "fr"})
        self.assertEqual(await self.ws.receive_json(timeout=2), {"type": "error", "code": "bad_frame"})
        self.assertEqual(self.adapter._lang, "en")

    async def test_persisted_client_id_prevents_duplicate_processing_after_memory_cache_expires(self):
        frame = {"type": "file", "client_id": "persisted", "name": "note.txt", "data": "aGk="}
        await self.ws.send_json(frame)
        first = await self.ws.receive_json(timeout=2)
        self.adapter._acks.clear()
        self.adapter._dedup = type(self.adapter._dedup)()
        await self.ws.send_json(frame)
        self.assertEqual(await self.ws.receive_json(timeout=2), first)
        self.adapter.handle_message.assert_awaited_once()
        self.assertEqual(self.adapter._history.seq, 1)

    async def test_invalid_file_base64_is_rejected(self):
        await self.assert_bad_upload("file", {"name": "bad.txt", "data": "not base64!"})

    async def assert_duplicate(self, kind, payload):
        frame = {"type": kind, "client_id": f"same-{kind}", **payload}
        await self.ws.send_json(frame)
        first = await self.ws.receive_json(timeout=2)
        self.assertEqual(first["type"], "ack")
        self.assertEqual(first["client_id"], frame["client_id"])
        self.assertEqual(self.adapter._history.get(first["id"])["client_id"], frame["client_id"])
        await self.ws.send_json(frame)
        self.assertEqual(await self.ws.receive_json(timeout=2), first)
        self.assertEqual(self.adapter._history.seq, 1)
        self.adapter.handle_message.assert_awaited_once()

    async def test_duplicate_text_is_reacknowledged_and_handled_once(self):
        await self.assert_duplicate("message", {"text": "Hello"})

    async def test_duplicate_file_is_reacknowledged_and_cached_once(self):
        await self.assert_duplicate("file", {"name": "note.txt", "mime": "text/plain", "data": "aGk="})
        self.assertEqual(self.cached, [b"hi"])
        self.assertEqual([p.read_bytes() for p in self.adapter._files_dir.iterdir()], [b"hi"])

    async def test_duplicate_voice_is_reacknowledged_and_stored_once(self):
        await self.assert_duplicate("voice", {"mime": "audio/webm", "audio": "aGk=", "duration_ms": 500})
        self.assertEqual([p.read_bytes() for p in self.adapter._voice_dir.iterdir()], [b"hi"])

    async def assert_rejection_replayed(self, kind, payload):
        frame = {"type": kind, "client_id": f"rejected-{kind}", **payload}
        await self.ws.send_json(frame)
        first = await self.ws.receive_json(timeout=2)
        self.assertEqual(first, {"type": "error", "code": f"bad_{kind}", "client_id": frame["client_id"]})
        await self.ws.close()
        self.ws = await self.client.ws_connect(self.mod.WS_PATH, headers={"Authorization": f"Bearer {harness.TOKEN}"})
        await self.ws.send_json(frame)
        self.assertEqual(await self.ws.receive_json(timeout=2), first)
        self.assertEqual(self.adapter._history.seq, 0)
        self.assertEqual(list(self.adapter._voice_dir.iterdir()), [])
        self.assertEqual(list(self.adapter._files_dir.iterdir()), [])
        self.adapter.handle_message.assert_not_called()

    async def test_lost_file_rejection_is_replayed_after_reconnect(self):
        await self.assert_rejection_replayed("file", {"name": "bad.txt", "data": "invalid!"})

    async def test_lost_voice_rejection_is_replayed_after_reconnect(self):
        await self.assert_rejection_replayed("voice", {"mime": "audio/webm", "audio": "invalid!"})

    async def test_refused_origin_does_not_consume_the_authentication_budget(self):
        peer = {"Origin": "https://untrusted.invalid", "X-Forwarded-For": "198.51.100.9"}
        for _ in range(12):
            response = await self.client.get(self.mod.WS_PATH, headers=peer)
            self.assertEqual(response.status, 403)
            self.assertEqual((await response.json())["code"], "origin_not_allowed")
        # Même adresse, origine absente comme l'app Android : le bon jeton reste accepté.
        async with self.client.ws_connect(self.mod.WS_PATH, headers={
            "Authorization": f"Bearer {harness.TOKEN}", "X-Forwarded-For": "198.51.100.9",
        }) as ws:
            await ws.send_json({"type": "hello"})
            self.assertEqual((await ws.receive_json(timeout=2))["type"], "welcome")
