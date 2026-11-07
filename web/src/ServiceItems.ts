// Go Mode service items provide bounded browser baselines, reference/state updates, and advisory focus.

const GO_MODE_ITEMS_RESOURCE_URI = "gomode://items";
// Keep these setup-context limits and formatting aligned with Android's ServiceResources.kt.
const MAX_INITIAL_SERVICE_CONTEXT_CHARS = 4000;
const MAX_INITIAL_SERVICE_ITEMS = 20;

interface ServiceItem {
  id: string;
  active: boolean | null;
  reference: string | null;
  title: string;
  state: string;
  needsAttention: boolean;
}

interface ActiveServiceItem {
  id: string;
  reference: string | null;
}

interface ServiceItems {
  activeItem: ActiveServiceItem | null;
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

/** Bound an authoritative replacing snapshot without treating context as a spoken alert. */
export function serviceItemSnapshotContext(text: string | null): string {
  const source = text === null ? { items: [], moreItemsHint: null, omittedCount: 0 } : parseServiceItems(text);
  const lines = source.items.map((item) => {
    const reference = item.reference === null || item.reference === "" ? "" : `${item.reference}: `;
    return `- ${reference}${item.title} [${item.id}]${item.state ? ` (${item.state}${item.needsAttention ? ", needs attention" : ""})` : ""}`;
  });
  if (lines.length === 0 && source.omittedCount === 0) return "No visible service items.";
  const heading = "Service item updates (quiet context; references replace previous references):";
  const included: string[] = [];
  let chars = heading.length;
  for (const line of lines) {
    if (included.length >= MAX_INITIAL_SERVICE_ITEMS || chars + line.length + 80 > MAX_INITIAL_SERVICE_CONTEXT_CHARS)
      break;
    included.push(line);
    chars += line.length + 1;
  }
  if (included.length < lines.length || source.omittedCount > 0)
    included.push("- … more service updates omitted; read current service items.");
  if (source.moreItemsHint !== null) {
    const hint = `More items: ${source.moreItemsHint}`;
    if (heading.length + included.join("\n").length + hint.length + 2 <= MAX_INITIAL_SERVICE_CONTEXT_CHARS)
      included.push(hint);
  }
  return `${heading}\n${included.join("\n")}`;
}

/** Return only valid advisory focus; absence and malformed snapshots withdraw prior focus. */
export function serviceItemFocusContext(text: string | null): string | null {
  if (text === null) return null;
  try {
    const active = parseServiceItems(text).activeItem;
    return active === null ? null : formatActiveServiceItem(active);
  } catch {
    return null;
  }
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
    activeItem: activeServiceItem(value, items),
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
    active: value.active === undefined ? false : typeof value.active === "boolean" ? value.active : null,
    reference: value.reference ?? null,
    title: value.title,
    state: value.state ?? "",
    needsAttention: value.needsAttention ?? false,
  };
}

function activeServiceItem(source: Record<string, unknown>, items: ServiceItem[]): ActiveServiceItem | null {
  if (items.some((item) => item.active === null)) return null;
  const marked = items.filter((item) => item.active);
  if (marked.length > 1) return null;
  const topID = source.activeTaskId;
  const number = source.activeTaskNumber;
  if (number !== undefined && (typeof number !== "number" || !Number.isSafeInteger(number) || number <= 0)) return null;
  if (topID === undefined && number !== undefined) return null;
  const id = topID === undefined ? marked[0]?.id : topID;
  if (typeof id !== "string" || id.trim() === "" || id.length > 512) return null;
  if (marked.some((item) => item.id !== id)) return null;
  const matches = items.filter((item) => item.id === id);
  if (matches.length > 1) return null;
  return { id, reference: matches[0]?.reference || (number === undefined ? null : `#${number}`) };
}

function formatServiceItems(source: ServiceItems, count: number): string {
  const active = source.activeItem;
  const focus = active === null ? "" : `${formatActiveServiceItem(active)}\n`;
  if (source.items.length === 0 && source.omittedCount === 0) {
    return `${focus}No visible service items.`;
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
  return `${focus}Current service items:\n${lines.join("\n")}`;
}

function formatActiveServiceItem(active: ActiveServiceItem): string {
  const label = active.reference ? `${active.reference.slice(0, 512)} (${active.id})` : active.id;
  return `Active service item: ${label}.`;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
