// Owns the shared lazy runtime import and recovery of its failed production stylesheets.

// Native module loading cannot be cancelled. Keep its single flight across UI
// attempts so a retired account cannot discard a still-loading stylesheet.
let loading: ReturnType<typeof importRuntime> | undefined;
const failedStyles = new Map<string, { template: HTMLLinkElement; position: Comment }>();

function rememberFailure(link: HTMLLinkElement) {
  const template = link.cloneNode(false) as HTMLLinkElement;
  template.nonce = link.nonce;
  const position = document.createComment("voice stylesheet retry");
  link.replaceWith(position);
  failedStyles.set(link.href, { template, position });
}

async function restoreStyles() {
  const results = await Promise.allSettled(
    [...failedStyles].map(
      ([url, resource]) =>
        new Promise<void>((resolve, reject) => {
          const link = resource.template.cloneNode(false) as HTMLLinkElement;
          link.nonce = resource.template.nonce;
          const cleanup = () => {
            link.removeEventListener("load", loaded);
            link.removeEventListener("error", failed);
          };
          const loaded = () => {
            cleanup();
            failedStyles.delete(url);
            resolve();
          };
          const failed = () => {
            cleanup();
            rememberFailure(link);
            reject(new Error("Voice stylesheet could not be loaded"));
          };
          link.addEventListener("load", loaded);
          link.addEventListener("error", failed);
          if (resource.position.isConnected) resource.position.replaceWith(link);
          else document.head.append(link);
        }),
    ),
  );
  const failure = results.find((result) => result.status === "rejected");
  if (failure?.status === "rejected") throw failure.reason;
}

async function importRuntime() {
  await restoreStyles();
  const before = new Set(document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]'));
  // Vite inserts the import's CSS preload links synchronously. Capture only
  // those identities in this turn, before unrelated host imports can run.
  // Production browser tests exercise this contract against emitted assets.
  const runtime = import("./BrowserVoiceRuntime");
  const owned = [...document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]')].filter(
    (link) => !before.has(link),
  );
  const failures = new Set<HTMLLinkElement>();
  const failed = (event: Event) => failures.add(event.currentTarget as HTMLLinkElement);
  // Capture before the bundler's target listener rejects the import: its
  // promise continuation can run before subsequent target listeners.
  for (const link of owned) link.addEventListener("error", failed, true);
  try {
    // Both hosts' production preload helpers settle all CSS dependencies before
    // this promise settles, including when one stylesheet fails.
    return await runtime;
  } finally {
    for (const link of owned) {
      link.removeEventListener("error", failed, true);
      if (failures.has(link)) rememberFailure(link);
    }
  }
}

export function loadBrowserVoiceRuntime() {
  if (!loading) {
    loading = importRuntime().finally(() => {
      loading = undefined;
    });
  }
  return loading;
}
