# Go Mode users sign in with any provider through their browser

caic stops leaking session tokens in URLs, and the Android shell signs in to
caic and mddb through an Auth Tab. Design: [NATIVE_LOGIN.md](NATIVE_LOGIN.md).

```mermaid
flowchart LR
    B[caic-app-login-removal] --> E[caic-login-handoff]
    C[shell-login-handoff] --> D[mddb-login-handoff]
    C --> E
```

## Phase 1 — caic-app-login-removal: Stop putting caic JWTs in custom-scheme URLs

- **Depends on:** none
- **Scope:** Delete `return=app` login and its `caic://auth?token=` redirect. No
  client in caic or gomode uses it, and Go Mode does not register `caic://`.
- **Verify:** `return=app` yields a 400, web login with `next` still works, and
  no `caic://auth` redirect remains.

## Phase 2 — shell-login-handoff: Hand a browser login to the WebView

- **Depends on:** none
- **Scope:** The contract in [NATIVE_LOGIN.md](NATIVE_LOGIN.md#contract): a Go
  one-time code store with S256 verification for hosts, the web host-mode
  capability check, the shell's `externalLogin` message and Auth Tab launch
  with Custom Tab fallback, and the standalone hosted fixture. Settle the
  [redirect form](NATIVE_LOGIN.md#open-question-redirect-form) first and
  update the design.
- **Preserve:** Shells and pages without the capability keep WebView login.
  The existing `gomodeAuth` messages keep their meaning.
- **Verify:** Go tests cover single use, expiry, and verifier mismatch. Shell
  tests reject cross-origin start and `continue` paths. `make android-e2e`
  completes a fixture login, and a device run completes it in Chrome 137+ (Auth
  Tab) and in a browser without Auth Tab (Custom Tab).

## Phase 3 — mddb-login-handoff: Sign in to mddb from the shell

- **Depends on:** shell-login-handoff and a published Go Mode release
- **Scope:** mddb OAuth start and callback honor `gomode_challenge`, an
  exchange route redeems the code and establishes the session as the web
  callback does, and the login page sends `externalLogin` when the shell
  supports it.
- **Verify:** On a device, the shell signs in to mddb with GitHub and Google
  without typing a GitHub password when the browser holds a session. A replayed
  or verifier-less exchange fails.

## Phase 4 — caic-login-handoff: Sign in to caic from the shell

- **Depends on:** caic-app-login-removal, shell-login-handoff, and a published
  Go Mode release
- **Scope:** The same host changes for caic's GitHub, GitLab, and Google login;
  the exchange sets caic's session cookie.
- **Verify:** On a device, the shell signs in to caic with GitHub and Google. A
  replayed or verifier-less exchange fails.

## Later

- mddb shows OAuth callback errors as raw JSON; the login page ignores
  `oauth_error`.
- mddb OAuth identities stored before the verified-email rule keep their
  unverified emails, which git authorship still prefers.
