// Registers host-owned frontend voice tools for browser and native voice sessions.

import type { ToolDeclaration } from "../../sdk/voicegateway/ts/v1/types.gen";

export interface FrontendVoiceTool {
  declaration: ToolDeclaration;
  execute: (args: Record<string, unknown>) => Record<string, unknown>;
}

declare global {
  interface Window {
    gomodeVoiceTools?: { postMessage: (message: string) => void };
    executeGoModeVoiceTool?: typeof executeFrontendVoiceTool;
  }
}

const tools = new Map<string, FrontendVoiceTool>();

export function frontendVoiceToolDeclarations(): ToolDeclaration[] {
  return Array.from(tools.values(), (tool) => tool.declaration);
}

export function executeFrontendVoiceTool(name: string, args: Record<string, unknown>): Record<string, unknown> {
  const tool = tools.get(name);
  if (!tool) return { error: `Frontend voice tool "${name}" is unavailable.` };
  try {
    return tool.execute(args);
  } catch (error: unknown) {
    return { error: error instanceof Error ? error.message : "Frontend voice tool failed." };
  }
}

// Register before connecting voice. Tools execute synchronously in their owning
// page; native voice transports declarations and results without knowing host UI.
export function registerFrontendVoiceTool(tool: FrontendVoiceTool): () => void {
  const name = tool.declaration.name;
  if (name === "hang_up" || tools.has(name)) throw new Error(`Voice tool "${name}" is already reserved.`);
  tools.set(name, tool);
  window.executeGoModeVoiceTool = executeFrontendVoiceTool;
  publish();
  return () => {
    if (tools.get(name) !== tool) return;
    tools.delete(name);
    publish();
    if (tools.size === 0) delete window.executeGoModeVoiceTool;
  };
}

function publish(): void {
  window.gomodeVoiceTools?.postMessage(JSON.stringify(frontendVoiceToolDeclarations()));
}
