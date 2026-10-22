// Renders shared official Material Symbols for browser voice controls.

import styles from "./VoiceControls.module.css";

type VoiceIconName =
  | "bluetooth"
  | "callEnd"
  | "close"
  | "headphones"
  | "mic"
  | "micOff"
  | "phoneInTalk"
  | "playArrow"
  | "refresh"
  | "schedule"
  | "settings"
  | "speaker"
  | "stop"
  | "usb"
  | "volumeUp";

export default function VoiceIcon(props: { name: VoiceIconName }) {
  return <span class={`${styles.icon} ${styles[props.name]}`} data-icon={props.name} aria-hidden="true" />;
}
