"""Regressions for Android LiteRT-LM model weight push tooling."""

import pathlib
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

import android_push_model


class AndroidPushModelTest(unittest.TestCase):
    def test_missing_model_file_raises_file_not_found(self):
        nonexistent = pathlib.Path("/tmp/nonexistent/gemma.litertlm")
        with self.assertRaises(FileNotFoundError):
            android_push_model.push_model("adb", "device-1", nonexistent, "/data/local/tmp/llm/model.litertlm")

    def test_missing_argument_raises_value_error(self):
        with mock.patch.dict(android_push_model.os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                android_push_model.main([])

    def test_push_model_invokes_adb_mkdir_push_and_ls(self):
        with tempfile.NamedTemporaryFile() as tmp:
            model_path = pathlib.Path(tmp.name)
            calls: list[list[str]] = []

            def fake_run(cmd: list[str], **kwargs):
                calls.append(cmd)
                return SimpleNamespace(returncode=0, stdout="verified\n")

            with mock.patch.object(android_push_model.subprocess, "run", side_effect=fake_run):
                android_push_model.push_model("adb", "emulator-5554", model_path, "/data/local/tmp/llm/model.litertlm")

            self.assertEqual(len(calls), 3)
            self.assertEqual(
                calls[0],
                ["adb", "-s", "emulator-5554", "shell", "mkdir", "-p", "/data/local/tmp/llm"],
            )
            self.assertEqual(
                calls[1],
                ["adb", "-s", "emulator-5554", "push", str(model_path.resolve()), "/data/local/tmp/llm/model.litertlm"],
            )
            self.assertEqual(
                calls[2],
                ["adb", "-s", "emulator-5554", "shell", "ls", "-l", "/data/local/tmp/llm/model.litertlm"],
            )

    def test_main_uses_provided_args(self):
        with tempfile.NamedTemporaryFile() as tmp:
            with (
                mock.patch.object(android_push_model, "adb_path", return_value="adb"),
                mock.patch.object(android_push_model, "push_model") as mock_push,
            ):
                code = android_push_model.main([tmp.name, "--dest", "/custom/dest.litertlm", "--serial", "pixel-8-pro"])
                self.assertEqual(code, 0)
                mock_push.assert_called_once_with(
                    "adb",
                    "pixel-8-pro",
                    pathlib.Path(tmp.name),
                    "/custom/dest.litertlm",
                )


if __name__ == "__main__":
    unittest.main()
