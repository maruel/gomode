#!/bin/bash
# Install the latest checksummed voice gateway release and start its per-user service.

set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}
(($# == 0)) || fail 'This installer takes no arguments.'
case "$(uname -s)" in
Linux) platform=linux ;;
Darwin) platform=darwin ;;
*) fail 'This installer supports Linux and macOS.' ;;
esac
case "$(uname -m)" in
x86_64 | amd64) arch=amd64 ;;
aarch64 | arm64) arch=arm64 ;;
*) fail 'Supported architectures are amd64 and arm64.' ;;
esac
config_home="${XDG_CONFIG_HOME:-${HOME:?HOME must be set}/.config}"
for path in "$HOME" "$config_home"; do
  [[ "$path" == /* ]] || fail 'HOME and XDG_CONFIG_HOME must be absolute paths.'
  if printf '%s' "$path" | LC_ALL=C grep -q '[[:cntrl:]]'; then fail 'User paths cannot contain control characters.'; fi
done
install_dir="$HOME/.local/bin"
binary_path="$install_dir/voice-gateway"
config_path="$config_home/voice-gateway/config.toml"
if [[ "$platform" == linux ]]; then
  env_file="$HOME/.config/systemd/user/voice-gateway.env"
  service_path="$config_home/systemd/user/voice-gateway.service"
  command -v systemctl >/dev/null || fail 'systemctl is required.'
  systemctl --user show-environment >/dev/null
else
  env_file="$config_home/voice-gateway/voice-gateway.env"
  service_path="$HOME/Library/LaunchAgents/com.maruel.voice-gateway.plist"
  log_file="$HOME/Library/Logs/voice-gateway.log"
  command -v launchctl >/dev/null || fail 'launchctl is required.'
fi
destinations=("$binary_path" "$service_path")
if [[ "$platform" == darwin ]]; then destinations+=("$log_file"); fi
for path in "$config_path" "$env_file" "${destinations[@]}"; do
  [[ ! -e "$path" || -f "$path" ]] || fail "Expected a file: $path"
done
# Service/log writes must never follow aliases to preserved settings.
for path in "${destinations[@]}"; do
  if [[ "$path" -ef "$config_path" || "$path" -ef "$env_file" ]]; then fail 'An installation destination aliases configuration or credentials.'; fi
done
command -v curl >/dev/null || fail 'curl is required.'
if command -v sha256sum >/dev/null; then hash_command=(sha256sum); else hash_command=(shasum -a 256); fi
work_dir="$(mktemp -d)"
readonly work_dir
binary_temp=''
publishing=false
cleanup() {
  status=$?
  trap - EXIT
  rm -rf -- "$work_dir"
  if [[ -n "$binary_temp" ]]; then rm -f -- "$binary_temp"; fi
  if ((status != 0)) && "$publishing"; then printf '%s\n' 'Installation failed after publication began; copied files remain installed. Correct the error and rerun.' >&2; fi
  exit "$status"
}
trap cleanup EXIT
resolved="$(curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --head --output /dev/null --write-out '%{url_effective}' https://github.com/maruel/gomode/releases/latest)"
version="${resolved##*/}"
release_tag_pattern='^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
[[ "$version" =~ $release_tag_pattern ]] || fail 'The latest stable release could not be resolved.'
asset="voice-gateway_${platform}_${arch}.tar.gz"
base="https://github.com/maruel/gomode/releases/download/$version"
archive="$work_dir/$asset"
checksums="$work_dir/checksums.txt"
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' "$base/$asset" --output "$archive"
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' "$base/checksums.txt" --output "$checksums"
expected_hash=''
matches=0
while IFS= read -r line || [[ -n "$line" ]]; do
  hash="${line:0:64}"
  if [[ "$line" == "$hash  $asset" && "$hash" =~ ^[[:xdigit:]]{64}$ ]]; then
    expected_hash="$hash"
    matches=$((matches + 1))
  fi
done <"$checksums"
((matches == 1)) || fail "Expected exactly one SHA-256 entry for $asset"
expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
actual_hash="$("${hash_command[@]}" <"$archive")"
actual_hash="${actual_hash%% *}"
[[ "$actual_hash" == "$expected_hash" ]] || fail 'Archive SHA-256 checksum mismatch.'
printf '%s\n' LICENSE config.toml voice-gateway voice-gateway.env.example "voice-gateway.$([[ "$platform" == linux ]] && printf service || printf plist)" | LC_ALL=C sort >"$work_dir/expected"
tar -tzf "$archive" | LC_ALL=C sort >"$work_dir/members"
cmp -s "$work_dir/expected" "$work_dir/members" || fail 'Archive must contain exactly the expected release files at its root.'
# Reject links and special files before extraction, including links with valid member names.
tar -tvzf "$archive" >"$work_dir/types"
while IFS= read -r line; do
  [[ "$line" == -* ]] || fail 'Archive members must be regular files.'
done <"$work_dir/types"
mkdir "$work_dir/files"
tar -xzf "$archive" -C "$work_dir/files"
[[ -s "$work_dir/files/voice-gateway" && -x "$work_dir/files/voice-gateway" ]] || fail 'Archive binary must be nonempty and executable.'

xml_value() { printf '%s' "$1" | sed 's/\&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'; }
if [[ "$platform" == linux ]]; then
  # The template owns fixed %h paths; only XDG discovery comes from this process.
  config_environment="$(printf '%s' "$config_home" | sed 's/\\/\\\\/g; s/"/\\"/g; s/%/%%/g')"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    printf '%s\n' "$line"
    if [[ "$line" == '[Service]' ]]; then printf 'Environment="XDG_CONFIG_HOME=%s"\n' "$config_environment"; fi
  done <"$work_dir/files/voice-gateway.service" >"$work_dir/service"
else
  # Expand the original owning lines once; inserted user paths remain literal.
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    case "$line" in
    '        <string>~/.local/bin/voice-gateway</string>') value="$binary_path" ;;
    '        <string>~/.config</string>') value="$config_home" ;;
    '        <string>~/.config/voice-gateway/voice-gateway.env</string>') value="$env_file" ;;
    '        <string>~/.local/bin:~/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>') value="$install_dir:$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin" ;;
    '    <string>~/Library/Logs/voice-gateway.log</string>')
      printf '    <string>%s</string>\n' "$(xml_value "$log_file")"
      continue
      ;;
    *)
      printf '%s\n' "$line"
      continue
      ;;
    esac
    printf '        <string>%s</string>\n' "$(xml_value "$value")"
  done <"$work_dir/files/voice-gateway.plist" >"$work_dir/service"
  plutil -lint "$work_dir/service" >/dev/null
fi

publishing=true
mkdir -p -- "$install_dir" "$(dirname -- "$config_path")" "$(dirname -- "$env_file")"
if [[ ! -e "$config_path" ]]; then (
  set -o noclobber
  cat "$work_dir/files/config.toml" >"$config_path"
); fi
if [[ ! -e "$env_file" ]]; then (
  set -o noclobber
  cat "$work_dir/files/voice-gateway.env.example" >"$env_file"
); fi
binary_temp="$(mktemp "$install_dir/.voice-gateway.XXXXXX")"
install -m 755 "$work_dir/files/voice-gateway" "$binary_temp"
mv -f -- "$binary_temp" "$binary_path"
binary_temp=''
mkdir -p -- "$(dirname -- "$service_path")"
install -m 600 "$work_dir/service" "$service_path"
if [[ "$platform" == linux ]]; then
  systemctl --user daemon-reload
  systemctl --user enable voice-gateway.service
  systemctl --user restart voice-gateway.service
else
  mkdir -p -- "$HOME/Library/Logs"
  domain="gui/$(id -u)"
  if launchctl print "$domain/com.maruel.voice-gateway" >/dev/null 2>&1; then launchctl bootout "$domain/com.maruel.voice-gateway"; fi
  launchctl bootstrap "$domain" "$service_path"
fi
printf 'Installed: %s\nConfiguration: %s\nCredentials: %s\n' "$binary_path" "$config_path" "$env_file"
