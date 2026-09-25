// Parses MCP JSON-RPC notifications out of a Server-Sent Events stream.

/** A JSON-RPC notification delivered over an MCP subscription stream. */
export interface McpNotification {
  method: string;
  params?: Record<string, unknown>;
}

/**
 * Reads an SSE body until it ends, calling onNotification for every JSON-RPC
 * notification frame. Comment frames such as keep-alives and frames that are not
 * JSON-RPC notifications are ignored; a stream error rejects so the caller can
 * reconnect.
 */
export async function readMcpNotificationStream(
  body: ReadableStream<Uint8Array>,
  onNotification: (notification: McpNotification) => void,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });
      let index = buffer.indexOf("\n\n");
      while (index >= 0) {
        deliverFrame(buffer.slice(0, index), onNotification);
        buffer = buffer.slice(index + 2);
        index = buffer.indexOf("\n\n");
      }
    }
  } finally {
    reader.releaseLock();
  }
}

function deliverFrame(frame: string, onNotification: (notification: McpNotification) => void): void {
  const data: string[] = [];
  for (const raw of frame.split("\n")) {
    const line = raw.endsWith("\r") ? raw.slice(0, -1) : raw;
    // A leading colon marks an SSE comment, which is how keep-alives arrive.
    if (!line.startsWith("data:")) continue;
    data.push(line.slice(5).replace(/^ /, ""));
  }
  if (data.length === 0) return;

  let parsed: unknown;
  try {
    parsed = JSON.parse(data.join("\n"));
  } catch {
    return;
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return;
  const record = parsed as Record<string, unknown>;
  if (typeof record.method !== "string") return;
  const params =
    typeof record.params === "object" && record.params !== null && !Array.isArray(record.params)
      ? (record.params as Record<string, unknown>)
      : undefined;
  onNotification({ method: record.method, params });
}
