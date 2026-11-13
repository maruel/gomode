// Build or check the standalone frontend's deterministic, precompressed embedded assets.
import { transformAsync } from "@babel/core";
import { build } from "esbuild";
import { brotliCompressSync, constants } from "node:zlib";
import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const usage =
  "Usage: node scripts/build_frontend.mjs [--write | --help]\nCheck embedded frontend assets; --write regenerates them.";
const args = process.argv.slice(2);
if (args.length > 0 && !(args.length === 1 && args[0] === "--write")) {
  const help = args.length === 1 && ["-h", "--help"].includes(args[0]);
  (help ? console.log : console.error)(help ? usage : `Invalid arguments.\n${usage}`);
  process.exit(help ? 0 : 1);
}
const root = fileURLToPath(new URL("../", import.meta.url));
const output = path.join(root, "frontend/dist");
const result = await build({
  absWorkingDir: root,
  entryPoints: ["frontend/src/main.tsx"],
  outdir: output,
  entryNames: "assets/[name]-[hash]",
  chunkNames: "assets/[name]-[hash]",
  assetNames: "assets/[name]-[hash]",
  bundle: true,
  minify: true,
  format: "esm",
  splitting: true,
  conditions: ["browser"],
  define: { "process.env.NODE_ENV": '"production"' },
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
const assets = new Map(
  result.outputFiles.map((file) => [path.relative(output, file.path).split(path.sep).join("/"), file.contents]),
);
const entry = Object.entries(result.metafile.outputs).find(([, value]) => value.entryPoint === "frontend/src/main.tsx");
if (!entry) throw new Error("Missing frontend entry point");
// esbuild does not load the CSS belonging to dynamic JS chunks automatically.
const links = [...assets.keys()]
  .filter((name) => name.endsWith(".css"))
  .sort()
  .map((name) => `<link rel="stylesheet" href="/${name}" />`);
links.push(
  `<script type="module" src="/${path.relative(output, path.resolve(root, entry[0])).split(path.sep).join("/")}"></script>`,
);
assets.set(
  "index.html",
  Buffer.from(
    (await readFile(path.join(root, "frontend/index.html"), "utf8")).replace("<!-- ASSETS -->", links.join("\n    ")),
  ),
);
const compressed = new Map(
  [...assets].map(([name, body]) => [
    name + ".br",
    brotliCompressSync(body, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }),
  ]),
);
if (args[0] === "--write") {
  await rm(output, { recursive: true, force: true });
  for (const [name, body] of compressed) {
    const filename = path.join(output, name);
    await mkdir(path.dirname(filename), { recursive: true });
    await writeFile(filename, body);
  }
} else {
  const existing = new Set();
  for (const file of await readdir(output, { recursive: true, withFileTypes: true })) {
    if (file.isFile())
      existing.add(path.relative(output, path.join(file.parentPath, file.name)).split(path.sep).join("/"));
  }
  for (const [name, body] of compressed) {
    if (!existing.delete(name) || !body.equals(await readFile(path.join(output, name))))
      throw new Error("Embedded frontend is stale; run make fix");
  }
  if (existing.size) throw new Error("Embedded frontend has obsolete files; run make fix");
}
