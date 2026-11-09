#!/bin/bash
# Install checksummed voice gateway archives and manage per-user systemd or launchd services.

set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'HELP'
Usage: install.sh [--version vX.Y.Z | --archive /path/to/archive]
                  [--checksums /path/to/checksums.txt] [--no-service]
                  [--install-dir /absolute/bin] [--config-path /absolute/config.toml]
                  [--env-file /absolute/voice-gateway.env]

Download the latest stable release by default. Local archives use checksums.txt
beside the archive unless --checksums is supplied. Existing config and credentials
are preserved. --no-service leaves service registration and state unchanged.
HELP
}

version=''
release_tag_pattern='^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
archive=''
checksums=''
install_dir="${HOME:?HOME must be set}/.local/bin"
config_path="${XDG_CONFIG_HOME:-$HOME/.config}/voice-gateway/config.toml"
default_config_path="$config_path"
env_file=''
service=true
while (($#)); do
  case "$1" in
  --version | --archive | --checksums | --install-dir | --config-path | --env-file)
    if (($# < 2)) || [[ -z "$2" || "$2" == --* ]]; then fail "Missing value for $1"; fi
    case "$1" in
    --version) version="$2" ;;
    --archive) archive="$2" ;;
    --checksums) checksums="$2" ;;
    --install-dir) install_dir="$2" ;;
    --config-path) config_path="$2" ;;
    --env-file) env_file="$2" ;;
    esac
    shift 2
    ;;
  --no-service)
    service=false
    shift
    ;;
  --help | -h)
    usage
    exit 0
    ;;
  *) fail "Unknown argument: $1" ;;
  esac
done
[[ -z "$archive" || -z "$version" ]] || fail '--archive and --version are mutually exclusive.'
[[ -n "$archive" || -z "$checksums" ]] || fail '--checksums requires --archive.'
if [[ -n "$version" ]]; then
  [[ "$version" =~ $release_tag_pattern ]] || fail 'Version must be a full release tag, such as v0.4.0.'
fi
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
if [[ -z "$env_file" ]]; then
  if [[ "$platform" == linux ]]; then
    env_file="$HOME/.config/systemd/user/voice-gateway.env"
  else
    env_file="$(dirname -- "$config_path")/voice-gateway.env"
  fi
fi
for path in "$HOME" "$install_dir" "$config_path" "$env_file" "${XDG_CONFIG_HOME:-$HOME/.config}"; do
  [[ "$path" == /* ]] || fail "Path must be absolute: $path"
  if printf '%s' "$path" | LC_ALL=C grep -q '[[:cntrl:]]'; then
    fail 'Paths cannot contain control characters.'
  fi
done

# Resolve existing directory aliases without creating installation directories.
# Missing suffixes may contain new names, but not unresolved dot segments.
canonical_path() {
  local path="$1" suffix='' component
  while [[ ! -d "$path" ]]; do
    component="$(basename -- "$path")"
    case "$component" in . | ..) fail 'Use an unambiguous path without dot segments beneath missing directories.' ;; esac
    suffix="/$component$suffix"
    path="$(dirname -- "$path")"
  done
  path="$(CDPATH='' cd -P -- "$path" && pwd -P)" || fail 'Cannot resolve the destination directory.'
  path="${path%/}$suffix"
  if printf '%s' "$path" | LC_ALL=C grep -q '[[:cntrl:]]'; then fail 'Resolved paths cannot contain control characters.'; fi
  printf '%s\n' "${path:-/}"
}

same_path() {
  [[ "$1" == "$2" || "$1" -ef "$2" ]] && return 0
  if [[ "$platform" == darwin ]]; then
    [[ "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" == "$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')" ]] && return 0
  fi
  return 1
}

install_dir="$(canonical_path "$install_dir")"
config_path="$(canonical_path "$config_path")"
env_file="$(canonical_path "$env_file")"
binary_path="${install_dir%/}/voice-gateway"
for path in "$config_path" "$env_file" "$binary_path"; do
  [[ ! -e "$path" || -f "$path" ]] || fail "Expected a file: $path"
done
if same_path "$config_path" "$env_file" || same_path "$config_path" "$binary_path" || same_path "$env_file" "$binary_path"; then
  fail 'Binary, config, and credentials paths must differ, including aliases.'
fi
if "$service"; then
  if [[ "$platform" == linux ]]; then
    service_path="$(canonical_path "${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/voice-gateway.service")"
    command -v systemctl >/dev/null || fail 'systemctl is required; use --no-service to install files only.'
    if [[ "$install_dir" == *"'"* || "$install_dir" == *'"'* || "$install_dir" == *\\* ]]; then
      fail 'systemd executable paths cannot contain quotes or backslashes; choose another --install-dir or use --no-service.'
    fi
    if [[ "$env_file" == *\\* || "$env_file" == *'*'* || "$env_file" == *'?'* || "$env_file" == *'['* || "$env_file" == *']'* ]]; then
      fail 'systemd credentials paths cannot contain backslashes or glob characters; choose another --env-file or use --no-service.'
    fi
  else
    service_path="$(canonical_path "$HOME/Library/LaunchAgents/com.maruel.voice-gateway.plist")"
    log_file="$(canonical_path "$HOME/Library/Logs/voice-gateway.log")"
    if same_path "$config_path" "$log_file" || same_path "$env_file" "$log_file"; then fail 'Configuration and credentials cannot use the service log path.'; fi
    command -v launchctl >/dev/null || fail 'launchctl is required; use --no-service to install files only.'
  fi
  if same_path "$config_path" "$service_path" || same_path "$env_file" "$service_path" || same_path "$binary_path" "$service_path"; then fail 'Installed files cannot use the service definition path.'; fi
fi
asset="voice-gateway_${platform}_${arch}.tar.gz"
if [[ -n "$archive" ]]; then
  [[ "$archive" == /* && -f "$archive" ]] || fail '--archive must name an existing absolute file path.'
  [[ "$(basename -- "$archive")" == "$asset" ]] || fail "Expected archive basename: $asset"
  checksums="${checksums:-$(dirname -- "$archive")/checksums.txt}"
  [[ "$checksums" == /* && -f "$checksums" ]] || fail 'Checksums must name an existing absolute file path.'
else
  command -v curl >/dev/null || fail 'curl is required to download releases.'
fi
if command -v sha256sum >/dev/null; then
  hash_command=(sha256sum)
else
  command -v shasum >/dev/null || fail 'sha256sum or shasum is required.'
  hash_command=(shasum -a 256)
fi
work_dir="$(mktemp -d)"
readonly work_dir
binary_temp=''
publishing=false
cleanup() {
  status=$?
  trap - EXIT
  rm -rf -- "$work_dir"
  if [[ -n "$binary_temp" ]]; then rm -f -- "$binary_temp"; fi
  if ((status != 0)) && "$publishing"; then
    printf '%s\n' 'Installation failed after publication began; copied files remain installed. Correct the error and rerun.' >&2
  fi
  exit "$status"
}
trap cleanup EXIT
if [[ -n "$archive" ]]; then
  cp -- "$archive" "$work_dir/$asset"
  cp -- "$checksums" "$work_dir/checksums.txt"
  archive="$work_dir/$asset"
  checksums="$work_dir/checksums.txt"
else
  if [[ -z "$version" ]]; then
    resolved="$(curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --head --output /dev/null --write-out '%{url_effective}' https://github.com/maruel/gomode/releases/latest)"
    version="${resolved##*/}"
    [[ "$version" =~ $release_tag_pattern ]] || fail 'No stable binary release resolved; use --archive for a local snapshot.'
  fi
  base="https://github.com/maruel/gomode/releases/download/$version"
  archive="$work_dir/$asset"
  checksums="$work_dir/checksums.txt"
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' "$base/$asset" --output "$archive"
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' "$base/checksums.txt" --output "$checksums"
fi
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
systemd_value() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/%/%%/g'; }
if "$service"; then
  if [[ "$platform" == linux ]]; then
    # ':' disables systemd environment substitution, keeping literal dollar signs.
    exec_start=":\"$(systemd_value "$binary_path")\""
    if ! same_path "$config_path" "$(canonical_path "$HOME/.config/voice-gateway/config.toml")"; then
      exec_start="$exec_start -config \"$(systemd_value "$config_path")\""
    fi
    service_search_path="$(systemd_value "$install_dir:$HOME/.cargo/bin:/usr/local/bin:/usr/bin:/bin")"
    service_env_file="$(printf '%s' "$env_file" | sed 's/%/%%/g')"
    # Read original lines once: inserted values can themselves contain tokens.
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%$'\r'}"
      case "$line" in
      ExecStart=*) printf 'ExecStart=%s\n' "$exec_start" ;;
      'Environment="PATH='*) printf 'Environment="PATH=%s"\n' "$service_search_path" ;;
      EnvironmentFile=*) printf 'EnvironmentFile=-%s\n' "$service_env_file" ;;
      *) printf '%s\n' "$line" ;;
      esac
    done <"$work_dir/files/voice-gateway.service" >"$work_dir/service"
    # Fail before publication if the per-user service manager is unavailable.
    systemctl --user show-environment >/dev/null
  else
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%$'\r'}"
      case "$line" in
      '        <string>-env-file</string>')
        if ! same_path "$config_path" "$(canonical_path "$default_config_path")"; then
          printf '        <string>-config</string>\n        <string>%s</string>\n' "$(xml_value "$config_path")"
        fi
        printf '%s\n' "$line"
        continue
        ;;
      '    <string>~/Library/Logs/voice-gateway.log</string>')
        printf '    <string>%s</string>\n' "$(xml_value "$log_file")"
        continue
        ;;
      '        <string>~/.local/bin/voice-gateway</string>') value="$binary_path" ;;
      '        <string>~/.config</string>') value="${XDG_CONFIG_HOME:-$HOME/.config}" ;;
      '        <string>~/.config/voice-gateway/voice-gateway.env</string>') value="$env_file" ;;
      '        <string>~/.local/bin:~/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>') value="$install_dir:$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin" ;;
      *)
        printf '%s\n' "$line"
        continue
        ;;
      esac
      printf '        <string>%s</string>\n' "$(xml_value "$value")"
    done <"$work_dir/files/voice-gateway.plist" >"$work_dir/service"
    plutil -lint "$work_dir/service" >/dev/null
  fi
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
if "$service"; then
  mkdir -p -- "$(dirname -- "$service_path")"
  install -m 600 "$work_dir/service" "$service_path"
  if [[ "$platform" == linux ]]; then
    systemctl --user daemon-reload
    systemctl --user enable voice-gateway.service
    systemctl --user restart voice-gateway.service
  else
    mkdir -p -- "$HOME/Library/Logs"
    domain="gui/$(id -u)"
    if launchctl print "$domain/com.maruel.voice-gateway" >/dev/null 2>&1; then
      launchctl bootout "$domain/com.maruel.voice-gateway"
    fi
    launchctl bootstrap "$domain" "$service_path"
  fi
fi
printf 'Installed: %s\nConfiguration: %s\nCredentials: %s\n' "$binary_path" "$config_path" "$env_file"
if "$service"; then printf 'Started service: %s\n' "$service_path"; fi
