// Tests automatic pairing and code entry, correction, recovery, identity changes, and explicit authorization.

import { afterEach, describe, it } from "node:test";
import { createRoot } from "solid-js";
import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { expect, vi } from "../tests/expect";
import { createDeviceLogin, DeviceLoginApproval, DeviceLoginDisplay, type DeviceLoginState } from "./DeviceLogin";

const originalFetch = globalThis.fetch;
const endpoints = { start: "/start", poll: "/poll", lookup: "/lookup", approve: "/approve" };
const code = {
  device_code: "private",
  user_code: "BCDF-GHJK-MNPQ",
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
  it("rejects an unsafe verification link before exposing the code or polling", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValue(Response.json({ ...code, verification_uri: "javascript:alert(1)" }));
    globalThis.fetch = fetch;
    let dispose = () => {};
    const login = createRoot((d) => {
      dispose = d;
      return createDeviceLogin({ endpoints, onSession: () => {} });
    });
    try {
      await login.start();
      expect(login.state()).toBe("error");
      expect(login.code()).toBeNull();
      expect(fetch).toHaveBeenCalledTimes(1);
    } finally {
      dispose();
    }
  });
  it("starts pairing on arrival and removes the address and code on expiry", async () => {
    vi.useFakeTimers();
    globalThis.fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ ...code, expires_in: 2 }));
    render(() => <DeviceLoginDisplay options={{ endpoints, onSession: () => {} }} />);
    expect(screen.getByText(/On a device where you are signed in/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).toBeNull();
    await flush();
    expect(screen.getByRole("link", { name: code.verification_uri })).toHaveAttribute("href", code.verification_uri);
    expect(screen.getByRole("group", { name: "Device code" })).toHaveFocus();
    vi.advanceTimersByTime(2000);
    expect(screen.queryByLabelText("Device code")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByRole("status")).toHaveFocus();
    expect(screen.getByRole("button", { name: "Create a new code" })).toBeInTheDocument();
  });
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
  it("formats code entry and looks up only a complete code, including paste", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValue(Response.json({ error: "expired_token" }, { status: 400 }));
    globalThis.fetch = fetch;
    render(() => <DeviceLoginApproval endpoints={endpoints} />);
    const input = screen.getByRole("textbox", { name: "Device code" });
    expect(input).toHaveFocus();
    expect(input).toHaveAttribute("inputmode", "text");
    for (const value of ["b", "bcdfg", "bcdfghjk", "bcdfghjkmnp"]) {
      fireEvent.input(input, { target: { value } });
      fireEvent.submit(input.closest("form")!);
      await flush();
      expect(fetch).not.toHaveBeenCalled();
      expect(screen.getByRole("status").textContent).toBe("");
    }
    expect(input).toHaveValue("BCDF-GHJK-MNP");
    fireEvent.input(input, { target: { value: "bcdfghjkmnpqr" } });
    await flush();
    expect(fetch).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("Enter the 12-character code");
    fireEvent.input(input, { target: { value: "bcdf ghjk mnpq" } });
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    expect(input).toHaveValue("BCDF-GHJK-MNPQ");
    expect(JSON.parse(String(fetch.mock.calls[0]?.[1]?.body))).toEqual({ user_code: "BCDF-GHJK-MNPQ" });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("This code expired."));
    fireEvent.input(input, { target: { value: "BCDF-GHJK-MNP" } });
    expect(screen.getByRole("status").textContent).toBe("");
    expect(input).toHaveValue("BCDF-GHJK-MNP");
  });
  it("waits for composition to end and ignores duplicate formatted input", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(
      Response.json({
        user_code: code.user_code,
        ip: "192.0.2.0/24",
        user_agent: "Phone",
        created_at: new Date().toISOString(),
        expires_at: new Date(Date.now() + 600000).toISOString(),
        csrf_token: "csrf",
      }),
    );
    globalThis.fetch = fetch;
    render(() => <DeviceLoginApproval endpoints={endpoints} />);
    const input = screen.getByRole("textbox", { name: "Device code" });
    fireEvent.compositionStart(input);
    fireEvent.input(input, {
      target: { value: "bcdfghjkmnpq" },
      isComposing: true,
      inputType: "insertCompositionText",
    });
    await flush();
    expect(input).toHaveValue("bcdfghjkmnpq");
    expect(fetch).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve sign-in" })).toBeInTheDocument());
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(input).toHaveValue("BCDF-GHJK-MNPQ");
    fireEvent.input(input, { target: { value: "BCDF-GHJK-MNPQ - " } });
    fireEvent.submit(input.closest("form")!);
    await flush();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Approve sign-in" })).toBeInTheDocument();
    expect(input).toHaveValue("BCDF-GHJK-MNPQ");
  });
  it("does not interrupt composition with an earlier lookup result", async () => {
    let finish!: (response: Response) => void;
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValueOnce(Response.json({ error: "expired_token" }, { status: 400 }));
    globalThis.fetch = fetch;
    render(() => <DeviceLoginApproval endpoints={endpoints} />);
    const input = screen.getByRole("textbox", { name: "Device code" });
    fireEvent.input(input, { target: { value: code.user_code } });
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    fireEvent.compositionStart(input);
    fireEvent.input(input, {
      target: { value: code.user_code },
      isComposing: true,
      inputType: "insertCompositionText",
    });
    finish(
      Response.json({
        user_code: code.user_code,
        ip: "192.0.2.0/24",
        user_agent: "Phone",
        created_at: new Date().toISOString(),
        expires_at: new Date(Date.now() + 600000).toISOString(),
        csrf_token: "csrf",
      }),
    );
    await flush();
    expect(screen.queryByRole("button", { name: "Approve sign-in" })).toBeNull();
    expect(input).toHaveFocus();
    fireEvent.compositionEnd(input);
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("This code expired."));
  });
  it("offers an explicit retry after a throttled lookup", async () => {
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockResolvedValue(Response.json({ error: "rate_limited" }, { status: 429, headers: { "Retry-After": "60" } }));
    globalThis.fetch = fetch;
    render(() => <DeviceLoginApproval endpoints={endpoints} />);
    fireEvent.input(screen.getByRole("textbox", { name: "Device code" }), { target: { value: code.user_code } });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Wait 60 seconds"));
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
  it("lets touch keyboards delete across inserted separators in either direction", () => {
    const fetch = vi.fn<typeof globalThis.fetch>();
    globalThis.fetch = fetch;
    render(() => <DeviceLoginApproval endpoints={endpoints} />);
    const input = screen.getByRole("textbox", { name: "Device code" }) as HTMLInputElement;
    fireEvent.input(input, { target: { value: "bcdfg" }, inputType: "insertText" });
    expect(input).toHaveValue("BCDF-G");
    fireEvent.input(input, {
      target: { value: "BCDFG", selectionStart: 4, selectionEnd: 4 },
      inputType: "deleteContentBackward",
    });
    expect(input).toHaveValue("BCDG");
    expect(input.selectionStart).toBe(3);
    fireEvent.input(input, { target: { value: "bcdfgh" }, inputType: "insertText" });
    fireEvent.input(input, {
      target: { value: "BCDFGH", selectionStart: 4, selectionEnd: 4 },
      inputType: "deleteContentForward",
    });
    expect(input).toHaveValue("BCDF-H");
    expect(input.selectionStart).toBe(4);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("ignores a stale lookup when a complete code is corrected and offers a network retry", async () => {
    let finish!: (response: Response) => void;
    const fetch = vi
      .fn<typeof globalThis.fetch>()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          }),
      )
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(Response.json({ error: "expired_token" }, { status: 400 }));
    globalThis.fetch = fetch;
    render(() => <DeviceLoginApproval endpoints={endpoints} />);
    const input = screen.getByRole("textbox", { name: "Device code" });
    fireEvent.input(input, { target: { value: "BCDFGHJKMNPQ" } });
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    expect(input).not.toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("Checking code…");
    fireEvent.input(input, { target: { value: "BCDFGHJKMNP2" } });
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    finish(Response.json({ error: "expired_token" }, { status: 400 }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Could not check the code."));
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("This code expired."));
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
    await waitFor(() => expect(screen.getByText("Car browser")).toBeInTheDocument());
    expect(screen.getByText(/Never approve a code sent by someone else/)).toHaveFocus();
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
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Sign-in approved."));
    expect(screen.getByRole("status")).toHaveFocus();
    expect(screen.queryByRole("textbox")).toBeNull();
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
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve sign-in" })).toBeInTheDocument());
    const approve = screen.getByRole("button", { name: "Approve sign-in" });
    approve.focus();
    fireEvent.click(approve);
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Your decision could not be confirmed."));
    expect(screen.getByRole("textbox", { name: "Device code" })).toHaveFocus();
  });
});
