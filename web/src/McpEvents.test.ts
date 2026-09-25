// Tests parsing JSON-RPC notifications out of an MCP SSE stream.
import { describe, it } from "node:test";
import { expect } from "../tests/expect";

import { readMcpNotificationStream, type McpNotification } from "./McpEvents";

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });
}

describe("readMcpNotificationStream", () => {
  it("delivers data frames and ignores keep-alive comments", async () => {
    const seen: McpNotification[] = [];
    await readMcpNotificationStream(
      streamOf([
        ": keepalive\n\n",
        'data: {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"mddb://a"}}\n\n',
        ": keepalive\n\n",
        'data: {"jsonrpc":"2.0","method":"notifications/resources/list_changed"}\n\n',
      ]),
      (notification) => seen.push(notification),
    );
    expect(seen).toEqual([
      { method: "notifications/resources/updated", params: { uri: "mddb://a" } },
      { method: "notifications/resources/list_changed", params: undefined },
    ]);
  });

  it("reassembles a frame split across chunks", async () => {
    const seen: McpNotification[] = [];
    await readMcpNotificationStream(
      streamOf([
        'data: {"jsonrpc":"2.0","method":"notifications/resources/upd',
        'ated","params":{"uri":"x"}}\n\n',
      ]),
      (notification) => seen.push(notification),
    );
    expect(seen.map((notification) => notification.params?.uri)).toEqual(["x"]);
  });

  it("skips frames that are not JSON-RPC notifications", async () => {
    const seen: McpNotification[] = [];
    await readMcpNotificationStream(
      streamOf(['data: not json\n\n', 'data: {"jsonrpc":"2.0","id":1,"result":{}}\n\n', ": keepalive\n\n"]),
      (notification) => seen.push(notification),
    );
    expect(seen).toEqual([]);
  });
});
