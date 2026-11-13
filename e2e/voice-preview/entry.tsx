// Render the production browser voice overlay with a controlled conversation.
import { createSignal } from "solid-js";
import { render } from "solid-js/web";
import { defaultVoiceMessages } from "../../web/src/VoiceMessages";
import VoiceOverlay from "../../web/src/VoiceOverlay";
import { voiceSession, type TranscriptEntry } from "../../web/src/VoiceSession";
import { voiceMessages as de } from "../../web/src/voiceMessages/de";
import { voiceMessages as es } from "../../web/src/voiceMessages/es";
import { voiceMessages as fr } from "../../web/src/voiceMessages/fr";
import scene from "./scene/conversation.json";
import voiceChoices from "./scene/voice-choices.json";

// ?theme=dark applies the host's dark tokens; ?lang=de|es|fr renders a shipped translation.
const params = new URLSearchParams(location.search);
if (params.get("theme") === "dark") document.documentElement.dataset.theme = "dark";
const translations = { de, es, fr };
const lang = params.get("lang");
const translated = lang === "de" || lang === "es" || lang === "fr" ? lang : null;
const messages = translated === null ? defaultVoiceMessages : translations[translated];
document.documentElement.lang = translated ?? "en";

// The Android documentation fixture serves the same choices, so both platforms' captures compare.
// ?choices=loading keeps discovery pending; ?choices=error fails it, as an unreachable gateway does.
const choicesScene = new URLSearchParams(location.search).get("choices");
const originalFetch = window.fetch.bind(window);
window.fetch = (input, init) =>
  (input instanceof Request ? input.url : String(input)).endsWith("/voice/choices")
    ? choicesScene === "loading"
      ? new Promise<Response>(() => {})
      : choicesScene === "error"
        ? Promise.reject(new TypeError("Failed to fetch"))
        : Promise.resolve(
            new Response(JSON.stringify(voiceChoices), { headers: { "Content-Type": "application/json" } }),
          )
    : originalFetch(input, init);

const state = new URLSearchParams(location.search).get("state");
// ?state=locked starts a session after discovery, so settings show loaded choices locked.
const idle = state === "idle" || state === "locked";
voiceSession.setState((state) => ({ ...state, connected: !idle }));
if (state === "locked")
  void voiceSession.loadVoiceChoices().then(() => voiceSession.setState((state) => ({ ...state, connected: true })));
const root = document.getElementById("voice-root");
if (!root) throw new Error("Missing voice fixture root");
const [first, second] = scene.servers;
if (!first || !second || scene.servers.length !== 2) throw new Error("The fixture requires two named servers");
const [server, setServer] = createSignal(first);
const nextServer = () => (server() === first ? second : first);
function selectNextServer() {
  setServer(nextServer());
  const heading = document.querySelector("h1");
  if (!heading) throw new Error("Missing fixture server heading");
  heading.textContent = server();
}
render(
  () => <VoiceOverlay messages={messages} nextServer={{ name: nextServer(), onSelect: selectNextServer }} />,
  root,
);

// Controlled retention scene, advanced through fixture controls rather than DOM mutation.
const retention = new URLSearchParams(location.search).get("history") === "retention";
const retentionCalls = Array.from({ length: 40 }, (_, index) => ({
  sequence: index + 1,
  name: index < 2 ? "duplicate" : `tool_${index + 1}`,
}));
let historyOffset = 0;
if (retention) {
  const controls = document.createElement("div");
  controls.className = "fixture-controls";
  controls.setAttribute("role", "group");
  controls.setAttribute("aria-label", "Tool history fixture");
  for (const count of [1, 2]) {
    const button = document.createElement("button");
    button.textContent = count === 1 ? "Append tool call" : "Append two tool calls";
    button.addEventListener("click", () => {
      historyOffset = Math.min(historyOffset + count, retentionCalls.length - 32);
      const entries = retentionCalls.slice(historyOffset, historyOffset + 32);
      voiceSession.setState((state) => ({ ...state, toolHistory: entries, activeTool: entries.at(-1)?.name ?? null }));
    });
    controls.append(button);
  }
  document.querySelector("header")?.after(controls);
}

// Controlled text growth for reopening/follow-tail checks through a real fixture action.
const wrapping = new URLSearchParams(location.search).get("layout") === "wrapping";
if (wrapping) {
  const controls = document.createElement("div");
  controls.className = "fixture-controls";
  const append = document.createElement("button");
  append.textContent = "Append reply";
  append.addEventListener("click", () =>
    voiceSession.setState((state) => ({
      ...state,
      transcript: [
        ...state.transcript,
        {
          kind: "transcript",
          speaker: "assistant",
          final: true,
          text: "Updated.",
          responseID: "appended",
          responseUsage: { outputTokens: 1 },
        },
      ],
    })),
  );
  controls.append(append);
  document.querySelector("header")?.after(controls);
}

// Populate after mount to exercise the empty-to-populated transcript lifecycle.
queueMicrotask(() => {
  if (idle) return;
  voiceSession.setState((state) => ({
    ...state,
    connected: true,
    activeTool: retention ? "tool_32" : scene.tool,
    toolHistory: retention
      ? retentionCalls.slice(0, 32)
      : [
          { sequence: 1, name: "task_list" },
          { sequence: 2, name: "project_documents" },
          { sequence: 3, name: scene.tool },
        ],
    usage: {
      totals: { inputTokens: 1234, outputTokens: 64, cachedTokens: 128, reasoningTokens: 8 },
      latest: {
        kind: "session.usage",
        sessionID: "preview",
        provider: "llamacpp",
        model: "LFM3-audio",
        totals: { inputTokens: 1234, outputTokens: 64, cachedTokens: 128, reasoningTokens: 8 },
        lastResponse: { inputTokens: 234, outputTokens: 0, cachedTokens: 0 },
      },
    },
    transcript: wrapping
      ? [
          {
            kind: "transcript",
            speaker: "user",
            final: true,
            text: "Compare the launch brief with the prototype feedback, then check the accessibility notes before tomorrow's design review. ".repeat(
              8,
            ),
          },
          {
            kind: "transcript",
            speaker: "assistant",
            final: true,
            text: "Done.",
            responseID: "compact",
            responseUsage: { inputTokens: 1000, outputTokens: 52, cachedTokens: 128, reasoningTokens: 8 },
          },
        ]
      : [
          ...scene.transcript.flatMap((entry, index): TranscriptEntry[] => {
            if (entry.speaker !== "user" && entry.speaker !== "assistant") throw new Error("Invalid fixture speaker");
            const row: TranscriptEntry = {
              kind: "transcript",
              speaker: entry.speaker,
              text: entry.text,
              final: entry.final,
            };
            if (entry.speaker === "user") return [row];
            row.responseID = `preview-${index}`;
            row.responseUsage =
              index === 1
                ? { inputTokens: 1000, outputTokens: 52, cachedTokens: 128, reasoningTokens: 8 }
                : { inputTokens: 234, outputTokens: 0, cachedTokens: 0 };
            return index === 1
              ? [
                  {
                    kind: "reasoning",
                    speaker: "assistant",
                    text: "Compare the deadlines before choosing the next task.",
                    final: true,
                    responseID: row.responseID,
                  },
                  row,
                ]
              : [row];
          }),
          { kind: "transcript", speaker: "user", text: "Is anything else due today?", final: true },
          {
            kind: "transcript",
            speaker: "assistant",
            text: "The design review is tomorrow.",
            final: true,
            responseID: "preview-unknown-input",
            responseUsage: { outputTokens: 12 },
          },
        ],
  }));
});

// Controlled speech snapshots exercise the production renderer without a remote model.
window.addEventListener("message", (event: MessageEvent<unknown>) => {
  if (event.source !== window || event.origin !== window.location.origin) return;
  const snapshot = event.data as { kind?: string; text?: unknown };
  if (typeof snapshot.text !== "string") return;
  const text = snapshot.text;
  if (snapshot.kind === "preview.transcript.draft") {
    voiceSession.setState((state) => ({
      ...state,
      activeTool: null,
      transcriptDraft: { id: "preview-live", text },
    }));
  } else if (snapshot.kind === "preview.transcript.final") {
    voiceSession.setState((state) => ({
      ...state,
      transcriptDraft: null,
      transcript: [{ kind: "transcript", speaker: "user", text, final: true }],
    }));
  } else if (snapshot.kind === "preview.transcript.cancel") {
    voiceSession.setState((state) => ({ ...state, transcriptDraft: null }));
  }
});
