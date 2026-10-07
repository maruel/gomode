"""Regressions for managed capture entrypoints and failed Android test forwarding cleanup."""

import contextlib
import io
import sys
import unittest
from types import SimpleNamespace
from unittest import mock

import android_e2e


class InstrumentedEntrypointTest(unittest.TestCase):
    def test_direct_screenshot_flags_cannot_select_or_modify_a_physical_device(self):
        with (
            mock.patch.object(
                sys,
                "argv",
                ["android_e2e.py", "--screenshots", "--screenshots-icon-blacklist", "mobile,satellite,wifi"],
            ),
            mock.patch.object(android_e2e, "adb_path", return_value="adb"),
            mock.patch.object(android_e2e, "selected_device_serial", return_value="physical-phone") as select,
            mock.patch.object(android_e2e, "find_sdkmanager", return_value=("sdkmanager", "/sdk")),
            mock.patch.object(android_e2e.http.server, "ThreadingHTTPServer"),
            mock.patch.object(android_e2e.subprocess, "run", return_value=SimpleNamespace(returncode=0)) as run,
            contextlib.redirect_stderr(io.StringIO()),
        ):
            with self.assertRaises(SystemExit) as failure:
                android_e2e.main()
            self.assertEqual(failure.exception.code, 2)
        select.assert_not_called()
        run.assert_not_called()

    def test_failed_instrumentation_releases_forwarding_on_the_selected_device(self):
        with (
            mock.patch.object(android_e2e, "adb_path", return_value="adb"),
            mock.patch.object(android_e2e, "find_sdkmanager", return_value=("sdkmanager", "/sdk")),
            mock.patch.object(
                android_e2e.subprocess,
                "run",
                side_effect=[
                    SimpleNamespace(returncode=0),
                    SimpleNamespace(returncode=0),
                    FileNotFoundError("Gradle wrapper was removed"),
                    SimpleNamespace(returncode=0),
                ],
            ) as run,
        ):
            with self.assertRaises(FileNotFoundError):
                android_e2e.run_instrumented_tests(41743, ["-Ptest=fixture"], "emulator-5554")
        self.assertEqual(run.call_args.args[0], ["adb", "-s", "emulator-5554", "reverse", "--remove", "tcp:41743"])
        self.assertEqual(run.call_args_list[2].kwargs["env"]["ANDROID_SERIAL"], "emulator-5554")


if __name__ == "__main__":
    unittest.main()
