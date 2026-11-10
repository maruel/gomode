// Proves the public browser integration keeps voice runtime and overlay code outside its eager production graph.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { transformAsync } from "@babel/core";
import { build } from "esbuild";

const root = fileURLToPath(new URL("../", import.meta.url));

test("a production consumer loads voice runtime and UI only through its dynamic boundary", async () => {
  const result = await build({
    absWorkingDir: root,
    entryPoints: ["web/tests/browser-integration.tsx"],
    outdir: "e2e/browser-integration/build",
    bundle: true,
    minify: true,
    format: "esm",
    splitting: true,
    conditions: ["browser"],
    write: false,
    metafile: true,
    loader: { ".css": "local-css", ".svg": "file" },
    plugins: [
      {
        name: "solid",
        setup(builder) {
          builder.onLoad({ filter: /\.tsx$/ }, async ({ path: filename }) => {
            const transformed = await transformAsync(await readFile(filename, "utf8"), {
              filename,
              configFile: false,
              babelrc: false,
              presets: ["@babel/preset-typescript", "babel-preset-solid"],
            });
            if (!transformed?.code) throw new Error(`No transformed source: ${filename}`);
            return { contents: transformed.code, loader: "js", resolveDir: path.dirname(filename) };
          });
        },
      },
    ],
  });
  const outputs = result.metafile.outputs;
  const entry = Object.keys(outputs).find((name) => outputs[name].entryPoint === "web/tests/browser-integration.tsx");
  assert.ok(entry);
  const eager = new Set();
  const visit = (filename) => {
    if (eager.has(filename)) return;
    eager.add(filename);
    for (const dependency of outputs[filename].imports) {
      if (!dependency.external && dependency.kind !== "dynamic-import") visit(dependency.path);
    }
  };
  visit(entry);
  const eagerInputs = [...eager].flatMap((name) => Object.keys(outputs[name].inputs));
  assert.ok(eagerInputs.includes("web/src/BrowserVoiceShell.tsx"));
  assert.ok(eagerInputs.includes("web/src/FrontendVoiceTools.ts"));
  for (const optional of ["web/src/VoiceSession.ts", "web/src/VoiceOverlay.tsx", "web/src/BrowserVoiceRuntime.tsx"]) {
    assert.ok(!eagerInputs.includes(optional), `${optional} leaked into the eager graph`);
    assert.ok(
      Object.values(outputs).some((output) => optional in output.inputs),
      `${optional} was missing from production output`,
    );
  }
  const dynamic = Object.entries(outputs).filter(
    ([, output]) => output.entryPoint === "web/src/BrowserVoiceRuntime.tsx",
  );
  assert.equal(dynamic.length, 1);
  console.log(
    JSON.stringify({
      eagerJavaScriptBytes: [...eager].reduce((size, name) => size + outputs[name].bytes, 0),
      optionalRuntimeBytes: dynamic[0][1].bytes,
    }),
  );
});

test("SDK exports require an explicit protocol version", () => {
  for (const [family, versions] of [
    ["gomode", ["v1"]],
    ["mcp", ["v1"]],
    ["oauth", ["v1"]],
    ["voicegateway", ["v1", "v2"]],
  ]) {
    for (const version of versions) {
      const resolved = import.meta.resolve(`@maruel/gomode/sdk/${family}/${version}/types.gen`);
      assert.equal(resolved, new URL(`../sdk/${family}/ts/${version}/types.gen`, import.meta.url).href);
    }
    assert.throws(() => import.meta.resolve(`@maruel/gomode/sdk/${family}/types.gen`), {
      code: "ERR_PACKAGE_PATH_NOT_EXPORTED",
    });
  }
});

test("shared voice catalogs have no runtime or UI dependency", async () => {
  const result = await build({
    absWorkingDir: root,
    entryPoints: [
      "web/src/VoiceMessages.ts",
      "web/src/voiceMessages/de.ts",
      "web/src/voiceMessages/es.ts",
      "web/src/voiceMessages/fr.ts",
    ],
    outdir: "e2e/browser-integration/catalogs",
    bundle: true,
    format: "esm",
    write: false,
    metafile: true,
  });
  assert.deepEqual(Object.keys(result.metafile.inputs).sort(), [
    "web/src/VoiceMessages.ts",
    "web/src/voiceMessages/de.ts",
    "web/src/voiceMessages/es.ts",
    "web/src/voiceMessages/fr.ts",
  ]);
});

test("the public network observer has no voice or UI runtime dependency", async () => {
  const result = await build({
    absWorkingDir: root,
    stdin: {
      contents: 'export { observeNetworkChanges } from "@maruel/gomode/web/NetworkChanges";',
      sourcefile: "host-network-observer.ts",
      resolveDir: root,
      loader: "ts",
    },
    bundle: true,
    format: "esm",
    write: false,
    metafile: true,
  });
  assert.deepEqual(Object.keys(result.metafile.inputs).sort(), [
    "host-network-observer.ts",
    "web/src/NetworkChanges.ts",
  ]);
});
