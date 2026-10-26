// Verify the voice preview CLI exits before building for help and invalid arguments.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./voice_preview.mjs", import.meta.url));
for (const option of ["-h", "--help"]) {
  test(`voice preview ${option} explains the local fixture`, () => {
    const result = spawnSync(process.execPath, [script, option], { encoding: "utf8" });
    assert.equal(result.status, 0);
    assert.match(result.stdout, /Usage:.*voice_preview\.mjs/);
    assert.match(result.stdout, /http:\/\/127\.0\.0\.1:4179/);
    assert.equal(result.stderr, "");
  });
}
test("voice preview rejects unknown arguments without starting a server", () => {
  const result = spawnSync(process.execPath, [script, "--unknown"], { encoding: "utf8" });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  assert.match(result.stderr, /Invalid arguments\./);
  assert.match(result.stderr, /Usage:.*voice_preview\.mjs/);
});
