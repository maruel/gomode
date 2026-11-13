#!/usr/bin/env python3
"""Run all model-free Android tests with owned hosted and gateway fixtures.

Real model inference/transfers and documentation captures are excluded. Use
--module and --class for focused app or independent Telecom instrumentation, and
--ci for the subset that skips @LocalEmulatorOnly variants.
"""

import argparse
import contextlib
import dataclasses
import http.server
import json
import os
import pathlib
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
from collections.abc import Iterator

from android_devices import adb_path, ready_device_serials, selected_device_serial
from android_sdk import find_sdkmanager

SETTINGS = {
    "service": "gomode-test",
    "serviceVersion": "1.0.0",
    "apiVersion": 1,
    "webShell": {
        "bridgeVersion": 3,
        "toolGroups": [
            {
                "name": "demo",
                "endpoint": "/api/mcp",
                "protocolVersion": "2026-07-28",
                "authRequired": False,
            }
        ],
        "voiceGateway": {"url": "/voice", "required": False, "authRequired": False},
    },
}

# Public gateway choices served by the fixture so documentation captures show the
# voice settings a real host would expose.
VOICE_CHOICES = {
    "choices": [
        {
            "id": "gemini",
            "label": "Gemini Live",
            "transports": ["webrtc"],
            "voices": ["Orus", "Puck"],
        },
        {"id": "remote", "label": "Remote LLM", "transports": ["text"]},
        {"id": "local", "label": "Local LLM", "transports": ["text"]},
    ],
    "defaults": [
        {"transport": "text", "id": "remote"},
        {"transport": "webrtc", "id": "gemini"},
    ],
}

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = (ROOT / "e2e" / "hosted.html").read_bytes()


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        try:
            request = json.loads(self.rfile.read(length))
        except json.JSONDecodeError:
            self.send_error(400, "Invalid JSON")
            return
        method = request.get("method")
        if method == "server/discover":
            result = {
                "resultType": "complete",
                "supportedVersions": ["2026-07-28"],
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "android-demo", "version": "1"},
                "instructions": "Demonstration host for Go Mode documentation captures.",
                "ttlMs": 60000,
                "cacheScope": "private",
            }
        elif method == "tools/list":
            result = {"resultType": "complete", "tools": [], "ttlMs": 60000, "cacheScope": "private"}
        else:
            result = {"resultType": "complete", "resources": [], "ttlMs": 60000, "cacheScope": "private"}
        body = json.dumps({"jsonrpc": "2.0", "id": request.get("id"), "result": result}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/.well-known/gomode.json":
            body = json.dumps(SETTINGS).encode("utf-8")
            content_type = "application/json"
        elif self.path == "/voice/api/voicegateway/v2/voice/choices":
            body = json.dumps(VOICE_CHOICES).encode("utf-8")
            content_type = "application/json"
        else:
            body = PAGE
            content_type = "text/html; charset=utf-8"

        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: object) -> None:
        pass


APP_PACKAGE = "com.fghbuild.gomode"
APP_RUNNER = APP_PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TELECOM_PACKAGE = "com.fghbuild.gomode.cardialfixture"
TELECOM_RUNNER = TELECOM_PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
EXCLUDED = [APP_PACKAGE + ".DocumentationCapture", APP_PACKAGE + ".ModelAcceptance"]
DENIED_MICROPHONE = APP_PACKAGE + ".DeniedMicrophonePermission"
# Each instrumentation APK declares its own annotation; the runner rejects unknown classes.
APP_LOCAL_ONLY = APP_PACKAGE + ".LocalEmulatorOnly"
TELECOM_LOCAL_ONLY = TELECOM_PACKAGE + ".LocalEmulatorOnly"
RESULTS = ROOT / "android" / "gomode" / "build" / "outputs" / "model-free-tests"


@dataclasses.dataclass
class TestResult:
    name: str
    seconds: float
    status: int


@dataclasses.dataclass
class InstrumentationResult:
    passed: bool
    seconds: float
    tests: list[TestResult]


def shell(adb: str, serial: str, *arguments: str) -> str:
    return subprocess.run(
        [adb, "-s", serial, "shell", *arguments], capture_output=True, text=True, check=True
    ).stdout.strip()


def require_emulator(adb: str, serial: str) -> None:
    """Refuse unsupported devices before installs, forwarding, or permission changes."""
    if shell(adb, serial, "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Android tests require an emulator; device settings and APKs were not changed")
    if shell(adb, serial, "getprop", "ro.build.version.sdk") != "35":
        raise RuntimeError("Model-free speech fixtures require the stock API35 emulator; device was not changed")
    if shell(adb, serial, "am", "get-current-user") != "0":
        raise RuntimeError("Android fixtures require the emulator's primary user; device was not changed")
    settings = shell(adb, serial, "dumpsys", "lock_settings")
    primary = re.search(r"(?ms)^  User 0\n(.*?)(?=^  User |\Z)", settings)
    credentials = re.findall(r"^\s+CredentialType: ([A-Z_]+)$", primary[1], re.MULTILINE) if primary else []
    if credentials != ["NONE"]:
        raise RuntimeError("Android tests require an emulator without a secure credential; device was not changed")


def prepare_apks(adb: str, serial: str, telecom: bool) -> None:
    """Build before mutation; install each APK independently and retain completed installs on failure for retry."""
    sdk_script = pathlib.Path(__file__).with_name("android_sdk.py")
    subprocess.run([sys.executable, str(sdk_script), "check"], check=True)
    sdk = find_sdkmanager()
    if sdk is None:
        raise RuntimeError("Android SDK was not available after setup")
    gradle_env = {**os.environ, "ANDROID_HOME": sdk[1], "ANDROID_SDK_ROOT": sdk[1], "ANDROID_SERIAL": serial}
    modules = ["gomode", "car-dial-fixture"] if telecom else ["gomode"]
    tasks = [f":{module}:{task}" for module in modules for task in ("assembleDebug", "assembleDebugAndroidTest")]
    subprocess.run(["./gradlew", *tasks, "--quiet"], cwd=ROOT / "android", env=gradle_env, check=True)
    # Release the build heap before WebView/voice/Telecom launch their processes.
    subprocess.run(["./gradlew", "--stop"], cwd=ROOT / "android", env=gradle_env, check=True)
    for module in modules:
        for path in (
            f"{module}/build/outputs/apk/debug/{module}-debug.apk",
            f"{module}/build/outputs/apk/androidTest/debug/{module}-debug-androidTest.apk",
        ):
            subprocess.run([adb, "-s", serial, "install", "-r", "-t", str(ROOT / "android" / path)], check=True)
    if telecom:
        # Telecom's fixed five-second redirection deadline includes cold app startup.
        output = shell(adb, serial, "cmd", "package", "compile", "-m", "speed", "-f", APP_PACKAGE)
        if output != "Success":
            raise RuntimeError(f"Could not compile the car-call APK: {output}")


@contextlib.contextmanager
def hosted_fixture() -> Iterator[int]:
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
    worker = threading.Thread(target=server.serve_forever)
    worker.start()
    try:
        yield server.server_port
    finally:
        server.shutdown()
        worker.join()
        server.server_close()


def stop_fixture(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=10)
            return
        except subprocess.TimeoutExpired:
            continue
    raise RuntimeError("Gateway fixture did not terminate")


@contextlib.contextmanager
def gateway_fixture() -> Iterator[int]:
    # A private bound-port announcement identifies our process; never probe a
    # guessed free port that another process could take before the server starts.
    with tempfile.TemporaryDirectory(prefix="gomode-gateway-fixture-") as directory:
        binary = pathlib.Path(directory) / "android-voice-fixture"
        subprocess.run(["go", "build", "-o", str(binary), "./internal/cmd/android-voice-fixture"], cwd=ROOT, check=True)
        log_path = RESULTS / f"{pathlib.Path(directory).name}.log"
        with log_path.open("wb") as output:
            process = subprocess.Popen(
                [str(binary), "-addr", "127.0.0.1:0", "-rtc-ip", "10.0.2.2"],
                cwd=ROOT,
                stdout=output,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                deadline = time.monotonic() + 30
                with log_path.open() as log:
                    while time.monotonic() < deadline:
                        line = log.readline()
                        match = re.search(r"msg=listening addr=127\.0\.0\.1:([0-9]+)$", line)
                        if match:
                            yield int(match[1])
                            return
                        if process.poll() is not None:
                            raise RuntimeError(f"Gateway fixture exited during startup; see {log_path}")
                        time.sleep(0.05)
                raise RuntimeError(f"Gateway fixture did not announce readiness; see {log_path}")
            finally:
                stop_fixture(process)


@contextlib.contextmanager
def forward_ports(adb: str, serial: str, ports: list[int]) -> Iterator[None]:
    with contextlib.ExitStack() as cleanup:
        for port in ports:
            reverse = f"tcp:{port}"
            subprocess.run([adb, "-s", serial, "reverse", reverse, reverse], stdout=subprocess.DEVNULL, check=True)
            cleanup.callback(subprocess.run, [adb, "-s", serial, "reverse", "--remove", reverse], check=True)
        yield


@contextlib.contextmanager
def denied_microphone(adb: str, serial: str) -> Iterator[None]:
    """Reset permission prompts outside instrumentation, where revocation is safe."""
    permissions = shell(adb, serial, "dumpsys", "package", APP_PACKAGE)
    match = re.search(r"android.permission.RECORD_AUDIO: granted=(true|false), flags=\[([^]]*)\]", permissions)
    if match is None:
        raise RuntimeError("Could not read the app microphone permission")
    granted = match[1] == "true"
    original_flags = set(match[2].strip().split("|"))
    flags = [flag for flag in ("user-set", "user-fixed") if flag.upper().replace("-", "_") in original_flags]
    try:
        shell(adb, serial, "pm", "revoke", APP_PACKAGE, "android.permission.RECORD_AUDIO")
        shell(
            adb,
            serial,
            "pm",
            "clear-permission-flags",
            APP_PACKAGE,
            "android.permission.RECORD_AUDIO",
            "user-set",
            "user-fixed",
        )
        yield
    finally:
        shell(adb, serial, "am", "force-stop", APP_PACKAGE)
        shell(adb, serial, "pm", "grant" if granted else "revoke", APP_PACKAGE, "android.permission.RECORD_AUDIO")
        shell(
            adb,
            serial,
            "pm",
            "clear-permission-flags",
            APP_PACKAGE,
            "android.permission.RECORD_AUDIO",
            "user-set",
            "user-fixed",
        )
        if flags:
            shell(adb, serial, "pm", "set-permission-flags", APP_PACKAGE, "android.permission.RECORD_AUDIO", *flags)


def instrument(
    adb: str, serial: str, name: str, runner: str, arguments: dict[str, str], discovery: bool
) -> InstrumentationResult:
    command = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r"]
    for key, value in arguments.items():
        command.extend(("-e", key, value))
    if discovery:
        command.extend(("-e", "log", "true"))
    command.append(runner)
    started = time.monotonic()
    test_started = started
    current: dict[str, str] = {}
    tests: list[TestResult] = []
    summary = False
    failed = False
    completed = False
    with (RESULTS / f"{name}.log").open("w") as log:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            assert process.stdout is not None
            for line in process.stdout:
                log.write(line)
                log.flush()
                if not discovery and (
                    line.startswith("INSTRUMENTATION_STATUS: stack=")
                    or not line.startswith(("INSTRUMENTATION_STATUS:", "INSTRUMENTATION_STATUS_CODE:"))
                ):
                    print(line, end="", flush=True)
                match = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
                if match:
                    current[match[1]] = match[2].strip()
                code = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)", line)
                if code and "class" in current and "test" in current:
                    status = int(code[1])
                    if status == 1:
                        test_started = time.monotonic()
                    else:
                        test = TestResult(
                            current["class"] + "#" + current["test"], time.monotonic() - test_started, status
                        )
                        tests.append(test)
                        current = {}
                        if not discovery:
                            print(f"TEST {test.seconds:.3f}s status={status} {test.name}", flush=True)
                summary |= re.fullmatch(r"OK \([0-9]+ tests?\)", line.strip()) is not None
                failed |= line.startswith(("FAILURES!!!", "INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:"))
                completed |= line.strip() == "INSTRUMENTATION_CODE: -1"
            status = process.wait()
        finally:
            if process.poll() is None:
                # Cancelling adb alone leaves remote instrumentation running.
                target = runner.partition("/")[0].removesuffix(".test")
                try:
                    shell(adb, serial, "am", "force-stop", target)
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
            if process.stdout is not None:
                process.stdout.close()
            if not discovery:
                with (RESULTS / f"{name}-logcat.log").open("w") as logcat:
                    subprocess.run([adb, "-s", serial, "logcat", "-d", "-t", "3000"], stdout=logcat, check=True)
    result = InstrumentationResult(
        status == 0 and summary and completed and not failed, time.monotonic() - started, tests
    )
    return result


def run_suite(
    adb: str, serial: str, name: str, runner: str, arguments: dict[str, str], requires_denied_microphone: bool
) -> InstrumentationResult:
    discovered = instrument(adb, serial, name + "-discovery", runner, arguments, True)
    (RESULTS / f"{name}-discovery.json").write_text(json.dumps(dataclasses.asdict(discovered), indent=2) + "\n")
    if not discovered.passed:
        raise RuntimeError(f"Instrumentation discovery failed for {name}; see {RESULTS}")
    if not discovered.tests:
        print(f"{name}: no matching tests", flush=True)
        return discovered
    permission = denied_microphone(adb, serial) if requires_denied_microphone else contextlib.nullcontext()
    with permission:
        result = instrument(adb, serial, name, runner, arguments, False)
    expected = {test.name for test in discovered.tests}
    actual = {test.name for test in result.tests}
    complete = expected == actual and len(result.tests) == len(discovered.tests)
    print(
        f"{name}: {len(result.tests)}/{len(discovered.tests)} tests in {result.seconds:.2f}s; "
        f"passed={result.passed and complete}",
        flush=True,
    )
    if not complete:
        print(
            f"Missing tests: {sorted(expected - actual)}; unexpected tests: {sorted(actual - expected)}",
            file=sys.stderr,
        )
    result.passed &= complete
    (RESULTS / f"{name}.json").write_text(json.dumps(dataclasses.asdict(result), indent=2) + "\n")
    return result


def run_capture_tests(port: int, arguments: dict[str, str], serial: str) -> int:
    """Build and run explicit documentation captures on their hosted fixture."""
    adb = adb_path()
    RESULTS.mkdir(parents=True, exist_ok=True)
    prepare_apks(adb, serial, False)
    with forward_ports(adb, serial, [port]):
        result = run_suite(
            adb, serial, "captures", APP_RUNNER, {"baseUrl": f"http://localhost:{port}", **arguments}, False
        )
        return 0 if result.passed and result.tests else 1


def exit_on_signal(signum: int, _frame: object) -> None:
    raise SystemExit(128 + signum)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", choices=("app", "telecom"), help="run only one instrumentation APK")
    parser.add_argument(
        "--class",
        dest="test_class",
        metavar="CLASS[#METHOD]",
        help="focus a class/method (app unless --module telecom)",
    )
    parser.add_argument("--ci", action="store_true", help="skip @LocalEmulatorOnly variants to fit slower CI runners")
    args = parser.parse_args()
    module = args.module or ("app" if args.test_class else None)
    adb = adb_path()
    if os.environ.get("ANDROID_SERIAL"):
        serial = selected_device_serial(adb)
    else:
        emulators = [serial for serial in ready_device_serials(adb) if serial.startswith("emulator-")]
        if len(emulators) != 1:
            raise RuntimeError(f"Expected one ready emulator, found {len(emulators)}; set ANDROID_SERIAL")
        serial = emulators[0]
    require_emulator(adb, serial)
    signal.signal(signal.SIGTERM, exit_on_signal)
    RESULTS.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    prepare_apks(adb, serial, module != "app")
    selection = {"class": args.test_class} if args.test_class else {}
    app_excluded = [*EXCLUDED, APP_LOCAL_ONLY] if args.ci else EXCLUDED
    telecom_excluded = {"notAnnotation": TELECOM_LOCAL_ONLY} if args.ci else {}
    results: list[InstrumentationResult] = []
    with hosted_fixture() as hosted, gateway_fixture() as gateway, forward_ports(adb, serial, [hosted, gateway]):
        if module != "telecom":
            arguments = {
                "baseUrl": f"http://localhost:{hosted}",
                "gatewayBaseUrl": f"http://localhost:{gateway}",
                **selection,
            }
            results.append(
                run_suite(
                    adb,
                    serial,
                    "app-microphone-denied",
                    APP_RUNNER,
                    {**arguments, "annotation": DENIED_MICROPHONE, "notAnnotation": ",".join(app_excluded)},
                    True,
                )
            )
            results.append(
                run_suite(
                    adb,
                    serial,
                    "app",
                    APP_RUNNER,
                    {**arguments, "notAnnotation": ",".join([*app_excluded, DENIED_MICROPHONE])},
                    False,
                )
            )
        if module != "app":
            results.append(
                run_suite(
                    adb,
                    serial,
                    "telecom",
                    TELECOM_RUNNER,
                    {"baseUrl": f"http://localhost:{gateway}", **telecom_excluded, **selection},
                    False,
                )
            )
    count = sum(len(result.tests) for result in results)
    passed = all(result.passed for result in results) and count > 0
    if not count:
        print("No model-free tests matched the selection.", file=sys.stderr)
    print(
        f"Android model-free run: {count} tests in {time.monotonic() - started:.2f}s; "
        f"passed={passed}; results: {RESULTS}",
        flush=True,
    )
    return 0 if passed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
