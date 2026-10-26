// Serve the shared task scene with the production browser voice overlay for documentation captures.
import { transformAsync } from "@babel/core";
import { build } from "esbuild";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const usage =
  "Usage: node scripts/voice_preview.mjs [--help]\nServe the voice layout fixture at http://127.0.0.1:4179.";
const args = process.argv.slice(2);
if (args.length > 0) {
  const help = args.length === 1 && ["-h", "--help"].includes(args[0]);
  if (help) console.log(usage);
  else console.error(`Invalid arguments.\n${usage}`);
  process.exit(help ? 0 : 1);
}
const root = fileURLToPath(new URL("../", import.meta.url));
const output = path.join(root, "e2e/voice-preview/build");
const result = await build({
  write: false,
  entryPoints: [path.join(root, "e2e/voice-preview/entry.tsx")],
  bundle: true,
  outdir: output,
  entryNames: "app",
  assetNames: "assets/[name]-[hash]",
  conditions: ["browser"],
  loader: { ".css": "local-css", ".svg": "file" },
  plugins: [
    {
      name: "solid",
      setup(builder) {
        builder.onLoad({ filter: /\.tsx$/ }, async ({ path: filename }) => {
          const result = await transformAsync(await readFile(filename, "utf8"), {
            filename,
            configFile: false,
            babelrc: false,
            presets: ["@babel/preset-typescript", "babel-preset-solid"],
          });
          if (!result?.code) throw new Error(`No transformed source: ${filename}`);
          return { contents: result.code, loader: "js", resolveDir: path.dirname(filename) };
        });
      },
    },
  ],
});
const page = (await readFile(path.join(root, "e2e/voice-preview/scene/host.html"), "utf8")).replace(
  "<!-- VOICE_PREVIEW -->",
  '<div id="voice-root"></div><link rel="stylesheet" href="/app.css"><script type="module" src="/app.js"></script>',
);
const assets = new Map(
  result.outputFiles.map((file) => [`/${path.relative(output, file.path).split(path.sep).join("/")}`, file.contents]),
);
const server = createServer((req, res) => {
  const pathname = new URL(req.url, "http://127.0.0.1:4179").pathname;
  if (pathname === "/") {
    res.setHeader("Content-Type", "text/html; charset=utf-8");
    res.end(page);
    return;
  }
  const body = assets.get(pathname);
  if (!body) {
    res.writeHead(404).end();
    return;
  }
  const types = { ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml" };
  res.setHeader("Content-Type", types[path.extname(pathname)] ?? "application/octet-stream");
  res.end(body);
});
server.listen(4179, "127.0.0.1", () => console.log(usage.split("\n")[1]));
