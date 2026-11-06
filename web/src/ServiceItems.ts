// Go Mode service-item parsing builds bounded browser voice baselines and reference/state updates.

const GO_MODE_ITEMS_RESOURCE_URI = "gomode://items";
// Keep these setup-context limits and formatting aligned with Android's ServiceResources.kt.
const MAX_INITIAL_SERVICE_CONTEXT_CHARS = 4000;
const MAX_INITIAL_SERVICE_ITEMS = 20;

interface ServiceItem {
  id: string;
  reference: string | null;
  title: string;
  state: string;
  needsAttention: boolean;
}

interface ServiceItems {
  items: ServiceItem[];
  moreItemsHint: string | null;
  omittedCount: number;
}

export { GO_MODE_ITEMS_RESOURCE_URI };

/** Parse and bound a host-neutral Go Mode item resource for session setup. */
export function initialServiceContext(text: string | null): string {
  if (text === null) return "No visible service items.";
  let source: ServiceItems;
  try {
    source = parseServiceItems(text);
  } catch {
    // Service context is advisory: malformed or version-skewed resource data
    // must not prevent the otherwise independent voice transport from starting.
    return "No visible service items.";
  }
  const limit = Math.min(source.items.length, MAX_INITIAL_SERVICE_ITEMS);
  let included = 0;
  for (let count = 1; count <= limit; count++) {
    const candidate = formatServiceItems(source, count);
    if (candidate.length > MAX_INITIAL_SERVICE_CONTEXT_CHARS) break;
    included = count;
  }
  return formatServiceItems(source, included);
}

/** Compare authoritative item snapshots without treating context changes as spoken alerts. */
export function serviceItemVoiceChanges(previous: string | null, current: string | null): string | null {
  const oldItems = previous === null ? [] : parseServiceItems(previous).items;
  const source = current === null ? { items: [], moreItemsHint: null, omittedCount: 0 } : parseServiceItems(current);
  const prior = new Map(oldItems.map((item) => [item.id, item]));
  const currentIDs = new Set(source.items.map((item) => item.id));
  const lines: string[] = [];
  for (const item of source.items) {
    const old = prior.get(item.id);
    if (old !== undefined && JSON.stringify(old) === JSON.stringify(item)) continue;
    const reference = item.reference === null || item.reference === "" ? "" : `${item.reference}: `;
    const changed = old?.reference && old.reference !== item.reference ? ` (previously ${old.reference})` : "";
    lines.push(
      `- ${reference}${item.title} [${item.id}]${changed}${item.state ? ` (${item.state}${item.needsAttention ? ", needs attention" : ""})` : ""}`,
    );
  }
  for (const item of oldItems) {
    if (!currentIDs.has(item.id)) lines.push(`- Removed from the visible snapshot: ${item.title} [${item.id}]`);
  }
  if (lines.length === 0) return null;
  const heading = "Service item updates (quiet context; references replace previous references):";
  const included: string[] = [];
  let chars = heading.length;
  for (const line of lines) {
    if (included.length >= MAX_INITIAL_SERVICE_ITEMS || chars + line.length + 80 > MAX_INITIAL_SERVICE_CONTEXT_CHARS)
      break;
    included.push(line);
    chars += line.length + 1;
  }
  if (included.length < lines.length) included.push("- … more service updates omitted; read current service items.");
  return `${heading}\n${included.join("\n")}`;
}

function parseServiceItems(text: string): ServiceItems {
  if (text.length > 1024 * 1024) throw new Error("gomode://items exceeds the snapshot limit");
  const value: unknown = JSON.parse(text);
  if (!isObject(value) || !Array.isArray(value.items)) {
    throw new Error("gomode://items must be an object with an items array");
  }
  const omittedCount = value.omittedCount === undefined ? 0 : value.omittedCount;
  if (typeof omittedCount !== "number" || !Number.isSafeInteger(omittedCount) || omittedCount < 0) {
    throw new Error("gomode://items omittedCount must be a non-negative integer");
  }
  const moreItemsHint = value.moreItemsHint;
  if (moreItemsHint !== undefined && typeof moreItemsHint !== "string") {
    throw new Error("gomode://items moreItemsHint must be a string");
  }
  const items = value.items.map(parseServiceItem);
  if (new Set(items.map((item) => item.id)).size !== items.length) throw new Error("gomode://items has duplicate IDs");
  return {
    items,
    moreItemsHint: moreItemsHint?.slice(0, 512) ?? null,
    omittedCount,
  };
}

function parseServiceItem(value: unknown, index: number): ServiceItem {
  if (!isObject(value)) {
    throw new Error(`gomode://items item ${index} must be an object`);
  }
  if (typeof value.title !== "string" || value.title.trim() === "") {
    throw new Error(`gomode://items item ${index} must have a title`);
  }
  if (value.reference !== undefined && typeof value.reference !== "string") {
    throw new Error(`gomode://items item ${index} reference must be a string`);
  }
  if (value.state !== undefined && typeof value.state !== "string") {
    throw new Error(`gomode://items item ${index} state must be a string`);
  }
  if (value.needsAttention !== undefined && typeof value.needsAttention !== "boolean") {
    throw new Error(`gomode://items item ${index} needsAttention must be a boolean`);
  }
  if (typeof value.id !== "string" || value.id === "") throw new Error(`gomode://items item ${index} must have an ID`);
  return {
    id: value.id,
    reference: value.reference ?? null,
    title: value.title,
    state: value.state ?? "",
    needsAttention: value.needsAttention ?? false,
  };
}

function formatServiceItems(source: ServiceItems, count: number): string {
  if (source.items.length === 0 && source.omittedCount === 0) {
    return "No visible service items.";
  }
  const lines = source.items.slice(0, count).map((item) => {
    const reference = item.reference === null || item.reference === "" ? "" : `${item.reference}: `;
    const state = item.state === "" ? "" : ` (${item.state}${item.needsAttention ? ", needs attention" : ""})`;
    return `- ${reference}${item.title}${state}`;
  });
  const omitted = source.omittedCount + source.items.length - count;
  if (omitted > 0) {
    const hint = source.moreItemsHint === null || source.moreItemsHint === "" ? "" : ` ${source.moreItemsHint}`;
    lines.push(`- … ${omitted} more items omitted.${hint}`);
  }
  return `Current service items:\n${lines.join("\n")}`;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
