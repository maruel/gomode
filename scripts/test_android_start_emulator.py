"""Regression tests for owned emulator reuse, provisioning, and resource safety."""

import unittest
from types import SimpleNamespace
from unittest import mock

import android_start_emulator as launcher


class EmulatorLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.args = SimpleNamespace(auto_reuse=True, reuse_connected_device=False)
        for name, value in (
            ("_sdk_root", "/sdk"),
            ("adb_path", "adb"),
            ("_running_avd_serial", None),
            ("_wait_for_starting_avd", None),
        ):
            patch = mock.patch.object(launcher, name, return_value=value)
            patch.start()
            self.addCleanup(patch.stop)

    def test_legacy_emulator_does_not_launch_a_second_owned_emulator(self):
        with (
            mock.patch.object(launcher, "ready_device_serials", return_value=["emulator-5554"]),
            mock.patch.object(launcher.subprocess, "Popen") as launch,
        ):
            self.assertEqual(launcher._start(self.args), 1)
        launch.assert_not_called()

    def test_owned_emulator_reuse_does_not_reprovision_or_launch(self):
        with (
            mock.patch.object(launcher, "_running_avd_serial", return_value="emulator-5554"),
            mock.patch.object(launcher, "_wait_for_existing_boot", return_value=0),
            mock.patch.object(launcher.subprocess, "run") as provision,
            mock.patch.object(launcher.subprocess, "Popen") as launch,
        ):
            self.assertEqual(launcher._start(self.args), 0)
        provision.assert_not_called()
        launch.assert_not_called()

    def test_failed_first_provisioning_propagates_before_launch(self):
        with (
            mock.patch.object(launcher, "ready_device_serials", return_value=[]),
            mock.patch.object(launcher, "_check_host", return_value=0),
            mock.patch.object(launcher.subprocess, "run", return_value=SimpleNamespace(returncode=7)),
            mock.patch.object(launcher.subprocess, "Popen") as launch,
        ):
            self.assertEqual(launcher._start(self.args), 7)
        launch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
