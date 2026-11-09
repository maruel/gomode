// Owns audio device discovery, desired routes, and cancellation-bound route replacement.

import type { MicrophoneInput } from "./MicrophoneInput";
import type { SpeechLocation } from "../../sdk/voicegateway/ts/v2/types.gen";

/** One browser audio input or output device. */
export interface AudioDevice {
  deviceId: string;
  kind: MediaDeviceKind;
  label: string;
}

/** Reactive route feedback exposed by the voice session. */
export interface AudioRouteState {
  audioInputs: AudioDevice[];
  audioOutputs: AudioDevice[];
  selectedInputId: string;
  selectedOutputId: string;
  audioDevicesError: string | null;
  audioInputError: string | null;
  audioOutputError: string | null;
  audioSwitching: "input" | "output" | null;
  speechInput: SpeechLocation;
  speechOutput: SpeechLocation;
}

interface AudioRouteConnection {
  lifetime: AbortController;
  pc: RTCPeerConnection | null;
  microphone: MediaStream | null;
  outgoing: ReturnType<MicrophoneInput["connect"]> | null;
  speaker: HTMLAudioElement | null;
}

interface AudioRouteHost {
  state: AudioRouteState;
  update(change: (state: AudioRouteState) => void): void;
  connection(): AudioRouteConnection | null;
  input: MicrophoneInput;
  microphoneActive(): boolean;
  microphoneMuted(): boolean;
  monitorMicrophone(stream: MediaStream): void;
  microphoneDenied(): void;
}

interface QueuedRoute {
  deviceId: string;
  source: "manual" | "automatic" | "recovery";
}
interface Route {
  queued: QueuedRoute | null;
  provisional: boolean;
}
interface RouteOperation {
  kind: "input" | "output";
  request: QueuedRoute | null;
}
interface PendingMicrophoneCapture {
  stream: MediaStream;
  outgoing: ReturnType<MicrophoneInput["connect"]> | null;
}

// bluetoothAudioDeviceLabel recognizes explicit Bluetooth and common AirPods labels.
// Browsers do not expose audio transport types.
export function bluetoothAudioDeviceLabel(label: string): boolean {
  return /\b(bluetooth|airpods|bt)\b/i.test(label);
}

// Opaque Bluetooth device names are restored by their saved device ID.
function bluetoothAudioDevice(device: AudioDevice, rememberedId: string | null): boolean {
  return (
    device.deviceId !== "default" &&
    device.deviceId !== "communications" &&
    (device.deviceId === rememberedId || bluetoothAudioDeviceLabel(device.label))
  );
}

type AudioDevicePreference = "BluetoothInput" | "BluetoothOutput" | "Input" | "Output";

function savedAudioDevice(kind: AudioDevicePreference): string | null {
  try {
    return localStorage.getItem(`gomode.voice${kind}Device`);
  } catch (error) {
    console.warn("Could not load voice audio device", error);
    return null;
  }
}

function rememberAudioDevice(kind: AudioDevicePreference, deviceId: string): void {
  try {
    if (deviceId) localStorage.setItem(`gomode.voice${kind}Device`, deviceId);
    else localStorage.removeItem(`gomode.voice${kind}Device`);
  } catch (error) {
    console.warn("Could not save voice audio device", error);
  }
}

/** Coordinates both route directions and publishes only committed replacements. */
export class AudioRoutes {
  private readonly routes: Record<"input" | "output", Route> = {
    input: { queued: null, provisional: false },
    output: { queued: null, provisional: false },
  };
  private operation: RouteOperation | null = null;
  private enumeration: object | null = null;

  constructor(private readonly host: AudioRouteHost) {}

  /** Reserve routing while the transport installs its first microphone stream. */
  beginCapture(): (deviceId: string) => void {
    const operation = this.begin("input", null);
    if (operation === null) throw new Error("Audio route replacement is already pending");
    return (deviceId) => {
      if (this.operation !== operation) return;
      if (this.routes.input.provisional) rememberAudioDevice("Input", deviceId);
      this.routes.input.provisional = false;
      this.finish(operation);
    };
  }

  /** Apply the remembered or provisional speaker route to new remote audio. */
  speakerAttached(): void {
    if (this.host.state.selectedOutputId || this.routes.output.provisional) {
      this.routes.output.queued ??= { deviceId: this.host.state.selectedOutputId, source: "automatic" };
      void this.applyPending();
    }
  }

  /** Retire queued work and stale discovery/replacement completions. */
  reset(): void {
    this.enumeration = null;
    this.operation = null;
    this.routes.input.queued = null;
    this.routes.output.queued = null;
    this.host.update((s) => {
      s.audioSwitching = null;
    });
  }

  /** A manual request remains queued while the other direction is switching. */
  async selectInputDevice(deviceId: string): Promise<void> {
    if (this.host.state.speechInput !== "gateway") return;
    this.routes.input.queued = { deviceId, source: "manual" };
    await this.applyPending();
  }

  /** A speaker preference becomes durable only after its sink is installed. */
  async selectOutputDevice(deviceId: string): Promise<void> {
    if (this.host.state.speechOutput !== "gateway") return;
    this.routes.output.queued = { deviceId, source: "manual" };
    await this.applyPending();
  }

  private begin(kind: RouteOperation["kind"], request: QueuedRoute | null): RouteOperation | null {
    if (this.operation !== null) return null;
    const operation = { kind, request };
    this.operation = operation;
    this.host.update((s) => {
      s.audioSwitching = kind;
    });
    return operation;
  }

  private finish(operation: RouteOperation): void {
    if (this.operation !== operation) return;
    this.operation = null;
    this.host.update((s) => {
      s.audioSwitching = null;
    });
    void this.applyPending();
  }

  private current(operation: RouteOperation, connection: AudioRouteConnection | null): boolean {
    return this.operation === operation && this.host.connection() === connection;
  }

  private async applyPending(): Promise<void> {
    if (this.operation !== null) return;
    const output = this.routes.output.queued;
    this.routes.output.queued = null;
    const speaker = this.host.connection()?.speaker ?? null;
    if (
      output !== null &&
      (output.source === "manual" ||
        output.deviceId !== this.host.state.selectedOutputId ||
        this.routes.output.provisional ||
        speaker?.sinkId !== output.deviceId)
    ) {
      await this._selectOutputDevice(output.deviceId, output.source);
    }
    // An awaited sink can finish into another queued operation before returning.
    // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
    if (this.operation !== null) return;
    const input = this.routes.input.queued;
    this.routes.input.queued = null;
    if (input !== null && (input.deviceId !== this.host.state.selectedInputId || input.source === "manual"))
      await this._selectInputDevice(input.deviceId, input.source);
  }

  /** Enumerate available audio devices and auto-select defaults. Call before connect(). */
  async enumerateDevices(): Promise<void> {
    const attempt = {};
    this.enumeration = attempt;
    // In insecure contexts and unsupported browsers there is no device API.
    // The microphone control owns the capability warning and recovery guidance.
    const mediaDevices = (navigator as { mediaDevices?: Partial<MediaDevices> }).mediaDevices;
    if (!mediaDevices?.enumerateDevices) return;
    try {
      const devices = await mediaDevices.enumerateDevices();
      if (attempt !== this.enumeration) return;
      const previousInputs = this.host.state.audioInputs;
      const previousOutputs = this.host.state.audioOutputs;
      const inputs: AudioDevice[] = [];
      const outputs: AudioDevice[] = [];
      for (const d of devices) {
        if (d.kind === "audioinput") {
          inputs.push({
            deviceId: d.deviceId,
            kind: d.kind,
            label: d.label || "Microphone",
          });
        } else if (d.kind === "audiooutput") {
          outputs.push({
            deviceId: d.deviceId,
            kind: d.kind,
            label: d.label || "Speaker",
          });
        }
      }
      this.host.update((s) => {
        s.audioInputs = inputs;
        s.audioOutputs = outputs;
        s.audioDevicesError = null;
      });
      for (const kind of ["input", "output"] as const) {
        const queued = this.routes[kind].queued;
        const available = kind === "input" ? inputs : outputs;
        if (queued?.deviceId && !available.some((d) => d.deviceId === queued.deviceId)) this.routes[kind].queued = null;
      }
      // Only arrivals override a manual selection. Re-enumeration alone leaves it alone.
      const preferred = (
        available: AudioDevice[],
        previous: AudioDevice[],
        current: string,
        kind: "Input" | "Output",
      ) => {
        const rememberedBluetooth = savedAudioDevice(`Bluetooth${kind}`);
        const bluetooth = available.filter((d) => bluetoothAudioDevice(d, rememberedBluetooth));
        const arrived = bluetooth.find(
          (d) => !previous.some((old) => old.deviceId === d.deviceId && bluetoothAudioDevice(old, rememberedBluetooth)),
        );
        const lastBluetooth = arrived ?? bluetooth[0];
        if (lastBluetooth) rememberAudioDevice(`Bluetooth${kind}`, lastBluetooth.deviceId);
        const direction = kind === "Input" ? "input" : "output";
        const route = this.routes[direction];
        const requested =
          route.queued !== null || (this.operation?.kind === direction && this.operation.request !== null);
        return (
          arrived?.deviceId ??
          (!current && !route.provisional && !requested
            ? available.find((d) => d.deviceId === savedAudioDevice(kind))?.deviceId
            : undefined)
        );
      };
      const output = preferred(outputs, previousOutputs, this.host.state.selectedOutputId, "Output");
      const input = preferred(inputs, previousInputs, this.host.state.selectedInputId, "Input");
      if (output !== undefined) this.routes.output.queued = { deviceId: output, source: "automatic" };
      if (input !== undefined) this.routes.input.queued = { deviceId: input, source: "automatic" };
      const curOut = this.host.state.selectedOutputId;
      if (curOut && !outputs.some((d) => d.deviceId === curOut)) {
        if (this.operation !== null) {
          // An output switch already owns recovery and will report its outcome.
          if (this.operation.kind !== "output")
            this.host.update((s) => {
              s.audioOutputError = "Speaker disconnected. Choose another speaker.";
            });
        } else {
          await this._selectOutputDevice("", "recovery");
        }
      }
      const curSel = this.host.state.selectedInputId;
      if (attempt !== this.enumeration) return;
      if (curSel && !inputs.some((d) => d.deviceId === curSel)) {
        if (this.operation !== null) {
          // Preserve warnings about the other route while this switch completes.
          if (this.operation.kind !== "input")
            this.host.update((s) => {
              s.audioInputError = "Microphone disconnected. Choose another microphone.";
            });
        } else {
          // Keep the displayed choice until a replacement track is working.
          await this._selectInputDevice("", "recovery");
        }
      }
      if (attempt !== this.enumeration) return;
      await this.applyPending();
    } catch (error) {
      if (attempt !== this.enumeration) return;
      this.host.update((s) => {
        s.audioDevicesError = `Could not list audio devices: ${String(error)}`;
      });
    }
  }

  private async _selectInputDevice(deviceId: string, source: QueuedRoute["source"]): Promise<void> {
    if (this.host.state.speechInput !== "gateway") return;
    const selection = this.begin("input", { deviceId, source });
    if (selection === null) return;
    const connection = this.host.connection();
    const pc = connection?.pc;
    const oldStream = connection?.microphone;
    if (connection && pc && oldStream) {
      this.host.update((s) => {
        s.audioInputError = null;
      });
      let pending: PendingMicrophoneCapture | null = null;
      const releasePending = () => {
        const capture = pending;
        pending = null;
        capture?.outgoing?.close();
        capture?.stream.getTracks().forEach((track) => track.stop());
      };
      const signal = connection.lifetime.signal;
      signal.addEventListener("abort", releasePending, { once: true });
      try {
        // Acquire new mic stream with the selected device.
        const constraints: MediaStreamConstraints = {
          audio: deviceId ? { deviceId: { exact: deviceId } } : true,
          video: false,
        };
        if (!this.current(selection, connection)) return;
        const newStream = await navigator.mediaDevices.getUserMedia(constraints);
        if (!this.current(selection, connection)) {
          newStream.getTracks().forEach((track) => track.stop());
          return;
        }
        // Until installation commits, cancellation owns the pending capture
        // and graph while the previous working input remains installed.
        const capture: PendingMicrophoneCapture = { stream: newStream, outgoing: null };
        pending = capture;
        newStream.getAudioTracks().forEach((track) => {
          track.enabled = false;
        });
        const outgoing = this.host.input.connect(newStream);
        capture.outgoing = outgoing;
        // Replace tracks on the PeerConnection.
        const sender = pc.getSenders().find((s) => s.track?.kind === "audio");
        for (const t of outgoing.stream.getAudioTracks()) {
          // Keep the pending sender silent until installation can apply the current mode.
          t.enabled = false;
          if (sender) {
            await sender.replaceTrack(t);
            if (!this.current(selection, connection)) return;
          } else {
            pc.addTrack(t, outgoing.stream);
          }
        }
        if (!this.current(selection, connection)) return;
        newStream.getAudioTracks().forEach((track) => {
          track.enabled = !this.host.microphoneMuted();
        });
        outgoing.stream.getAudioTracks().forEach((track) => {
          track.enabled = this.host.microphoneActive();
        });
        const previous = connection.outgoing;
        connection.outgoing = outgoing;
        connection.microphone = newStream;
        pending = null;
        previous?.close();
        oldStream.getTracks().forEach((track) => track.stop());
        this.host.update((s) => {
          s.selectedInputId = deviceId;
          s.audioInputError = null;
        });
        if (!this.current(selection, connection)) return;
        if (source !== "recovery") rememberAudioDevice("Input", deviceId);
        this.routes.input.provisional = false;
        this.host.monitorMicrophone(newStream);
      } catch (error) {
        releasePending();
        if (this.current(selection, connection) && error instanceof DOMException && error.name === "NotAllowedError") {
          this.host.microphoneDenied();
          return;
        }
        if (this.current(selection, connection))
          this.host.update((s) => {
            s.audioInputError = `Could not switch microphone: ${String(error)}`;
          });
      } finally {
        signal.removeEventListener("abort", releasePending);
        this.finish(selection);
      }
    } else {
      this.host.update((s) => {
        s.selectedInputId = deviceId;
        s.audioInputError = null;
      });
      this.routes.input.provisional = source !== "recovery";
      this.finish(selection);
    }
  }

  private async _selectOutputDevice(deviceId: string, source: QueuedRoute["source"]): Promise<void> {
    if (this.host.state.speechOutput !== "gateway") return;
    const selection = this.begin("output", { deviceId, source });
    if (selection === null) return;
    const audio = this.host.connection()?.speaker ?? null;
    this.host.update((s) => {
      s.audioOutputError = null;
    });
    try {
      if (!("setSinkId" in HTMLMediaElement.prototype))
        throw new Error("Use your device's sound settings to change the speaker");
      if (audio) await audio.setSinkId(deviceId);
      if ((this.host.connection()?.speaker ?? null) !== audio || selection !== this.operation) return;
      this.host.update((s) => {
        s.selectedOutputId = deviceId;
        s.audioOutputError = null;
      });
      // Before remote audio exists, this is a provisional route selection.
      if (audio) {
        if (source !== "recovery") rememberAudioDevice("Output", deviceId);
        this.routes.output.provisional = false;
      } else {
        this.routes.output.provisional = source !== "recovery";
      }
    } catch (error) {
      if ((this.host.connection()?.speaker ?? null) === audio && selection === this.operation)
        this.host.update((s) => {
          s.audioOutputError = `Could not switch speaker: ${String(error)}`;
        });
    } finally {
      this.finish(selection);
    }
  }
}
