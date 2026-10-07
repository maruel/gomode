"""Regression tests for emulator capture safety, settings restoration, and pixel comparison."""

import fcntl
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import android_screenshots


class CaptureEnvironmentTest(unittest.TestCase):
    def test_incompatible_legacy_profile_is_refused_before_settings_mutation(self):
        with (
            mock.patch.object(sys, "argv", ["android_screenshots.py", "generate"]),
            mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
            mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
            mock.patch.object(android_screenshots, "selected_device_serial", return_value="emulator-5554"),
            mock.patch.object(
                android_screenshots,
                "wait_for_capture_services",
                return_value=("isInDemoMode=false", "[x] com.android.internal.emulation.pixel_6"),
            ),
            mock.patch.object(android_screenshots, "adb_command") as adb,
        ):
            with self.assertRaisesRegex(RuntimeError, "legacy Pixel6 capture profile"):
                android_screenshots.main()
        adb.assert_not_called()

    def test_boot_complete_does_not_imply_overlay_service_readiness(self):
        state = "isInDemoMode=false\nclock : [Clock]\nbattery : [Battery]"
        overlays = "[x] com.android.internal.emulation.pixel_6"
        with (
            mock.patch.object(
                android_screenshots,
                "adb_command",
                side_effect=[state, subprocess.CalledProcessError(20, "cmd overlay list"), state, overlays],
            ),
            mock.patch.object(android_screenshots.time, "sleep") as poll,
        ):
            self.assertEqual(android_screenshots.wait_for_capture_services("adb", "emulator-5554"), (state, overlays))
        poll.assert_called_once()

    def test_unregistered_controller_does_not_imply_demo_settings_observer_readiness(self):
        unregistered = "isInDemoMode=false\nclock : []\nbattery : [Battery]"
        registered = "isInDemoMode=false\nclock : [Clock]\nbattery : [Battery]"
        with (
            mock.patch.object(
                android_screenshots,
                "adb_command",
                side_effect=[unregistered, "", registered, ""],
            ),
            mock.patch.object(android_screenshots.time, "sleep") as poll,
        ):
            self.assertEqual(android_screenshots.wait_for_capture_services("adb", "emulator-5554"), (registered, ""))
        poll.assert_called_once()

    def test_concurrent_capture_is_refused_before_device_selection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            baselines = root / "e2e/screenshots/android"
            baselines.parent.mkdir(parents=True)
            with (
                baselines.with_name(".android.capture.lock").open("a") as handle,
                mock.patch.object(android_screenshots, "ROOT", root),
                mock.patch.object(android_screenshots, "BASELINES", baselines),
                mock.patch.object(sys, "argv", ["android_screenshots.py", "generate"]),
                mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
                mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
                mock.patch.object(
                    android_screenshots, "selected_device_serial", return_value="physical-phone"
                ) as select,
            ):
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with self.assertRaisesRegex(RuntimeError, "Another screenshot capture is active"):
                    android_screenshots.main()
            select.assert_not_called()

    def test_physical_device_is_refused_before_mutation(self):
        with (
            mock.patch.object(sys, "argv", ["android_screenshots.py", "update"]),
            mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
            mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
            mock.patch.object(android_screenshots, "selected_device_serial", return_value="physical-device"),
            mock.patch.object(android_screenshots, "adb_command") as adb,
        ):
            with self.assertRaisesRegex(RuntimeError, "require an emulator"):
                android_screenshots.main()
            adb.assert_not_called()

    def test_active_systemui_demo_is_preserved_even_without_a_persisted_toggle(self):
        def response(_adb, _serial, *arguments):
            if arguments[-1] == "DemoModeController":
                return "isInDemoMode=true\nclock : [Clock]\nbattery : [Battery]"
            return "null"

        with (
            mock.patch.object(sys, "argv", ["android_screenshots.py", "generate"]),
            mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
            mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
            mock.patch.object(android_screenshots, "selected_device_serial", return_value="emulator-5554"),
            mock.patch.object(android_screenshots, "adb_command", side_effect=response) as adb,
        ):
            with self.assertRaisesRegex(RuntimeError, "existing SystemUI demo"):
                android_screenshots.main()
        self.assertFalse(
            any("put" in call.args or "delete" in call.args or "disable" in call.args for call in adb.call_args_list)
        )

    def test_unsupported_icon_slot_syntax_is_refused_before_mutation(self):
        def response(_adb, _serial, *arguments):
            if arguments[-1] == "DemoModeController":
                return "isInDemoMode=false\nclock : [Clock]\nbattery : [Battery]"
            if arguments == ("shell", "settings", "get", "secure", "icon_blacklist"):
                return "alarm clock"
            return "null"

        with (
            mock.patch.object(sys, "argv", ["android_screenshots.py", "generate"]),
            mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
            mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
            mock.patch.object(android_screenshots, "selected_device_serial", return_value="emulator-5554"),
            mock.patch.object(android_screenshots, "adb_command", side_effect=response) as adb,
        ):
            with self.assertRaisesRegex(RuntimeError, "Unsupported SystemUI icon slot syntax"):
                android_screenshots.main()
        self.assertFalse(any("put" in call.args or "delete" in call.args for call in adb.call_args_list))

    def test_other_display_locale_is_refused_before_changing_settings(self):
        def response(_adb, _serial, *arguments):
            if arguments[-1] == "DemoModeController":
                return "isInDemoMode=false\nclock : [Clock]\nbattery : [Battery]"
            if arguments == ("shell", "cmd", "activity", "get-config"):
                return "config: de-rDE-ldltr-sw411dp"
            return "null"

        with (
            mock.patch.object(sys, "argv", ["android_screenshots.py", "generate"]),
            mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
            mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
            mock.patch.object(android_screenshots, "selected_device_serial", return_value="emulator-5554"),
            mock.patch.object(android_screenshots, "adb_command", side_effect=response) as adb,
        ):
            with self.assertRaisesRegex(RuntimeError, "require the en-US display locale"):
                android_screenshots.main()
        self.assertFalse(
            any("put" in call.args or "delete" in call.args or "disable" in call.args for call in adb.call_args_list)
        )

    def test_other_device_overlays_are_not_replaced_on_failed_capture(self):
        def response(_adb, _serial, *arguments):
            if arguments[-1] == "DemoModeController":
                return "isInDemoMode=false\nclock : [Clock]\nbattery : [Battery]"
            if arguments == ("shell", "cmd", "activity", "get-config"):
                return "config: en-rUS-ldltr"
            if arguments == ("shell", "cmd", "overlay", "list"):
                return "[x] com.android.internal.emulation.pixel_6_pro\n[x] com.android.systemui.emulation.pixel_6_pro"
            return "null"

        with (
            mock.patch.object(sys, "argv", ["android_screenshots.py", "generate"]),
            mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
            mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
            mock.patch.object(android_screenshots, "selected_device_serial", return_value="emulator-5554"),
            mock.patch.object(android_screenshots, "adb_command", side_effect=response) as adb,
            mock.patch.object(
                android_screenshots, "render", side_effect=RuntimeError("instrumentation failed")
            ) as render,
        ):
            with self.assertRaisesRegex(RuntimeError, "instrumentation failed"):
                android_screenshots.main()
        self.assertEqual(render.call_args.args[-1], "mobile,satellite,wifi")
        self.assertFalse(any(call.args[3:6] == ("cmd", "overlay", "disable") for call in adb.call_args_list))
        self.assertFalse(any(call.args[3:6] == ("cmd", "overlay", "enable") for call in adb.call_args_list))

    def test_failed_render_restores_emulator_settings(self):
        calls = []
        restored_under_lock = []

        def adb_command(_adb, _serial, *arguments):
            calls.append(arguments)
            if arguments == ("shell", "settings", "delete", "global", "sysui_tuner_demo_on"):
                with self.assertRaisesRegex(RuntimeError, "Another screenshot capture is active"):
                    with android_screenshots.catalog_lock(
                        android_screenshots.ROOT, android_screenshots.BASELINES, "capture"
                    ):
                        self.fail("Run lock was released before final settings restoration")
                restored_under_lock.append(True)
            if arguments == ("shell", "cmd", "activity", "get-config"):
                return "config: en-rUS-ldltr"
            if arguments == ("shell", "cmd", "overlay", "list"):
                return "[x] com.android.systemui.auto_generated_rro_product__"
            if arguments[-1] == "DemoModeController":
                return "isInDemoMode=false\nclock : [Clock]\nbattery : [Battery]"
            if arguments == ("shell", "settings", "get", "secure", "icon_blacklist"):
                return "alarm_clock"
            if arguments[:3] == ("shell", "settings", "get"):
                return (
                    "null"
                    if arguments[-1] in ("sysui_demo_allowed", "sysui_tuner_demo_on", "show_ime_with_hard_keyboard")
                    else "1.0"
                )
            return ""

        with (
            mock.patch.object(sys, "argv", ["android_screenshots.py", "update"]),
            mock.patch.object(android_screenshots.shutil, "which", return_value="/usr/bin/tool"),
            mock.patch.object(android_screenshots, "adb_path", return_value="adb"),
            mock.patch.object(android_screenshots, "selected_device_serial", return_value="emulator-5554"),
            mock.patch.object(android_screenshots, "adb_command", side_effect=adb_command),
            mock.patch.object(
                android_screenshots, "render", side_effect=RuntimeError("instrumentation failed")
            ) as render,
        ):
            with self.assertRaisesRegex(RuntimeError, "instrumentation failed"):
                android_screenshots.main()
        self.assertEqual(render.call_args.args[-1], "alarm_clock,mobile,satellite,wifi")
        self.assertEqual(restored_under_lock, [True])
        with android_screenshots.catalog_lock(android_screenshots.ROOT, android_screenshots.BASELINES, "capture"):
            pass
        self.assertIn(("shell", "settings", "delete", "secure", "show_ime_with_hard_keyboard"), calls)
        self.assertIn(("shell", "settings", "put", "secure", "icon_blacklist", "alarm_clock"), calls)
        restored = ("shell", "settings", "delete", "global", "sysui_demo_allowed")
        exited = ("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit")
        self.assertLess(calls.index(exited), calls.index(restored))
        for key in ("animator_duration_scale", "transition_animation_scale", "window_animation_scale"):
            self.assertIn(("shell", "settings", "put", "global", key, "1.0"), calls)


class ProvenanceTest(unittest.TestCase):
    def test_generated_sdk_edits_invalidate_capture_inputs_without_a_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / "sdk/gomode/kotlin/src/main/kotlin/Types.kt"
            sdk.parent.mkdir(parents=True)
            sdk.write_text("data class Manifest(val version: Int)\n")
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(
                [
                    "git",
                    "-c",
                    "user.name=Capture test",
                    "-c",
                    "user.email=capture@example.invalid",
                    "commit",
                    "-qm",
                    "fixture",
                ],
                cwd=root,
                check=True,
            )
            with mock.patch.object(android_screenshots, "ROOT", root):
                before = android_screenshots.provenance()
                sdk.write_text("data class Manifest(val revision: Int)\n")
                after = android_screenshots.provenance()
            self.assertEqual(before["revision"], after["revision"])
            self.assertNotEqual(before["inputsSha256"], after["inputsSha256"])


class PixelComparisonTest(unittest.TestCase):
    def test_only_bounded_status_bar_antialiasing_is_accepted(self):
        original = bytes(30000)
        changed = bytearray(original)
        changed[0] = 2
        with (
            mock.patch.object(android_screenshots, "SCENARIOS", {"scene": "demo"}),
            mock.patch.object(android_screenshots, "dimensions", return_value=(100, 100)),
            mock.patch.object(android_screenshots, "pixels", side_effect=[bytes(changed), original]),
        ):
            android_screenshots.compare(Path("first"), Path("second"), 10)

    def test_product_pixel_and_meaningful_systemui_changes_are_rejected(self):
        original = bytes(30000)
        for channel, value, message in ((3000, 1, "product pixels"), (0, 3, "meaningfully")):
            with self.subTest(channel=channel):
                changed = bytearray(original)
                changed[channel] = value
                with (
                    mock.patch.object(android_screenshots, "SCENARIOS", {"scene": "demo"}),
                    mock.patch.object(android_screenshots, "dimensions", return_value=(100, 100)),
                    mock.patch.object(android_screenshots, "pixels", side_effect=[bytes(changed), original]),
                ):
                    with self.assertRaisesRegex(RuntimeError, message):
                        android_screenshots.compare(Path("first"), Path("second"), 10)

    def test_widespread_status_bar_change_is_rejected(self):
        original = bytes(30000)
        changed = bytearray(original)
        changed[:33] = bytes([1] * 33)
        with (
            mock.patch.object(android_screenshots, "SCENARIOS", {"scene": "demo"}),
            mock.patch.object(android_screenshots, "dimensions", return_value=(100, 100)),
            mock.patch.object(android_screenshots, "pixels", side_effect=[bytes(changed), original]),
        ):
            with self.assertRaisesRegex(RuntimeError, "across 11 pixels"):
                android_screenshots.compare(Path("first"), Path("second"), 10)
