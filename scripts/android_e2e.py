#!/usr/bin/env python3
"""Run Go Mode shell tests against a standalone hosted frontend fixture."""

import http.server
import json
import os
import pathlib
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
        "toolGroups": [],
        "voiceGateway": {"required": False, "authRequired": False},
    },
}

PAGE = b"""<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body><div id="app"><div style="min-height:100vh">Go Mode hosted test frontend</div></div></body></html>
"""

HOSTED_FIXTURE_ANNOTATION = "com.fghbuild.gomode.StandaloneHostedFixture"


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path == "/.well-known/gomode.json":
            body = json.dumps(SETTINGS).encode("utf-8")
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


def main() -> int:
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_port

    try:
        adb = adb_path()
        serial = selected_device_serial(adb)
        sdk_script = pathlib.Path(__file__).with_name("android_sdk.py")
        subprocess.run([sys.executable, str(sdk_script), "check"], check=True)
        sdk = find_sdkmanager()
        if sdk is None:
            raise RuntimeError("Android SDK was not available after setup")
        gradle_env = {**os.environ, "ANDROID_HOME": sdk[1], "ANDROID_SDK_ROOT": sdk[1]}
        reverse = f"tcp:{port}"
        try:
            subprocess.run([adb, "-s", serial, "reverse", reverse, reverse], check=True)
            print(
                f"Testing Go Mode shell on {serial} with hosted fixture at localhost:{port}",
                flush=True,
            )
            return subprocess.run(
                [
                    "./gradlew",
                    "--no-daemon",
                    ":gomode:connectedDebugAndroidTest",
                    f"-Pandroid.testInstrumentationRunnerArguments.baseUrl=http://localhost:{port}",
                    f"-Pandroid.testInstrumentationRunnerArguments.annotation={HOSTED_FIXTURE_ANNOTATION}",
                ],
                cwd="android",
                env=gradle_env,
                check=False,
            ).returncode
        finally:
            subprocess.run(
                [adb, "-s", serial, "reverse", "--remove", reverse], check=False
            )
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
