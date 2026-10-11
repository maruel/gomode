#!/usr/bin/env python3
"""Test variant-specific Android model discovery, installation, and download publication."""

import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import android_push_model as models


class ModelPushTest(unittest.TestCase):
    def test_variants_do_not_overwrite_each_other(self):
        with tempfile.TemporaryDirectory() as directory:
            for variant in ("E2B", "E4B"):
                path = pathlib.Path(directory) / f"gemma-4-{variant}-it.litertlm"
                path.write_bytes(b"weights")
                with patch.object(models.subprocess, "run") as run:
                    run.return_value = subprocess.CompletedProcess([], 0, stdout="installed")
                    models.push_model("adb", "device", path, variant)
                    pushes = [call.args[0] for call in run.call_args_list if "push" in call.args[0]]
                    self.assertEqual(1, len(pushes))
                    self.assertEqual(f"/data/local/tmp/llm/gemma-4-{variant}-it.litertlm", pushes[0][-1])

    def test_conflicting_filename_is_rejected_before_device_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "gemma-4-E4B-it.litertlm"
            path.write_bytes(b"weights")
            with patch.object(models.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "conflicts"):
                    models.push_model("adb", "device", path, "E2B")
                run.assert_not_called()

    def test_discovery_uses_selected_variant_not_newest_weights(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = pathlib.Path.cwd()
            try:
                os.chdir(directory)
                pathlib.Path("models").mkdir()
                e2b = pathlib.Path("models/gemma-4-E2B-it.litertlm")
                e2b.write_bytes(b"e2b")
                self.assertEqual(e2b, models.find_local_model("E2B"))
                self.assertIsNone(models.find_local_model("E4B"))
            finally:
                os.chdir(previous)

    def test_interrupted_download_is_not_reused_as_complete_weights(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory)

            def fail_download(url, path, reporthook):
                pathlib.Path(path).write_bytes(b"partial")
                raise OSError("interrupted")

            with patch.object(models.urllib.request, "urlretrieve", side_effect=fail_download):
                with self.assertRaises(OSError):
                    models.download_model(models.MODEL_URLS["E4B"], destination)
            self.assertFalse((destination / "gemma-4-E4B-it.litertlm").exists())

    def test_environment_selects_variant_for_make(self):
        with (
            patch.dict(os.environ, {"VARIANT": "E4B"}),
            patch.object(models, "find_local_model", return_value=pathlib.Path("model.litertlm")) as find,
            patch.object(models, "adb_path", return_value="adb"),
            patch.object(models, "selected_device_serial", return_value="device"),
            patch.object(models, "push_model") as push,
        ):
            self.assertEqual(0, models.main([]))
            find.assert_called_once_with("E4B")
            push.assert_called_once_with("adb", "device", pathlib.Path("model.litertlm"), "E4B")


if __name__ == "__main__":
    unittest.main()
