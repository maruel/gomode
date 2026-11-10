// Loads advertised opaque model resources and bounds whole sections and Unicode text.

import { mcpClient, type McpToolCallOptions } from "./McpClient";
import { MODEL_RESOURCE_BUDGET, MODEL_CONTEXT_BUDGET } from "./ConversationContext";

export function boundedModelText(text: string, limit: number): string {
  let end = Math.min(text.length, limit);
  const last = text.charCodeAt(end - 1);
  if (end < text.length && last >= 0xd800 && last <= 0xdbff) end--;
  return text.slice(0, end);
}

/** Preserve the whole public resource or report omission; never rewrite server fields. */
export function modelResourceContext(uri: string, text: string | null | undefined | Error, limit: number): string {
  const value =
    typeof text === "string"
      ? { resource: uri, status: "known", content: text }
      : { resource: uri, status: text === null ? "absent" : text === undefined ? "unread" : "error" };
  const encoded = JSON.stringify(value);
  return encoded.length <= limit ? encoded : JSON.stringify({ resource: uri, status: "omitted" });
}

/** Bound the complete set without truncating opaque server payloads. */
export function modelContextText(resources: Array<readonly [string, string | null | undefined | Error]>): string {
  const sections: string[] = [];
  let remaining = MODEL_CONTEXT_BUDGET - 64;
  let omitted = 0;
  for (const [uri, text] of resources) {
    const section = modelResourceContext(uri, text, Math.min(MODEL_RESOURCE_BUDGET, remaining));
    if (section.length + 1 > remaining) {
      omitted++;
      continue;
    }
    sections.push(section);
    remaining -= section.length + 1;
  }
  if (omitted) sections.push(JSON.stringify({ omittedResourceCount: omitted }));
  return sections.join("\n");
}

/** Initial reads are advisory and fail independently of voice transport setup. */
export async function readInitialModelContext(options: McpToolCallOptions): Promise<string> {
  try {
    const { resources } = await mcpClient.listModelResources(undefined, options);
    return modelContextText(
      await Promise.all(
        resources.map(async ({ uri }) => {
          try {
            return [uri, await mcpClient.readTextResource(uri, undefined, options)] as const;
          } catch {
            return [uri, new Error("Model resource read failed")] as const;
          }
        }),
      ),
    );
  } catch {
    return JSON.stringify({ status: "error" });
  }
}
