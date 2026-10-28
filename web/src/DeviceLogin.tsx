// Shared device-code login states, actionable recovery, and accessible, host-styled pairing views.

import { createEffect, createSignal, createUniqueId, onCleanup, onMount, Show, type Accessor } from "solid-js";
import styles from "./DeviceLogin.module.css";

export interface DeviceLoginEndpoints {
  start: string;
  poll: string;
  lookup: string;
  approve: string;
}
export interface DeviceLoginCode {
  device_code: string;
  user_code: string;
  verification_uri: string;
  expires_in: number;
  interval: number;
}
export interface DeviceLoginRequest {
  user_code: string;
  ip: string;
  user_agent: string;
  created_at: string;
  expires_at: string;
  csrf_token: string;
}
export type DeviceLoginState = "idle" | "starting" | "pending" | "approved" | "denied" | "expired" | "error";
export interface DeviceLoginOptions {
  endpoints: DeviceLoginEndpoints;
  /** Supply the host's normal authenticated request headers for approval requests. */
  headers?: () => HeadersInit;
  /** Consume the normal login response using the host's existing session/token storage. */
  onSession: (response: unknown) => void | Promise<void>;
  /** Check the host’s normal session state after an unconfirmed delivery. */
  onCheckSession?: () => void;
}

/** Actionable failures distinguish a rejected operation from unknown client receipt. */
export type DeviceLoginFailureKind =
  | "audit"
  | "changed"
  | "decided"
  | "delivery"
  | "disabled"
  | "expired"
  | "ineligible"
  | "login"
  | "network"
  | "rejected"
  | "session"
  | "throttled";
export interface DeviceLoginFailure {
  kind: DeviceLoginFailureKind;
  retryAfter: number | null;
}
interface Result {
  ok: boolean;
  status: number;
  data: unknown;
  retryAfter: number | null;
}
async function request(
  url: string,
  body: object,
  headers: HeadersInit | undefined,
  signal: AbortSignal,
): Promise<Result> {
  const h = new Headers(headers);
  h.set("Content-Type", "application/json");
  const response = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers: h,
    body: JSON.stringify(body),
    signal,
    cache: "no-store",
  });
  const text = await response.text();
  let data: unknown = null;
  try {
    data = text.length ? JSON.parse(text) : null;
  } catch {
    if (response.ok) throw new Error("invalid_response");
  }
  const retry = response.headers.get("Retry-After");
  const retryAfter = retry !== null && /^\d+$/.test(retry) ? Number(retry) : null;
  return { ok: response.ok, status: response.status, data, retryAfter };
}
function errorCode(value: unknown): string | null {
  return typeof value === "object" && value !== null && "error" in value && typeof value.error === "string"
    ? value.error
    : null;
}
function failure(result: Result, operation: "start" | "poll" | "lookup" | "approve"): DeviceLoginFailure {
  let kind: DeviceLoginFailureKind;
  switch (errorCode(result.data)) {
    case "expired_token":
      kind = "expired";
      break;
    case "audit_failed":
      kind = "audit";
      break;
    case "session_failed":
      kind = "session";
      break;
    case "csrf_failed":
      kind = "changed";
      break;
    case "already_decided":
      kind = "decided";
      break;
    case "disabled":
      kind = "disabled";
      break;
    case "access_denied":
      kind = "ineligible";
      break;
    case "login_required":
      kind = "login";
      break;
    default:
      kind =
        result.status === 429
          ? "throttled"
          : result.status === 401
            ? "login"
            : operation === "poll" || operation === "approve"
              ? "delivery"
              : "rejected";
  }
  return { kind, retryAfter: result.retryAfter };
}
function interval(value: unknown): number | null {
  return typeof value === "object" &&
    value !== null &&
    "interval" in value &&
    typeof value.interval === "number" &&
    value.interval > 0
    ? value.interval
    : null;
}

/** Owns one display request. Dispose or restart cancels pending fetches and timers. */
export function createDeviceLogin(options: DeviceLoginOptions) {
  const [state, setState] = createSignal<DeviceLoginState>("idle");
  const [code, setCode] = createSignal<DeviceLoginCode | null>(null);
  const [remaining, setRemaining] = createSignal(0);
  const [problem, setProblem] = createSignal<DeviceLoginFailure | null>(null);
  let controller: AbortController | null = null;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let clock: ReturnType<typeof setInterval> | null = null;
  let generation = 0;
  const stop = () => {
    generation++;
    controller?.abort();
    controller = null;
    if (pollTimer !== null) clearTimeout(pollTimer);
    if (clock !== null) clearInterval(clock);
    pollTimer = null;
    clock = null;
  };
  const start = async () => {
    stop();
    const id = generation;
    const c = new AbortController();
    controller = c;
    setCode(null);
    setProblem(null);
    setState("starting");
    try {
      const response = await request(options.endpoints.start, {}, undefined, c.signal);
      if (id !== generation) return;
      if (!response.ok) {
        setProblem(failure(response, "start"));
        stop();
        setState("error");
        return;
      }
      const data = response.data as DeviceLoginCode;
      if (
        !data ||
        typeof data.device_code !== "string" ||
        typeof data.user_code !== "string" ||
        typeof data.verification_uri !== "string" ||
        !(data.expires_in > 0) ||
        !(data.interval > 0)
      )
        throw new Error("invalid_response");
      const verificationURL = new URL(data.verification_uri);
      if (verificationURL.protocol !== "https:" && verificationURL.protocol !== "http:")
        throw new Error("invalid_response");
      setCode(data);
      setState("pending");
      const expires = Date.now() + data.expires_in * 1000;
      setRemaining(data.expires_in);
      let seconds = data.interval;
      const finish = (next: DeviceLoginState) => {
        stop();
        setState(next);
      };
      clock = setInterval(() => {
        setRemaining(Math.max(0, Math.ceil((expires - Date.now()) / 1000)));
        if (Date.now() >= expires) finish("expired");
      }, 1000);
      const poll = async () => {
        try {
          const result = await request(options.endpoints.poll, { device_code: data.device_code }, undefined, c.signal);
          if (id !== generation) return;
          if (result.ok) {
            await options.onSession(result.data);
            if (id === generation) finish("approved");
            return;
          }
          const error = errorCode(result.data);
          if (error === "authorization_pending" || error === "slow_down") {
            if (error === "slow_down") seconds = interval(result.data) ?? seconds + 5;
            pollTimer = setTimeout(() => {
              void poll();
            }, seconds * 1000);
          } else {
            setProblem(failure(result, "poll"));
            finish(error === "access_denied" ? "denied" : error === "expired_token" ? "expired" : "error");
          }
        } catch {
          if (id === generation) {
            setProblem({ kind: "delivery", retryAfter: null });
            finish("error");
          }
        }
      };
      pollTimer = setTimeout(() => {
        void poll();
      }, seconds * 1000);
    } catch {
      if (id === generation) {
        stop();
        setProblem({ kind: "network", retryAfter: null });
        setState("error");
      }
    }
  };
  onCleanup(stop);
  return { state, code, remaining, failure: problem, start };
}

export interface DeviceLoginMessages {
  starting: string;
  instructions: string;
  pending: string;
  approved: string;
  denied: string;
  expired: string;
  error: string;
  remaining: (seconds: number) => string;
  code: string;
  retry: string;
  checking: string;
  tooLong: string;
  approve: string;
  warning: string;
  approvalApproved: string;
  approvalError: string;
  approvalExpired: string;
  device: string;
  address: string;
  requested: string;
}
export const defaultDeviceLoginMessages: DeviceLoginMessages = {
  starting: "Creating a code…",
  instructions: "On a device where you are signed in, open this address and enter the code.",
  pending: "Waiting for approval…",
  approved: "Signed in.",
  denied: "Sign-in denied.",
  expired: "This code expired. Create a new code.",
  error: "Sign-in failed. Create a new code.",
  remaining: (seconds) => `Expires in ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`,
  code: "Device code",
  retry: "Try again",
  checking: "Checking code…",
  tooLong: "Enter the 12-character code shown on the other device.",
  approve: "Approve sign-in",
  warning:
    "Only approve a code shown on a device in front of you that you want to sign in. Never approve a code sent by someone else. This device will receive your account’s permissions.",
  approvalApproved: "Sign-in approved. You can close this page.",
  approvalError: "Could not check or approve this code. Check it again.",
  approvalExpired: "This code expired. Create a new code on the device you want to sign in.",
  device: "Browser",
  address: "Network",
  requested: "Requested",
};

/** Hosts translate recovery copy separately from the ordinary pairing labels. */
export interface DeviceLoginRecoveryMessages {
  newCode: string;
  checkSignIn: string;
  throttled: (seconds: number | null) => string;
  approvalThrottled: (seconds: number | null) => string;
  audit: string;
  approvalAudit: string;
  session: string;
  delivery: string;
  approvalDelivery: string;
  approvalNetwork: string;
  changed: string;
  decided: string;
  login: string;
  ineligible: string;
  disabled: string;
  network: string;
}
export const defaultDeviceLoginRecoveryMessages: DeviceLoginRecoveryMessages = {
  newCode: "Create a new code",
  checkSignIn: "Check sign-in",
  throttled: (seconds) =>
    seconds === null
      ? "Too many attempts. Wait ten minutes before creating a new code."
      : `Too many attempts. Wait ${seconds} seconds before creating a new code.`,
  approvalThrottled: (seconds) =>
    seconds === null
      ? "Too many attempts. Wait ten minutes before checking the code again."
      : `Too many attempts. Wait ${seconds} seconds before checking the code again.`,
  audit: "Sign-in could not be recorded. Create a new code.",
  approvalAudit: "Your approval could not be recorded. Check the code again before approving.",
  session: "A session could not be completed. Create a new code.",
  delivery: "Sign-in could not be confirmed. Check whether this browser is signed in, or create a new code.",
  approvalDelivery: "Your decision could not be confirmed. Check the code again, and check the other device.",
  approvalNetwork: "Could not check the code. Check your connection and check it again.",
  changed: "Your account or request changed. Check the code again.",
  decided: "This code is being processed or already used. Check the other device.",
  login: "Sign in again to check this code.",
  ineligible: "This account cannot approve device sign-in.",
  disabled: "Device sign-in is unavailable.",
  network: "Could not create a code. Check your connection and try again.",
};

type RecoveryMessages = Partial<DeviceLoginRecoveryMessages> | Accessor<Partial<DeviceLoginRecoveryMessages>>;
function recoveryMessages(value: RecoveryMessages | undefined) {
  return { ...defaultDeviceLoginRecoveryMessages, ...(typeof value === "function" ? value() : value) };
}

type Messages = Partial<DeviceLoginMessages> | Accessor<Partial<DeviceLoginMessages>>;
function messages(value: Messages | undefined) {
  return { ...defaultDeviceLoginMessages, ...(typeof value === "function" ? value() : value) };
}

/** Pairing instructions and active code. Public --device-login-* CSS tokens inherit host styling. */
export function DeviceLoginDisplay(props: {
  options: DeviceLoginOptions;
  messages?: Messages;
  recoveryMessages?: RecoveryMessages;
}) {
  const login = createDeviceLogin({
    get endpoints() {
      return props.options.endpoints;
    },
    onSession: (response) => props.options.onSession(response),
  });
  const m = () => messages(props.messages);
  const r = () => recoveryMessages(props.recoveryMessages);
  onMount(() => void login.start());
  let activeCode: HTMLParagraphElement | undefined;
  let outcome: HTMLParagraphElement | undefined;
  createEffect(() => {
    const state = login.state();
    if (state === "pending") activeCode?.focus();
    else if (state !== "idle" && state !== "starting") outcome?.focus();
  });
  const status = () => {
    const problem = login.failure();
    if (login.state() !== "error" || !problem)
      return login.state() === "idle" ? "" : m()[login.state() as Exclude<DeviceLoginState, "idle">];
    if (problem.kind === "throttled") return r().throttled(problem.retryAfter);
    if (
      problem.kind === "audit" ||
      problem.kind === "session" ||
      problem.kind === "delivery" ||
      problem.kind === "disabled" ||
      problem.kind === "network"
    )
      return r()[problem.kind];
    return m().error;
  };
  return (
    <section class={styles.root}>
      <Show when={login.state() === "idle" || login.state() === "starting" || login.state() === "pending"}>
        <p class={styles.instructions}>{m().instructions}</p>
      </Show>
      <Show when={login.state() === "pending" && login.code()}>
        {(code) => (
          <div class={styles.pairing}>
            <a class={styles.url} href={code().verification_uri} target="_blank" rel="noopener noreferrer">
              {code().verification_uri}
            </a>
            <span class={styles.caption}>{m().code}</span>
            <p ref={activeCode} class={styles.code} role="group" aria-label={m().code} tabIndex={-1}>
              {code().user_code}
            </p>
            <p class={styles.remaining}>{m().remaining(login.remaining())}</p>
          </div>
        )}
      </Show>
      <p ref={outcome} class={styles.status} role="status" tabIndex={-1}>
        {status()}
      </p>
      <div class={styles.actions}>
        <Show when={login.failure()?.kind === "delivery" && props.options.onCheckSession}>
          <button type="button" onClick={() => props.options.onCheckSession?.()}>
            {r().checkSignIn}
          </button>
        </Show>
        <Show when={login.state() !== "starting" && login.state() !== "pending" && login.state() !== "approved"}>
          <button
            type="button"
            onClick={() => {
              void login.start();
            }}
          >
            {r().newCode}
          </button>
        </Show>
      </div>
    </section>
  );
}

/** Formats and checks complete codes automatically; authorization always requires an explicit decision. */
export function DeviceLoginApproval(props: {
  endpoints: DeviceLoginEndpoints;
  headers?: () => HeadersInit;
  messages?: Messages;
  recoveryMessages?: RecoveryMessages;
  onAuthenticationRequired?: () => void;
  /** Refuse an action if the host’s current identity differs from the rendered approver. */
  verifyApprover?: (signal: AbortSignal) => Promise<boolean>;
}) {
  const m = () => messages(props.messages);
  const id = createUniqueId();
  const [code, setCode] = createSignal("");
  const [details, setDetails] = createSignal<DeviceLoginRequest | null>(null);
  const [busy, setBusy] = createSignal(false);
  const [checking, setChecking] = createSignal(false);
  const [state, setState] = createSignal<"idle" | "approved">("idle");
  const [problem, setProblem] = createSignal<DeviceLoginFailure | null>(null);
  let review: HTMLParagraphElement | undefined;
  let outcome: HTMLParagraphElement | undefined;
  let input: HTMLInputElement | undefined;
  let composing = false;
  let restoreInput = false;
  onMount(() => input?.focus());
  createEffect(() => {
    if (details()) review?.focus();
    else if (state() !== "idle") outcome?.focus();
    else if (problem() && !busy() && restoreInput) {
      input?.focus();
      restoreInput = false;
    }
  });
  const r = () => recoveryMessages(props.recoveryMessages);
  const c = new AbortController();
  let pendingLookup: AbortController | null = null;
  onCleanup(() => {
    c.abort();
    pendingLookup?.abort();
  });
  const reject = (issue: DeviceLoginFailure) => {
    if (c.signal.aborted) return;
    restoreInput = details() !== null;
    setDetails(null);
    setProblem(issue);
    if (issue.kind === "login") props.onAuthenticationRequired?.();
  };
  const verify = async (signal: AbortSignal) => {
    const valid = !props.verifyApprover || (await props.verifyApprover(signal));
    if (signal.aborted) return false;
    if (!valid) {
      reject({ kind: "changed", retryAfter: null });
      return false;
    }
    return true;
  };
  createEffect(() => {
    const d = details();
    if (!d) return;
    const expires = Date.parse(d.expires_at);
    const timer = setInterval(() => {
      if (Date.now() >= expires) reject({ kind: "expired", retryAfter: null });
    }, 1000);
    onCleanup(() => clearInterval(timer));
  });
  const status = () => {
    if (checking()) return m().checking;
    if (code().replaceAll("-", "").length > 12) return m().tooLong;
    const issue = problem();
    if (!issue) return state() === "approved" ? m().approvalApproved : "";
    if (issue.kind === "throttled") return r().approvalThrottled(issue.retryAfter);
    if (issue.kind === "audit") return r().approvalAudit;
    if (issue.kind === "delivery") return r().approvalDelivery;
    if (issue.kind === "network") return r().approvalNetwork;
    if (issue.kind === "expired") return m().approvalExpired;
    if (
      issue.kind === "changed" ||
      issue.kind === "decided" ||
      issue.kind === "login" ||
      issue.kind === "ineligible" ||
      issue.kind === "disabled"
    )
      return r()[issue.kind];
    return m().approvalError;
  };
  const lookup = async () => {
    // Match the 12-symbol code contract in devicelogin/engine.go's randomCode.
    if (busy() || checking() || details() || !/^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code())) return;
    const controller = new AbortController();
    pendingLookup = controller;
    const value = code();
    setChecking(true);
    setState("idle");
    setProblem(null);
    setDetails(null);
    try {
      if (!(await verify(controller.signal))) return;
      const result = await request(props.endpoints.lookup, { user_code: value }, props.headers?.(), controller.signal);
      if (controller.signal.aborted || composing) return;
      if (!result.ok) {
        reject(failure(result, "lookup"));
        return;
      }
      const d = result.data as DeviceLoginRequest;
      if (
        !d ||
        typeof d.user_code !== "string" ||
        typeof d.csrf_token !== "string" ||
        !d.csrf_token ||
        typeof d.ip !== "string" ||
        typeof d.user_agent !== "string" ||
        !Number.isFinite(Date.parse(d.created_at)) ||
        !Number.isFinite(Date.parse(d.expires_at))
      )
        throw new Error("invalid_response");
      if (Date.now() >= Date.parse(d.expires_at)) {
        reject({ kind: "expired", retryAfter: null });
        return;
      }
      setDetails(d);
    } catch {
      if (!controller.signal.aborted) reject({ kind: "network", retryAfter: null });
    } finally {
      if (pendingLookup === controller) {
        pendingLookup = null;
        setChecking(false);
      }
    }
  };
  const edit = (el: HTMLInputElement, inputType: string) => {
    let before = el.value.slice(0, el.selectionStart ?? el.value.length).replace(/[^a-z0-9]/gi, "").length;
    let raw = el.value.toUpperCase().replace(/[^A-Z0-9]/g, "");
    // Deleting an inserted separator also deletes the adjacent symbol, so
    // backspace and forward-delete cannot get stuck on a regenerated dash.
    if (raw === code().replaceAll("-", "") && el.value.length < code().length) {
      if (inputType === "deleteContentBackward" && before > 0) {
        raw = raw.slice(0, before - 1) + raw.slice(before);
        before--;
      } else if (inputType === "deleteContentForward") {
        raw = raw.slice(0, before) + raw.slice(before + 1);
      }
    }
    const formatted = raw.match(/.{1,4}/g)?.join("-") ?? "";
    el.value = formatted;
    const caret = Math.min(formatted.length, before + Math.floor(Math.max(0, before - 1) / 4));
    el.setSelectionRange(caret, caret);
    if (formatted === code()) return;
    pendingLookup?.abort();
    pendingLookup = null;
    setChecking(false);
    setCode(formatted);
    setDetails(null);
    setState("idle");
    setProblem(null);
    void lookup();
  };
  const approve = async () => {
    const d = details();
    if (!d || busy()) return;
    setBusy(true);
    let sent = false;
    try {
      if (!(await verify(c.signal))) return;
      const h = new Headers(props.headers?.());
      h.set("X-Device-Login-CSRF", d.csrf_token);
      sent = true;
      const result = await request(props.endpoints.approve, { user_code: d.user_code, approve: true }, h, c.signal);
      if (c.signal.aborted) return;
      if (result.ok) {
        if (
          !result.data ||
          typeof result.data !== "object" ||
          !("state" in result.data) ||
          result.data.state !== "approved"
        )
          throw new Error("invalid_response");
        setProblem(null);
        setState("approved");
      } else reject(failure(result, "approve"));
      setDetails(null);
    } catch {
      if (!c.signal.aborted) reject({ kind: sent ? "delivery" : "network", retryAfter: null });
    } finally {
      setBusy(false);
    }
  };
  return (
    <section class={styles.root}>
      <Show when={state() === "idle"}>
        <form
          aria-busy={checking()}
          onSubmit={(e) => {
            e.preventDefault();
            void lookup();
          }}
        >
          <label for={id}>{m().code}</label>
          <input
            ref={input}
            id={id}
            class={styles.codeInput}
            placeholder="XXXX-XXXX-XXXX"
            value={code()}
            onInput={(e) => {
              if (!composing && !e.isComposing) edit(e.currentTarget, e.inputType);
            }}
            onCompositionStart={() => {
              composing = true;
            }}
            onCompositionEnd={(e) => {
              composing = false;
              edit(e.currentTarget, "insertCompositionText");
              if (!details() && !problem()) void lookup();
            }}
            autocomplete="off"
            autocapitalize="characters"
            inputmode="text"
            enterkeyhint="done"
            spellcheck={false}
            required
            aria-invalid={code().replaceAll("-", "").length > 12 || undefined}
            disabled={busy()}
          />
          <Show
            when={
              problem() &&
              !checking() &&
              (problem()?.kind === "network" ||
                problem()?.kind === "delivery" ||
                problem()?.kind === "audit" ||
                problem()?.kind === "changed" ||
                problem()?.kind === "throttled")
            }
          >
            <button type="submit" disabled={busy() || !code().trim()}>
              {m().retry}
            </button>
          </Show>
        </form>
        <Show when={details()}>
          {(d) => (
            <div class={styles.review} aria-busy={busy()}>
              <p ref={review} class={styles.warning} tabIndex={-1}>
                {m().warning}
              </p>
              <dl>
                <dt>{m().address}</dt>
                <dd>{d().ip}</dd>
                <dt>{m().requested}</dt>
                <dd>{new Date(d().created_at).toLocaleString()}</dd>
              </dl>
              <details class={styles.browser}>
                <summary>{m().device}</summary>
                <p>{d().user_agent}</p>
              </details>
              <div class={styles.actions}>
                <button
                  type="button"
                  disabled={busy()}
                  onClick={() => {
                    void approve();
                  }}
                >
                  {m().approve}
                </button>
              </div>
            </div>
          )}
        </Show>
      </Show>
      <p ref={outcome} class={styles.status} role="status" tabIndex={-1}>
        {status()}
      </p>
    </section>
  );
}
