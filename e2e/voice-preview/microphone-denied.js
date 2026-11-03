// Force a denied microphone permission for the voice preview documentation capture.
//
// agent-browser reapplies its own permission state before every command, so a CDP
// Browser.setPermission override does not survive to the screenshot, and
// --deny-permission-prompts leaves navigator.permissions.query at "prompt". Answering
// the two Web APIs the voice overlay reads makes the denied notice deterministic,
// including on hosts without a capture device. Pass the file to
// `agent-browser --init-script`.
(() => {
  const status = {
    state: "denied",
    name: "microphone",
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent() {
      return false;
    },
  };
  const query = Permissions.prototype.query;
  Permissions.prototype.query = function queryPermission(descriptor) {
    return descriptor?.name === "microphone" ? Promise.resolve(status) : query.call(this, descriptor);
  };
  MediaDevices.prototype.getUserMedia = () => Promise.reject(new DOMException("Permission denied", "NotAllowedError"));
})();
