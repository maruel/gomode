// Renders shared official Material Symbols for browser voice controls.

import styles from "./VoiceControls.module.css";

type VoiceIconName =
  | "arrowUpward"
  | "arrowDownward"
  | "dataUsage"
  | "history"
  | "build"
  | "cached"
  | "psychology"
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
  | "subtitles"
  | "swapHoriz"
  | "usb"
  | "volumeUp";

export default function VoiceIcon(props: { name: VoiceIconName }) {
  return <span class={`${styles.icon} ${styles[props.name]}`} data-icon={props.name} aria-hidden="true" />;
}
