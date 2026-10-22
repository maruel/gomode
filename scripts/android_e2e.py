#!/usr/bin/env python3
"""Run hosted Go Mode tests and share device-scoped instrumentation execution.

With --voice, serve internal/cmd/android-voice-fixture instead and select tests
marked @VoiceFixture.
"""

import argparse
import http.server
import json
import os
import pathlib
import socket
import subprocess
import sys
import threading

from android_devices import adb_path, selected_device_serial
from android_sdk import find_sdkmanager

SETTINGS = {
    "service": "gomode-test",
    "serviceVersion": "1.0.0",
    "apiVersion": 1,
    "webShell": {
        "bridgeVersion": 1,
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
            "transports": ["webrtc", "text"],
            "voices": ["Orus", "Puck"],
            "defaultVoice": "Orus",
        },
        {"id": "remote", "label": "Remote LLM", "transports": ["text"]},
    ],
    "defaultChoiceID": "gemini",
}

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = (ROOT / "e2e" / "hosted.html").read_bytes()

HOSTED_FIXTURE_ANNOTATION = "com.fghbuild.gomode.StandaloneHostedFixture"
VOICE_FIXTURE_ANNOTATION = "com.fghbuild.gomode.VoiceFixture"
VOICE_FIXTURE_PACKAGE = "./internal/cmd/android-voice-fixture"


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
        elif self.path == "/voice/api/voicegateway/v1/voice/choices":
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


def free_port() -> int:
    """Return a free TCP port on the loopback interface."""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run_instrumented_tests(port: int, selection: list[str], serial: str) -> int:
    """Run selected Android tests with a hosted fixture and clean up adb forwarding."""
    adb = adb_path()
    sdk_script = pathlib.Path(__file__).with_name("android_sdk.py")
    subprocess.run([sys.executable, str(sdk_script), "check"], check=True)
    sdk = find_sdkmanager()
    if sdk is None:
        raise RuntimeError("Android SDK was not available after setup")
    gradle_env = {**os.environ, "ANDROID_HOME": sdk[1], "ANDROID_SDK_ROOT": sdk[1], "ANDROID_SERIAL": serial}
    reverse = f"tcp:{port}"
    try:
        subprocess.run([adb, "-s", serial, "reverse", reverse, reverse], check=True)
        print(
            f"Testing Go Mode shell on {serial} with the fixture at localhost:{port}",
            flush=True,
        )
        return subprocess.run(
            [
                "./gradlew",
                "--max-workers=2",
                ":gomode:connectedDebugAndroidTest",
                f"-Pandroid.testInstrumentationRunnerArguments.baseUrl=http://localhost:{port}",
                *selection,
            ],
            cwd=ROOT / "android",
            env=gradle_env,
            check=False,
        ).returncode
    finally:
        subprocess.run([adb, "-s", serial, "reverse", "--remove", reverse], check=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--voice", action="store_true", help="serve the voice gateway fixture")
    args = parser.parse_args()
    server: http.server.ThreadingHTTPServer | None = None
    fixture: subprocess.Popen[bytes] | None = None
    if args.voice:
        port = free_port()
        fixture = subprocess.Popen(["go", "run", VOICE_FIXTURE_PACKAGE, "-addr", f"127.0.0.1:{port}"], cwd=ROOT)
        annotation = VOICE_FIXTURE_ANNOTATION
    else:
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_port
        annotation = HOSTED_FIXTURE_ANNOTATION

    try:
        serial = selected_device_serial(adb_path())
        return run_instrumented_tests(
            port, [f"-Pandroid.testInstrumentationRunnerArguments.annotation={annotation}"], serial
        )
    finally:
        if fixture is not None:
            fixture.terminate()
            fixture.wait(timeout=10)
        if server is not None:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
