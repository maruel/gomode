// Verifies browser route hints ignore bandwidth estimates and release all event listeners.
import { it } from "node:test";
import { expect, vi } from "../tests/expect";
import { observeNetworkChanges } from "./NetworkChanges";

it("observes routes and reachability without reconnecting for quality estimates", (t) => {
  class Connection extends EventTarget {
    type = "wifi";
  }
  const connection = new Connection();
  Object.defineProperty(navigator, "connection", { configurable: true, value: connection });
  t.after(() => {
    Reflect.deleteProperty(navigator, "connection");
  });
  const changed = vi.fn();
  const stop = observeNetworkChanges(changed);
  connection.dispatchEvent(new Event("change"));
  expect(changed).not.toHaveBeenCalled();
  connection.type = "cellular";
  connection.dispatchEvent(new Event("change"));
  expect(changed).toHaveBeenCalledTimes(1);
  window.dispatchEvent(new window.Event("offline"));
  window.dispatchEvent(new window.Event("online"));
  expect(changed).toHaveBeenCalledTimes(3);
  stop();
  connection.type = "wifi";
  connection.dispatchEvent(new Event("change"));
  window.dispatchEvent(new window.Event("online"));
  expect(changed).toHaveBeenCalledTimes(3);
});
