// Live voice testing with a visible frontend tool result and the shared production controls.
import { createSignal, onCleanup } from "solid-js";
import BrowserVoiceShell from "../../web/src/BrowserVoiceShell";
import { createFrontendPreparationClient } from "../../web/src/FrontendVoiceTools";
import styles from "./App.module.css";

export default function App() {
  const [note, setNote] = createSignal("");
  const identity = createFrontendPreparationClient().publishAuth({ authChanged: true, nativeAccess: true });
  const scope = identity.createScope();
  scope.setTools([
    {
      declaration: {
        name: "set_test_note",
        description: "Replace the visible test note on this browser page. The note lasts until the page is reloaded.",
        parameters: {
          type: "object",
          properties: { text: { type: "string", maxLength: 2000 } },
          required: ["text"],
          additionalProperties: false,
        },
      },
      execute(args) {
        if (typeof args.text !== "string" || Array.from(args.text).length > 2000)
          throw new Error("Provide a note of at most 2000 characters.");
        setNote(args.text);
        return { text: args.text };
      },
    },
  ]);
  scope.setDataReady(true);
  onCleanup(() => identity.dispose());

  return (
    <div class={styles.app}>
      <main class={styles.main}>
        <header>
          <p class={styles.eyebrow}>Go Mode</p>
          <h1>Voice Gateway</h1>
          <p>Test a voice conversation directly with your running gateway.</p>
        </header>
        <section aria-labelledby="test-note-title" class={styles.note}>
          <h2 id="test-note-title">Test note</h2>
          <p class={styles.hint}>Try saying “Set the test note to hello world.”</p>
          <p role="status" aria-label="Test note" class={styles.value}>
            {note() || "Your note will appear here."}
          </p>
          <button type="button" disabled={note() === ""} onClick={() => setNote("")}>
            Clear note
          </button>
        </section>
        <details class={styles.help}>
          <summary>What can I test?</summary>
          <p>
            Choose a voice backend in the controls below, then connect. The same controls are used in caic and mddb.
          </p>
          <p>
            Ask the assistant to echo a phrase to test a server MCP tool, or set the test note to test a browser tool.
            Notes stay in this tab and reset on reload.
          </p>
        </details>
      </main>
      <BrowserVoiceShell
        service="voice-gateway"
        toolGroup="test"
        clientName="voice-gateway-frontend"
        preparation={scope}
        setupFailed="Voice controls could not be loaded. Reload the page to retry."
        onError={(cause) => {
          console.error("Voice gateway frontend", cause);
        }}
      />
    </div>
  );
}
