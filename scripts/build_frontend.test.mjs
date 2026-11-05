// Verify embedded production HTML includes every stylesheet and references existing compressed assets.
import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { brotliDecompressSync } from "node:zlib";
import { test } from "node:test";
import { JSDOM } from "jsdom";

const dist = fileURLToPath(new URL("../frontend/dist/", import.meta.url));
test("production HTML loads entry JS and all extracted CSS, including lazy voice controls", async () => {
  const html = brotliDecompressSync(await readFile(path.join(dist, "index.html.br"))).toString();
  const dom = new JSDOM(html);
  const scripts = [...dom.window.document.querySelectorAll('script[type="module"][src]')].map((script) =>
    script.getAttribute("src"),
  );
  assert.equal(scripts.length, 1);
  const styles = [...dom.window.document.querySelectorAll('link[rel="stylesheet"]')].map((link) =>
    link.getAttribute("href"),
  );
  const files = await readdir(path.join(dist, "assets"));
  assert.deepEqual(
    styles.sort(),
    files
      .filter((name) => name.endsWith(".css.br"))
      .map((name) => "/assets/" + name.slice(0, -3))
      .sort(),
  );
  for (const url of [...styles, ...scripts]) {
    assert.ok(url.startsWith("/assets/"));
    const contents = brotliDecompressSync(await readFile(path.join(dist, url.slice(1) + ".br")));
    assert.ok(contents.length > 0);
  }
  dom.window.close();
});
