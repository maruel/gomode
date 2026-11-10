// MCP request metadata preserves typed fields and arbitrary extension keys.
import type { ClientCapabilities, Implementation, OpenAIUserLocation } from "./types.gen";

export interface RequestMeta {
  "io.modelcontextprotocol/protocolVersion": string;
  "io.modelcontextprotocol/clientInfo": Implementation;
  "io.modelcontextprotocol/clientCapabilities": ClientCapabilities;
  progressToken?: unknown;
  "io.modelcontextprotocol/logLevel"?: string;
  "openai/locale"?: string;
  "openai/organization"?: string;
  "openai/session"?: string;
  "openai/subject"?: string;
  "openai/userAgent"?: string;
  "openai/userLocation"?: OpenAIUserLocation;
  [key: string]: unknown;
}
