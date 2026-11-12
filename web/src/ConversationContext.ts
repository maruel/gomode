// Validates private resource ordering and routes opaque server-owned public context.
//
// Clients load the resources a server marks with _meta["com.fghbuild.gomode/modelContext"]: true
// and pass each text to the model verbatim, with no URI, status, or wrapper. A server that marks
// more than MAX_MODEL_RESOURCES fails context loading. A text over MODEL_RESOURCE_BUDGET
// characters, or one that no longer fits MODEL_CONTEXT_BUDGET, is dropped whole; clients never
// truncate or rewrite server text. Unread, failed, absent, and withdrawn resources contribute
// nothing. After setup, a refresh sends only the resources whose text changed, and quiet updates
// never request a model turn. A hosted browser reuses its authenticated screen feed when available.

import type { ConversationContext, ResourceDependency } from "../../sdk/gomode/ts/v1/types.gen";

export type ContextSnapshot = Omit<ConversationContext, "dependencies"> & {
  content: string;
  dependencies: Array<Omit<ResourceDependency, "uris"> & { uris: string[] }>;
};
export const MODEL_CONTEXT_META_KEY = "com.fghbuild.gomode/modelContext";
export const MAX_MODEL_RESOURCES = 16;
export const MODEL_RESOURCE_BUDGET = 6000;
export const MODEL_CONTEXT_BUDGET = 10000;

/** Private result metadata, never domain/model data. */
export function parseContextUpdate(value: unknown): ContextSnapshot {
  if (typeof value !== "object" || value === null) throw new Error("Invalid context metadata");
  const v = value as Record<string, unknown>;
  if (
    typeof v.uri !== "string" ||
    !v.uri ||
    v.uri.length > 1024 ||
    typeof v.epoch !== "string" ||
    !v.epoch ||
    v.epoch.length > 128 ||
    !Number.isSafeInteger(v.version) ||
    (v.version as number) < 0 ||
    typeof v.content !== "string" ||
    v.content.length > 128 * 1024 ||
    !Array.isArray(v.dependencies) ||
    v.dependencies.length > 128
  )
    throw new Error("Invalid context ordering or content");
  const dependencies: ContextSnapshot["dependencies"] = [];
  for (const candidate of v.dependencies as unknown[]) {
    if (typeof candidate !== "object" || candidate === null) throw new Error("Invalid context event routing");
    const dependency = candidate as Record<string, unknown>;
    if (
      !Array.isArray(dependency.uris) ||
      dependency.uris.length < 1 ||
      dependency.uris.length > 50 ||
      dependency.uris.some((uri: unknown) => typeof uri !== "string" || !uri || uri.length > 1024) ||
      (dependency.aspects !== undefined &&
        (!Array.isArray(dependency.aspects) ||
          dependency.aspects.length > 16 ||
          dependency.aspects.some((aspect: unknown) => typeof aspect !== "string" || !aspect || aspect.length > 64)))
    )
      throw new Error("Invalid context event routing");
    dependencies.push({
      uris: [...(dependency.uris as string[])],
      ...(dependency.aspects === undefined ? {} : { aspects: [...(dependency.aspects as string[])] }),
    });
  }
  return { uri: v.uri, epoch: v.epoch, version: v.version as number, content: v.content, dependencies };
}
