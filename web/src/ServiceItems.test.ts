// Tests for bounded Go Mode service-item context used during browser voice setup.

import { describe, it } from "node:test";
import { readFileSync } from "node:fs";
import { expect } from "../tests/expect";

import { initialServiceContext } from "./ServiceItems";

describe("initialServiceContext", () => {
  it("formats references, attention, and omitted-item guidance", () => {
    const context = initialServiceContext(
      JSON.stringify({
        items: [
          {
            id: "1",
            reference: "Item #3",
            title: "Review plan",
            state: "pending",
            needsAttention: true,
          },
        ],
        moreItemsHint: "Call items_list and follow nextCursor until absent.",
        omittedCount: 4,
      }),
    );

    expect(context).toBe(
      "Current service items:\n" +
        "- Item #3: Review plan (pending, needs attention)\n" +
        "- … 4 more items omitted. Call items_list and follow nextCursor until absent.",
    );
  });

  it("bounds item count and reports locally omitted items", () => {
    const context = initialServiceContext(
      JSON.stringify({
        items: Array.from({ length: 25 }, (_, index) => ({
          id: `${index}`,
          title: `Item ${index}`,
          state: "active",
          needsAttention: false,
        })),
        moreItemsHint: "Use the list tool.",
      }),
    );

    expect(context).toContain("Item 19");
    expect(context).not.toContain("Item 20");
    expect(context).toContain("5 more items omitted. Use the list tool.");
    expect(context.length).toBeLessThanOrEqual(4000);
  });

  it("uses an empty baseline when advertised data is malformed", () => {
    expect(initialServiceContext('{"items":[{"id":"new","title":""}]}')).toBe("No visible service items.");
  });
});

const focusCases = JSON.parse(
  readFileSync(new URL("../../testdata/service-items/active-context.json", import.meta.url), "utf8"),
) as Array<{ name: string; resource: unknown; focus: string | null }>;

describe("advisory active service items", () => {
  for (const vector of focusCases) {
    it(vector.name, () => {
      const context = initialServiceContext(JSON.stringify(vector.resource));
      if (vector.focus === null) expect(context).not.toContain("Active service item:");
      else expect(context.startsWith(vector.focus)).toBe(true);
      expect(context.length).toBeLessThanOrEqual(4000);
      expect(context).toMatch(/Build|No visible service items/);
    });
  }

  it("preserves focus outside the first twenty items without reordering them", () => {
    const context = initialServiceContext(
      JSON.stringify({
        items: Array.from({ length: 25 }, (_, index) => ({
          id: `${index}`,
          title: `Build ${index}`,
          active: index === 24,
        })),
      }),
    );
    expect(context.startsWith("Active service item: 24.")).toBe(true);
    expect(context).toContain("Build 19");
    expect(context).not.toContain("Build 20");
    expect(context).toContain("5 more items omitted.");
  });

  it("accounts for focus and continuation text in the character budget", () => {
    const context = initialServiceContext(
      JSON.stringify({
        items: Array.from({ length: 25 }, (_, index) => ({ id: `${index}`, title: "x".repeat(900) })),
        activeTaskId: "active",
        activeTaskNumber: 99,
        moreItemsHint: "h".repeat(512),
        omittedCount: 5,
      }),
    );
    expect(context.startsWith("Active service item: #99 (active).")).toBe(true);
    expect(context).toContain("27 more items omitted.");
    expect(context.length).toBeLessThanOrEqual(4000);
  });
});
