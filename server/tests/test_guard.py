"""Tests de guard.py (limite des jetons refusés). Sans Hermes : python -m unittest discover server/tests"""

import importlib.util
import pathlib
import unittest

_spec = importlib.util.spec_from_file_location(
    "dots_guard", pathlib.Path(__file__).resolve().parents[1] / "hermes-plugin-dots" / "guard.py")
guard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(guard)


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class ClientKeyTest(unittest.TestCase):
    def test_direct_connection_uses_remote_address(self):
        self.assertEqual(guard.client_key("203.0.113.7", {}), "203.0.113.7")

    def test_direct_connection_ignores_spoofed_forwarded_header(self):
        self.assertEqual(guard.client_key("203.0.113.7", {"X-Forwarded-For": "1.2.3.4"}), "203.0.113.7")

    def test_behind_proxy_takes_the_address_the_proxy_appended(self):
        headers = {"X-Forwarded-For": "6.6.6.6, 198.51.100.4"}  # the client forged the first one
        self.assertEqual(guard.client_key("127.0.0.1", headers), "198.51.100.4")
        self.assertEqual(guard.client_key("::1", {"X-Forwarded-For": "198.51.100.4"}), "198.51.100.4")

    def test_behind_proxy_falls_back_to_x_real_ip(self):
        self.assertEqual(guard.client_key("127.0.0.1", {"X-Real-IP": "198.51.100.9"}), "198.51.100.9")

    def test_local_connection_without_proxy_header_is_never_limited(self):
        self.assertIsNone(guard.client_key("127.0.0.1", {}))
        self.assertIsNone(guard.client_key("127.0.0.1", {"X-Forwarded-For": " , "}))


class AuthGuardTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.guard = guard.AuthGuard(max_failures=3, window=60, block=300, clock=self.clock)

    def fail(self, key, times):
        return [self.guard.failure(key) for _ in range(times)]

    def test_blocks_after_max_failures_only(self):
        self.assertEqual(self.fail("a", 2), [False, False])
        self.assertEqual(self.guard.retry_after("a"), 0)
        self.assertTrue(self.guard.failure("a"))
        self.assertEqual(self.guard.retry_after("a"), 300)

    def test_block_expires(self):
        self.fail("a", 3)
        self.clock.now += 299.5
        self.assertEqual(self.guard.retry_after("a"), 1)
        self.clock.now += 1
        self.assertEqual(self.guard.retry_after("a"), 0)
        # A fresh start after the block: one more failure does not block again.
        self.assertFalse(self.guard.failure("a"))

    def test_failures_outside_the_window_do_not_add_up(self):
        self.fail("a", 2)
        self.clock.now += 61
        self.assertEqual(self.fail("a", 2), [False, False])
        self.assertEqual(self.guard.retry_after("a"), 0)

    def test_success_forgets_failures(self):
        self.fail("a", 2)
        self.guard.success("a")
        self.assertEqual(self.fail("a", 2), [False, False])

    def test_addresses_are_independent(self):
        self.fail("attacker", 3)
        self.assertGreater(self.guard.retry_after("attacker"), 0)
        self.assertEqual(self.guard.retry_after("owner"), 0)

    def test_unidentified_clients_are_never_tracked(self):
        self.assertEqual(self.fail(None, 10), [False] * 10)
        self.assertEqual(self.guard.retry_after(None), 0)
        self.assertEqual(len(self.guard), 0)

    def test_memory_is_bounded(self):
        small = guard.AuthGuard(max_tracked=100, clock=self.clock)
        for i in range(1000):
            small.failure(f"10.0.{i // 256}.{i % 256}")
        self.assertEqual(len(small), 100)


if __name__ == "__main__":
    unittest.main()
