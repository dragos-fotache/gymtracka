"""Stdlib regression tests for scripts/run-isolated-backend.py.

No Docker, backend, or network services are used: every test exercises path
ownership, port probing on loopback test sockets, or refusal ordering with
provisioning calls patched to fail loudly if attempted.

Run: python3 -m unittest discover -s scripts/tests -p 'test_*.py'
"""

import importlib.util
import json
import os
import signal
import socket
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parent.parent / "run-isolated-backend.py"


def load_script():
    spec = importlib.util.spec_from_file_location("run_isolated_backend", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SupervisorOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.module = load_script()
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)
        self.previous_sigterm = signal.getsignal(signal.SIGTERM)

    def tearDown(self):
        signal.signal(signal.SIGTERM, self.previous_sigterm)
        self.scratch.cleanup()

    def test_claim_refuses_existing_file_and_preserves_it(self):
        target = self.root / "config.json"
        target.write_text('{"mine": true}')
        with self.assertRaises(self.module.ConfigExistsError):
            self.module.claim_config_path(str(target))
        self.assertEqual('{"mine": true}', target.read_text())

    def test_claim_refuses_symlink_and_preserves_link_and_target(self):
        target = self.root / "real.json"
        target.write_text('{"mine": true}')
        link = self.root / "config.json"
        link.symlink_to(target)
        with self.assertRaises(self.module.ConfigExistsError):
            self.module.claim_config_path(str(link))
        self.assertTrue(link.is_symlink())
        self.assertEqual('{"mine": true}', target.read_text())

    def test_claim_refuses_directory(self):
        with self.assertRaises(self.module.ConfigExistsError):
            self.module.claim_config_path(str(self.root))

    def test_claim_accepts_missing_path(self):
        self.module.claim_config_path(str(self.root / "absent.json"))

    def test_publish_creates_owner_only_exclusive_file(self):
        path = str(self.root / "new.json")
        identity = self.module.publish_config(path, {"a": 1})
        self.assertEqual({"a": 1}, json.loads(Path(path).read_text()))
        mode = stat.S_IMODE(os.stat(path).st_mode)
        self.assertEqual(0o600, mode)
        with self.assertRaises(FileExistsError):
            self.module.publish_config(path, {"a": 2})
        self.assertEqual({"a": 1}, json.loads(Path(path).read_text()))
        self.assertTrue(self.module.unpublish_config(path, identity))
        self.assertFalse(os.path.lexists(path))

    def test_unpublish_leaves_foreign_file_alone(self):
        path = str(self.root / "foreign.json")
        Path(path).write_text('{"mine": true}')
        self.assertFalse(self.module.unpublish_config(path, ("dev", "ino")))
        self.assertFalse(self.module.unpublish_config(path, None))
        self.assertEqual('{"mine": true}', Path(path).read_text())

    def test_unpublish_leaves_symlink_alone(self):
        target = self.root / "real.json"
        target.write_text("{}")
        link = str(self.root / "config.json")
        os.symlink(target, link)
        self.assertFalse(self.module.unpublish_config(link, ("dev", "ino")))
        self.assertTrue(Path(link).is_symlink())

    def test_main_refuses_occupied_config_before_provisioning(self):
        occupied = self.root / "config.json"
        occupied.write_text('{"mine": true}')
        with mock.patch.object(self.module.subprocess, "run",
                               side_effect=AssertionError("provisioning attempted")):
            code = self.module.main([
                "--backend-root", str(self.root),
                "--config-path", str(occupied),
                "--port", "18081",
                "--no-build",
            ])
        self.assertEqual(2, code)
        self.assertEqual('{"mine": true}', occupied.read_text())

    def test_main_refuses_occupied_port_before_provisioning(self):
        holder = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            holder.bind(("127.0.0.1", 0))
            holder.listen(1)
            port = holder.getsockname()[1]
            with mock.patch.object(self.module.subprocess, "run",
                                   side_effect=AssertionError("provisioning attempted")):
                code = self.module.main([
                    "--backend-root", str(self.root),
                    "--config-path", str(self.root / "fresh.json"),
                    "--port", str(port),
                    "--no-build",
                ])
            self.assertEqual(2, code)
            self.assertFalse((self.root / "fresh.json").exists())
        finally:
            holder.close()

    def test_check_port_free_accepts_closed_port(self):
        holder = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            holder.bind(("127.0.0.1", 0))
            port = holder.getsockname()[1]
        finally:
            holder.close()
        self.module.check_port_free(port)

    def test_failed_startup_preserves_preexisting_config(self):
        # A startup failure after refusal must not touch the occupied path:
        # refusal happens before any provisioning call.
        occupied = self.root / "config.json"
        occupied.write_text('{"mine": true}')
        with mock.patch.object(self.module.subprocess, "run",
                               side_effect=AssertionError("provisioning attempted")), \
             mock.patch.object(self.module, "check_port_free",
                               side_effect=AssertionError("port probe after refusal")):
            code = self.module.main([
                "--backend-root", str(self.root),
                "--config-path", str(occupied),
                "--port", "18081",
                "--no-build",
            ])
        self.assertEqual(2, code)
        self.assertEqual('{"mine": true}', occupied.read_text())


if __name__ == "__main__":
    unittest.main()
