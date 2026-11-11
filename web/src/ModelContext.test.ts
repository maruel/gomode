// Tests that server-owned resource texts reach the model verbatim within whole-text bounds.

import { it } from "node:test";
import { expect } from "../tests/expect";
import { modelContextText } from "./ModelContext";

it("sends multilingual server texts verbatim, separated by blank lines", () => {
  const texts = ['{"選択": ["資料😀"]}', "Aucun dossier sélectionné.", "[false,null,42]"];
  expect(modelContextText(texts)).toBe(texts.join("\n\n"));
});

it("drops blank and oversized texts whole and bounds the aggregate", () => {
  expect(modelContextText(["  ", '"'.repeat(7000), "kept"])).toBe("kept");
  const text = modelContextText(Array.from({ length: 16 }, (_, i) => `${i}:${"資料".repeat(2000)}`));
  expect(text.length).toBeLessThanOrEqual(10000);
  expect(text.split("\n\n").every((section) => section.endsWith("資料"))).toBe(true);
});
