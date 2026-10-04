// Tests frontend voice tool registration, native publication, errors, and disposal.

import { it } from "node:test";
import { expect, vi } from "../tests/expect";
import { executeFrontendVoiceTool, registerFrontendVoiceTool } from "./FrontendVoiceTools";

it("publishes native declarations and removes handlers on disposal", () => {
  const postMessage = vi.fn();
  window.gomodeVoiceTools = { postMessage };
  const execute = vi.fn(() => ({ selected: true }));
  const tool = { declaration: { name: "select_item", description: "Select item", parameters: {} }, execute };
  const unregister = registerFrontendVoiceTool(tool);
  try {
    expect(JSON.parse(postMessage.mock.calls[0]?.[0] as string)).toHaveLength(1);
    expect(window.executeGoModeVoiceTool?.("select_item", { id: "one" })).toEqual({ selected: true });
    expect(execute).toHaveBeenCalledWith({ id: "one" });
    expect(() => registerFrontendVoiceTool(tool)).toThrow(/reserved/);
    expect(() => registerFrontendVoiceTool({ ...tool, declaration: { ...tool.declaration, name: "hang_up" } })).toThrow(
      /reserved/,
    );
  } finally {
    unregister();
    delete window.gomodeVoiceTools;
  }
  expect(window.executeGoModeVoiceTool).toBeUndefined();
  expect(postMessage).toHaveBeenLastCalledWith("[]");
  expect(executeFrontendVoiceTool("select_item", {})).toHaveProperty("error");
});
