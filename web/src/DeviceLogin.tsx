// Shared device-code login states, actionable recovery, and accessible, host-styled pairing views.

import { createEffect, createSignal, createUniqueId, onCleanup, Show, type Accessor } from "solid-js";
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
  start: string;
  starting: string;
  instructions: string;
  pending: string;
  approved: string;
  denied: string;
  expired: string;
  error: string;
  remaining: (seconds: number) => string;
  code: string;
  lookup: string;
  approve: string;
  deny: string;
  warning: string;
  approvalApproved: string;
  approvalDenied: string;
  approvalError: string;
  approvalExpired: string;
  device: string;
  address: string;
  requested: string;
}
export const defaultDeviceLoginMessages: DeviceLoginMessages = {
  start: "Sign in with another device",
  starting: "Creating a code…",
  instructions: "On a device where you are signed in, open this address and enter the code.",
  pending: "Waiting for approval…",
  approved: "Signed in.",
  denied: "Sign-in denied.",
  expired: "This code expired. Create a new code.",
  error: "Sign-in failed. Create a new code.",
  remaining: (seconds) => `Expires in ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`,
  code: "Device code",
  lookup: "Check code",
  approve: "Approve sign-in",
  deny: "Deny sign-in",
  warning:
    "Only approve a code shown on a device in front of you that you want to sign in. Never approve a code sent by someone else. This device will receive your account’s permissions.",
  approvalApproved: "Sign-in approved. You can close this page.",
  approvalDenied: "Sign-in denied. You can close this page.",
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
  approvalAudit: "Your decision could not be recorded. Check the code again before approving or denying.",
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

/** Minimal large-code display. Public --device-login-* CSS tokens inherit host styling. */
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
      <Show when={login.state() !== "idle" && login.state() !== "starting" && login.code()}>
        {(code) => (
          <>
            <p>{m().instructions}</p>
            <p class={styles.code} aria-label={m().code}>
              {code().user_code}
            </p>
            <p class={styles.url}>{code().verification_uri}</p>
            <Show when={login.state() === "pending"}>
              <p>{m().remaining(login.remaining())}</p>
            </Show>
          </>
        )}
      </Show>
      <p role="status">{status()}</p>
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
          {login.state() === "idle" ? m().start : r().newCode}
        </button>
      </Show>
    </section>
  );
}

/** Manual code entry and explicit decision; never reads a device secret or approves on navigation. */
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
  const [state, setState] = createSignal<"idle" | "approved" | "denied">("idle");
  const [problem, setProblem] = createSignal<DeviceLoginFailure | null>(null);
  const r = () => recoveryMessages(props.recoveryMessages);
  const c = new AbortController();
  onCleanup(() => c.abort());
  const reject = (issue: DeviceLoginFailure) => {
    if (c.signal.aborted) return;
    setDetails(null);
    setProblem(issue);
    if (issue.kind === "login") props.onAuthenticationRequired?.();
  };
  const verify = async () => {
    if (props.verifyApprover && !(await props.verifyApprover(c.signal))) {
      reject({ kind: "changed", retryAfter: null });
      return false;
    }
    return !c.signal.aborted;
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
    const issue = problem();
    if (!issue) return state() === "approved" ? m().approvalApproved : state() === "denied" ? m().approvalDenied : "";
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
    setBusy(true);
    setState("idle");
    setProblem(null);
    setDetails(null);
    try {
      if (!(await verify())) return;
      const result = await request(props.endpoints.lookup, { user_code: code() }, props.headers?.(), c.signal);
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
      if (!c.signal.aborted) {
        setDetails(d);
      }
    } catch {
      if (!c.signal.aborted) reject({ kind: "network", retryAfter: null });
    } finally {
      setBusy(false);
    }
  };
  const decide = async (approve: boolean) => {
    const d = details();
    if (!d) return;
    setBusy(true);
    let sent = false;
    try {
      if (!(await verify())) return;
      const h = new Headers(props.headers?.());
      h.set("X-Device-Login-CSRF", d.csrf_token);
      sent = true;
      const result = await request(props.endpoints.approve, { user_code: d.user_code, approve }, h, c.signal);
      if (c.signal.aborted) return;
      if (result.ok) {
        if (
          !result.data ||
          typeof result.data !== "object" ||
          !("state" in result.data) ||
          result.data.state !== (approve ? "approved" : "denied")
        )
          throw new Error("invalid_response");
        setProblem(null);
        setState(approve ? "approved" : "denied");
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
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void lookup();
        }}
      >
        <label for={id}>{m().code}</label>
        <input
          id={id}
          value={code()}
          onInput={(e) => {
            setCode(e.currentTarget.value);
            setDetails(null);
            setState("idle");
            setProblem(null);
          }}
          autocomplete="off"
          autocapitalize="characters"
          spellcheck={false}
          maxLength={16}
          required
          disabled={busy()}
        />
        <button type="submit" disabled={busy()}>
          {m().lookup}
        </button>
      </form>
      <Show when={details()}>
        {(d) => (
          <>
            <p class={styles.code}>{d().user_code}</p>
            <p>{m().warning}</p>
            <dl>
              <dt>{m().address}</dt>
              <dd>{d().ip}</dd>
              <dt>{m().device}</dt>
              <dd>{d().user_agent}</dd>
              <dt>{m().requested}</dt>
              <dd>{new Date(d().created_at).toLocaleString()}</dd>
            </dl>
            <div class={styles.actions}>
              <button
                type="button"
                disabled={busy()}
                onClick={() => {
                  void decide(true);
                }}
              >
                {m().approve}
              </button>
              <button
                type="button"
                disabled={busy()}
                onClick={() => {
                  void decide(false);
                }}
              >
                {m().deny}
              </button>
            </div>
          </>
        )}
      </Show>
      <p role="status">{status()}</p>
    </section>
  );
}
