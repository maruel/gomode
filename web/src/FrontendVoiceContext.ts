// Owns bounded conversation context publication, advisory outcomes, retries, and detached lease-release diagnostics.

import {
  batch,
  createEffect,
  createMemo,
  createRoot,
  createSignal,
  untrack,
  type Accessor,
  type Setter,
} from "solid-js";

export type FrontendVoiceContextFailure = "concurrent-update" | "request" | "timeout";
export type FrontendVoiceContextRelease = (request: (signal: AbortSignal) => Promise<unknown>) => void;

export class FrontendVoiceContextConflict extends Error {
  constructor() {
    super("Voice context changed concurrently.");
    this.name = "FrontendVoiceContextConflict";
  }
}

export interface FrontendVoiceContextPublisher {
  publish(selection: string | null, isCurrent: () => boolean, signal: AbortSignal): Promise<void>;
  dispose(): void;
}

export interface FrontendVoiceContextPolicy {
  selection: Accessor<string | null>;
  createPublisher(
    sessionID: string,
    kind: "conversation" | "invocation",
    release: FrontendVoiceContextRelease,
  ): FrontendVoiceContextPublisher;
}

export interface FrontendVoiceContextPublication {
  failure: FrontendVoiceContextFailure | null;
  pending: boolean;
}

interface Publication {
  selection: string | null;
  controller: AbortController;
  promise: Promise<void>;
}

interface Conversation {
  sessionID: string;
  publisher: FrontendVoiceContextPublisher;
  pending: Accessor<Publication | null>;
  setPending: Setter<Publication | null>;
}

type Outcome = { selection: string | null; failure: FrontendVoiceContextFailure | null };
const emptyPublication: FrontendVoiceContextPublication = { failure: null, pending: false };

// The preparation scope owns this coordinator; host policy owns its publication format and lease.
export function createFrontendVoiceContext(policy: FrontendVoiceContextPolicy, ready: Accessor<boolean>) {
  return createRoot((disposeRoot) => {
    const selected = createMemo(policy.selection);
    const [conversation, setConversation] = createSignal<Conversation | null>(null);
    const [outcome, setOutcome] = createSignal<Outcome | null>(null);
    let invocation: Conversation | null = null;
    const createConversation = (sessionID: string, kind: "conversation" | "invocation"): Conversation => {
      const [pending, setPending] = createSignal<Publication | null>(null);
      return {
        sessionID,
        publisher: policy.createPublisher(sessionID, kind, releaseVoiceContext),
        pending,
        setPending,
      };
    };
    const current = (value: Conversation) => untrack(conversation) === value || invocation === value;
    const release = (value: Conversation) => {
      untrack(value.pending)?.controller.abort();
      value.publisher.dispose();
    };
    const retire = (sessionID: string) => {
      const value = untrack(conversation);
      if (value?.sessionID === sessionID) {
        setConversation(null);
        setOutcome(null);
        release(value);
      }
      if (invocation?.sessionID === sessionID) {
        const previous = invocation;
        invocation = null;
        release(previous);
      }
    };
    const publish = (value: Conversation, selection: string | null): Publication => {
      const pending = untrack(value.pending);
      if (pending?.selection === selection && !pending.controller.signal.aborted) return pending;
      pending?.controller.abort();
      if (untrack(conversation) === value && untrack(outcome)?.selection !== selection) setOutcome(null);
      const controller = new AbortController();
      const signal = controller.signal;
      const timer = setTimeout(
        () => controller.abort(new DOMException("Voice context publication timed out.", "TimeoutError")),
        2000,
      );
      const work = Promise.resolve().then(() => {
        if (!current(value) || signal.aborted) return;
        return value.publisher.publish(
          selection,
          () => current(value) && untrack(value.pending) === publication && !signal.aborted,
          signal,
        );
      });
      const publication: Publication = { selection, controller, promise: abortable(work, signal) };
      value.setPending(publication);
      const settle = (failure: FrontendVoiceContextFailure | null) => {
        clearTimeout(timer);
        if (!current(value) || untrack(value.pending) !== publication) return;
        // Ordinary cancellation is an expected attempt/supersession boundary.
        const canceled = signal.aborted && signal.reason?.name !== "TimeoutError";
        batch(() => {
          value.setPending(null);
          if (untrack(conversation) === value && untrack(selected) === selection)
            setOutcome(canceled ? null : { selection, failure });
        });
        if (!canceled && failure !== null) diagnose("publish", failure);
      };
      void publication.promise.then(
        () => settle(null),
        (error: unknown) => settle(classify(error)),
      );
      return publication;
    };
    const prepare = async (value: Conversation, signal: AbortSignal) => {
      if (signal.aborted || !current(value) || !untrack(ready)) throw retired();
      let publication: Publication | null = publish(value, untrack(selected));
      while (publication !== null) {
        const captured = publication;
        const abort = () => captured.controller.abort();
        signal.addEventListener("abort", abort, { once: true });
        try {
          await captured.promise;
        } catch (error) {
          // Supersession redirects preparation to its newer selected item.
          if (!captured.controller.signal.aborted || captured.controller.signal.reason?.name === "TimeoutError")
            throw error;
        } finally {
          signal.removeEventListener("abort", abort);
        }
        if (signal.aborted || !current(value) || !untrack(ready)) throw retired();
        publication = untrack(value.pending);
      }
    };
    createEffect(() => {
      const value = conversation();
      const selection = selected();
      if (value === null || !ready()) return;
      // Publication settlement updates the outcome, not selection subscription.
      void publish(value, selection).promise.catch(() => {});
    });
    const contextPublication = createMemo<FrontendVoiceContextPublication>(() => {
      const value = conversation();
      const result = outcome();
      return value === null
        ? emptyPublication
        : {
            failure: result?.selection === selected() ? result.failure : null,
            pending: value.pending() !== null,
          };
    });
    return {
      contextPublication,
      async retryContext() {
        const value = untrack(conversation);
        if (value === null || !untrack(ready)) return;
        // The existing pending publication shares retries and owns cancellation.
        await prepare(value, new AbortController().signal).catch(() => {});
      },
      async prepareSession(sessionID: string, signal: AbortSignal) {
        const value = untrack(conversation);
        if (value?.sessionID !== sessionID) {
          if (value !== null) retire(value.sessionID);
          if (invocation !== null) retire(invocation.sessionID);
          const created = createConversation(sessionID, "conversation");
          return batch(() => {
            setConversation(created);
            return prepare(created, signal);
          });
        }
        await prepare(value, signal);
      },
      async publishContext(sessionID: string, signal: AbortSignal) {
        const value = untrack(conversation);
        if (value !== null) {
          if (value.sessionID !== sessionID) throw retired();
          await prepare(value, signal);
          return;
        }
        if (invocation?.sessionID !== sessionID) {
          if (invocation !== null) retire(invocation.sessionID);
          invocation = createConversation(sessionID, "invocation");
        }
        await prepare(invocation, signal);
      },
      retireSession: retire,
      reset() {
        const value = untrack(conversation);
        if (value !== null) retire(value.sessionID);
        if (invocation !== null) retire(invocation.sessionID);
      },
      dispose() {
        this.reset();
        disposeRoot();
      },
    };
  });
}

// Detached cleanup captures only its request and bounded diagnostic, never a conversation slot.
function releaseVoiceContext(request: (signal: AbortSignal) => Promise<unknown>): void {
  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(new DOMException("Voice context release timed out.", "TimeoutError")),
    2000,
  );
  const work = Promise.resolve()
    .then(() => request(controller.signal))
    .then(() => {});
  void abortable(work, controller.signal)
    .catch((error: unknown) => diagnose("release", classify(error)))
    .finally(() => clearTimeout(timer));
}

function classify(error: unknown): FrontendVoiceContextFailure {
  if (error instanceof DOMException && error.name === "TimeoutError") return "timeout";
  return error instanceof FrontendVoiceContextConflict ? "concurrent-update" : "request";
}

function diagnose(operation: "publish" | "release", failure: FrontendVoiceContextFailure): void {
  // Categories deliberately exclude host request/auth material and arbitrary error bodies.
  console.warn("Voice context operation failed", Object.freeze({ operation, failure }));
}

function retired(): DOMException {
  return new DOMException("Frontend preparation retired.", "AbortError");
}

function abortable(work: Promise<void>, signal: AbortSignal): Promise<void> {
  let abort: () => void;
  const canceled = new Promise<never>((_resolve, reject) => {
    abort = () => reject(signal.reason);
    if (signal.aborted) abort();
    else signal.addEventListener("abort", abort, { once: true });
  });
  return Promise.race([work, canceled]).finally(() => signal.removeEventListener("abort", abort));
}
