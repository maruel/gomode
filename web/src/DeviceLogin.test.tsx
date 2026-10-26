// Tests pairing polling, typed recovery, unknown delivery, account changes, and explicit CSRF-protected decisions.

import { afterEach, describe, it } from "node:test";
import { createRoot } from "solid-js";
import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { expect, vi } from "../tests/expect";
import { createDeviceLogin, DeviceLoginApproval, type DeviceLoginState } from "./DeviceLogin";

const originalFetch = globalThis.fetch;
const endpoints = { start: "/start", poll: "/poll", lookup: "/lookup", approve: "/approve" };
const code = {
  device_code: "private",
  user_code: "BCDF-GHJK",
  verification_uri: "https://host/device",
  expires_in: 600,
  interval: 5,
};
const flush = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};
afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.useRealTimers();
});

describe("device login", () => {
  it("respects pending and increased slow-down intervals and passes the normal host session response", async () => {
    vi.useFakeTimers();
    const onSession = vi.fn();
    const fetch = vi.fn<typeof globalThis.fetch>();
    fetch.mockResolvedValueOnce(Response.json(code));
    fetch.mockResolvedValueOnce(Response.json({ error: "authorization_pending" }, { status: 400 }));
    fetch.mockResolvedValueOnce(Response.json({ error: "slow_down", interval: 10 }, { status: 400 }));
    fetch.mockResolvedValueOnce(Response.json({ token: "normal-host-token" }));
    globalThis.fetch = fetch;
    let dispose = () => {};
    const login = createRoot((d) => {
      dispose = d;
      return createDeviceLogin({ endpoints, onSession });
    });
    try {
      await login.start();
      expect(fetch).toHaveBeenCalledTimes(1);
      vi.advanceTimersByTime(5000);
      await flush();
      expect(fetch).toHaveBeenCalledTimes(2);
      vi.advanceTimersByTime(5000);
      await flush();
      expect(fetch).toHaveBeenCalledTimes(3);
      vi.advanceTimersByTime(9999);
      await flush();
      expect(fetch).toHaveBeenCalledTimes(3);
      vi.advanceTimersByTime(1);
      await flush();
      expect(onSession).toHaveBeenCalledWith({ token: "normal-host-token" });
      expect(login.state()).toBe("approved");
    } finally {
      dispose();
    }
  });
  for (const [error, state] of [
    ["access_denied", "denied"],
    ["expired_token", "expired"],
    ["bad", "error"],
  ] as const) {
    it(`handles ${state} and stops polling`, async () => {
      vi.useFakeTimers();
      const fetch = vi
        .fn<typeof globalThis.fetch>()
        .mockResolvedValueOnce(Response.json(code))
        .mockResolvedValueOnce(Response.json({ error }, { status: 400 }));
      globalThis.fetch = fetch;
      let dispose = () => {};
      const login = createRoot((d) => {
        dispose = d;
        return createDeviceLogin({ endpoints, onSession: () => {} });
      });
      try {
        await login.start();
        vi.advanceTimersByTime(5000);
        await flush();
        expect(login.state()).toBe(state satisfies DeviceLoginState);
        vi.advanceTimersByTime(600000);
        await flush();
        expect(fetch).toHaveBeenCalledTimes(2);
      } finally {
        dispose();
      }
    });
  }
  it("accepts a cookie-only successful session", async () => {
    vi.useFakeTimers();
    const onSession = vi.fn();
    globalThis.fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(code))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    let dispose = () => {};
    const login = createRoot((d) => {
      dispose = d;
      return createDeviceLogin({ endpoints, onSession });
    });
    try {
      await login.start();
      vi.advanceTimersByTime(5000);
      await flush();
      expect(login.state()).toBe("approved");
      expect(onSession).toHaveBeenCalledWith(null);
    } finally {
      dispose();
    }
  });
  it("expires locally and cancels work on disposal", async () => {
    vi.useFakeTimers();
    globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ ...code, expires_in: 2 }));
    let dispose = () => {};
    const login = createRoot((d) => {
      dispose = d;
      return createDeviceLogin({ endpoints, onSession: () => {} });
    });
    await login.start();
    vi.advanceTimersByTime(2000);
    expect(login.state()).toBe("expired");
    await login.start();
    dispose();
    vi.advanceTimersByTime(10000);
    await flush();
    expect(globalThis.fetch).toHaveBeenCalledTimes(2);
  });
  it("never approves during lookup and sends the CSRF header only after an explicit decision", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(
        Response.json({
          user_code: code.user_code,
          ip: "192.0.2.0/24",
          user_agent: "Car browser",
          created_at: "2026-10-22T12:00:00Z",
          expires_at: new Date(Date.now() + 600_000).toISOString(),
          csrf_token: "csrf",
        }),
      )
      .mockResolvedValueOnce(Response.json({ state: "approved" }));
    globalThis.fetch = fetch;
    render(() => <DeviceLoginApproval endpoints={endpoints} headers={() => ({ Authorization: "Bearer normal" })} />);
    fireEvent.input(screen.getByRole("textbox", { name: "Device code" }), { target: { value: code.user_code } });
    fireEvent.click(screen.getByRole("button", { name: "Check code" }));
    await waitFor(() => expect(screen.getByText("Car browser")).toBeInTheDocument());
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/Never approve a code sent by someone else/)).toBeInTheDocument();
    expect(screen.getByText(/This device will receive your account’s permissions/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Approve sign-in" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    const init = fetch.mock.calls[1]?.[1];
    expect(new Headers(init?.headers).get("X-Device-Login-CSRF")).toBe("csrf");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer normal");
    expect(JSON.parse(String(init?.body))).toEqual({
      user_code: code.user_code,
      approve: true,
    });
  });
  for (const [error, kind, status] of [
    ["audit_failed", "audit", 500],
    ["session_failed", "session", 500],
    ["rate_limited", "throttled", 429],
    ["disabled", "disabled", 404],
  ] as const) {
    it(`exposes ${kind} completion failure without replay`, async () => {
      vi.useFakeTimers();
      const fetch = vi
        .fn<typeof globalThis.fetch>()
        .mockResolvedValueOnce(Response.json(code))
        .mockResolvedValueOnce(Response.json({ error }, { status }));
      globalThis.fetch = fetch;
      let dispose = () => {};
      const login = createRoot((d) => {
        dispose = d;
        return createDeviceLogin({ endpoints, onSession: () => {} });
      });
      try {
        await login.start();
        vi.advanceTimersByTime(5000);
        await flush();
        expect(login.failure()?.kind).toBe(kind);
        vi.advanceTimersByTime(600000);
        await flush();
        expect(fetch).toHaveBeenCalledTimes(2);
      } finally {
        dispose();
      }
    });
  }
  it("keeps network delivery and local session-consumption failures unconfirmed", async () => {
    vi.useFakeTimers();
    for (const response of [null, Response.json({ session: "host" })]) {
      const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(Response.json(code));
      if (response) fetch.mockResolvedValueOnce(response);
      else fetch.mockRejectedValueOnce(new Error("network lost"));
      globalThis.fetch = fetch;
      let dispose = () => {};
      const login = createRoot((d) => {
        dispose = d;
        return createDeviceLogin({
          endpoints,
          onSession: () => {
            throw new Error("cannot store session");
          },
        });
      });
      try {
        await login.start();
        vi.advanceTimersByTime(5000);
        await flush();
        expect(login.failure()?.kind).toBe("delivery");
        expect(login.state()).toBe("error");
      } finally {
        dispose();
      }
    }
  });
  it("recognizes a host's non-protocol throttling response", async () => {
    globalThis.fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValue(new Response("Too many requests", { status: 429, headers: { "Retry-After": "60" } }));
    let dispose = () => {};
    const login = createRoot((d) => {
      dispose = d;
      return createDeviceLogin({ endpoints, onSession: () => {} });
    });
    try {
      await login.start();
      expect(login.failure()).toEqual({ kind: "throttled", retryAfter: 60 });
    } finally {
      dispose();
    }
  });
  it("refuses a decision after the approving account changes", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(
      Response.json({
        user_code: code.user_code,
        ip: "192.0.2.0/24",
        user_agent: "Car",
        created_at: new Date().toISOString(),
        expires_at: new Date(Date.now() + 600000).toISOString(),
        csrf_token: "csrf",
      }),
    );
    globalThis.fetch = fetch;
    const verify = vi.fn<() => Promise<boolean>>().mockResolvedValueOnce(true).mockResolvedValueOnce(false);
    render(() => <DeviceLoginApproval endpoints={endpoints} verifyApprover={verify} />);
    fireEvent.input(screen.getByRole("textbox", { name: "Device code" }), { target: { value: code.user_code } });
    fireEvent.click(screen.getByRole("button", { name: "Check code" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve sign-in" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Approve sign-in" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Your account or request changed."));
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "Approve sign-in" })).toBeNull();
  });
  it("ignores a late identity verification after unmount", async () => {
    let resolve = (_value: boolean) => {};
    const check = new Promise<boolean>((r) => {
      resolve = r;
    });
    const fetch = vi.fn<typeof globalThis.fetch>();
    globalThis.fetch = fetch;
    const view = render(() => <DeviceLoginApproval endpoints={endpoints} verifyApprover={() => check} />);
    fireEvent.input(screen.getByRole("textbox", { name: "Device code" }), { target: { value: code.user_code } });
    fireEvent.click(screen.getByRole("button", { name: "Check code" }));
    view.unmount();
    resolve(true);
    await flush();
    expect(fetch).not.toHaveBeenCalled();
  });
  it("does not accept a malformed approval acknowledgement as success", async () => {
    globalThis.fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(
        Response.json({
          user_code: code.user_code,
          ip: "192.0.2.0/24",
          user_agent: "Car",
          created_at: new Date().toISOString(),
          expires_at: new Date(Date.now() + 600000).toISOString(),
          csrf_token: "csrf",
        }),
      )
      .mockResolvedValueOnce(Response.json({ state: "pending" }));
    render(() => <DeviceLoginApproval endpoints={endpoints} />);
    fireEvent.input(screen.getByRole("textbox", { name: "Device code" }), { target: { value: code.user_code } });
    fireEvent.click(screen.getByRole("button", { name: "Check code" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve sign-in" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Approve sign-in" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Your decision could not be confirmed."));
  });
});
