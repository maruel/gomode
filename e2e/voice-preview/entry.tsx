// Render the production browser voice overlay with a controlled conversation.
import { createSignal } from "solid-js";
import { render } from "solid-js/web";
import VoiceOverlay from "../../web/src/VoiceOverlay";
import { voiceSession, type TranscriptEntry } from "../../web/src/VoiceSession";
import scene from "./scene/conversation.json";

const originalFetch = window.fetch.bind(window);
window.fetch = (input, init) =>
  String(input).endsWith("/voice/choices")
    ? Promise.resolve(
        new Response(
          JSON.stringify({
            choices: [{ id: "preview", label: "Demonstration", transports: ["webrtc"] }],
            defaultChoiceID: "preview",
          }),
          { headers: { "Content-Type": "application/json" } },
        ),
      )
    : originalFetch(input, init);

voiceSession.setState((state) => ({
  ...state,
  connected: true,
  activeTool: scene.tool,
  transcript: scene.transcript.map((entry): TranscriptEntry => {
    if (entry.speaker !== "user" && entry.speaker !== "assistant") throw new Error("Invalid fixture speaker");
    return { ...entry, speaker: entry.speaker };
  }),
}));
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
render(() => <VoiceOverlay nextServer={{ name: nextServer(), onSelect: selectNextServer }} />, root);
