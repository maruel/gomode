// Tests opaque server-owned catalog content and whole-resource bounds.

import { it } from "node:test";
import { expect } from "../tests/expect";
import { modelResourceContext, modelContextText } from "./ModelContext";
const serviceItemsContext = (value: string | null | undefined | Error) =>
  modelResourceContext("fixture://catalog", value, 4000);

it("preserves arbitrary multilingual server shapes without interpreting catalog fields", () => {
  for (const body of [
    '{"選択": ["資料😀"], "_meta": "domain content"}',
    "Aucun dossier sélectionné.",
    "[false,null,42]",
  ]) {
    expect(JSON.parse(serviceItemsContext(body))).toEqual({
      resource: "fixture://catalog",
      status: "known",
      content: body,
    });
  }
});

it("omits whole resources and distinguishes read outcomes without inventing domain state", () => {
  expect(JSON.parse(serviceItemsContext('"'.repeat(4000)))).toEqual({
    resource: "fixture://catalog",
    status: "omitted",
  });
  for (const [value, status] of [
    [undefined, "unread"],
    [null, "absent"],
    [new Error("private"), "error"],
  ] as const)
    expect(JSON.parse(serviceItemsContext(value))).toEqual({ resource: "fixture://catalog", status });
  expect(JSON.parse(serviceItemsContext("[]")).content).toBe("[]");
});

it("bounds the aggregate while retaining complete opaque resource envelopes", () => {
  const resources = Array.from({ length: 16 }, (_, i) => [`fixture://resource/${i}`, "資料".repeat(2000)] as const);
  const text = modelContextText(resources);
  expect(text.length).toBeLessThanOrEqual(16000);
  const sections = text.split("\n").map((line) => JSON.parse(line));
  expect(sections.every((section) => section.status === "known" || section.status === "omitted")).toBe(true);
  expect(sections.some((section) => section.status === "omitted")).toBe(true);
});
