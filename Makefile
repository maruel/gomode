# Build and verification targets for the standalone Go Mode contracts and shell.
.DEFAULT_GOAL := help
.PHONY: help build fix verify test test-race android-check android-sdk android-setup-emulator android-start-emulator android-stop-emulator android-push android-e2e generate-sdks refresh-generated benchmark coverage test-smoke upgrade git-hooks tools

RUFF_VERSION=0.16.8
UV_BIN := $(shell uv tool dir --bin 2>/dev/null)
export PATH := $(if $(UV_BIN),$(UV_BIN):)$(PATH)

# Read-only verification lanes. Each command is passed through two shells, so
# keep single quotes out of these values.
VERIFY_GO = go test -run=^$$ ./... && go test -race -run=^$$ ./voicegateway/voicertc && go vet ./... && go tool golangci-lint run ./... && go mod tidy -diff && go build ./...
VERIFY_PY = ruff check --quiet scripts && ruff format --check --quiet scripts
VERIFY_SH = files=$$(git ls-files "*.sh" "scripts/hooks/*"); [ -z "$$files" ] || go tool shfmt -l $$files
VERIFY_MISC = python3 scripts/lint_binaries.py && python3 scripts/update_agents_file_index.py --check && git diff --check
VERIFY_ANDROID = cd ./android && ./gradlew :gomode:ktlintCheck :halo-sdk:ktlintCheck :gomode:detekt :gomode:lintDebug :oauth-sdk:assemble --quiet

tools:
	@command -v uv >/dev/null 2>&1 || { echo 'uv is required to install Ruff; see https://docs.astral.sh/uv/' >&2; exit 1; }
	@ruff --version 2>/dev/null | grep -Fqw "$(RUFF_VERSION)" || uv tool install --force --quiet ruff==$(RUFF_VERSION)

node_modules/.modules.yaml: package.json pnpm-lock.yaml
	@pnpm install --frozen-lockfile --silent
	@touch $@

help:
	@echo 'Go Mode - standalone contracts and shell'
	@echo ''
	@echo 'Available targets:'
	@printf '  %-27s - %s\n' 'make fix' 'Format Go and Android code'
	@printf '  %-27s - %s\n' 'make verify' 'Check Go, browser, Python, shell, and Android code'
	@printf '  %-27s - %s\n' 'make git-hooks' 'Install versioned Git hooks'
	@printf '  %-27s - %s\n' 'make test' 'Run Go, browser, and Android unit tests'
	@printf '  %-27s - %s\n' 'make test-race' 'Run race-safe voice gateway concurrency tests'
	@printf '  %-27s - %s\n' 'make build' 'Build Go packages and Android app and SDKs'
	@printf '  %-27s - %s\n' 'make benchmark' 'Run Go benchmarks'
	@printf '  %-27s - %s\n' 'make coverage' 'Generate Go coverage report'
	@printf '  %-27s - %s\n' 'make test-smoke' 'Run local model voice smoke tests (slow, downloads models)'
	@printf '  %-27s - %s\n' 'make android-check' 'Run Android lint, builds, unit tests, and coverage'
	@printf '  %-27s - %s\n' 'make android-e2e' 'Run Android instrumented tests on an emulator'
	@printf '  %-27s - %s\n' 'make android-push' 'Build and install Go Mode on connected devices'
	@printf '  %-27s - %s\n' 'make android-setup-emulator' 'Install emulator image and create the test AVD'
	@printf '  %-27s - %s\n' 'make android-start-emulator' 'Start or reuse the Android emulator'
	@printf '  %-27s - %s\n' 'make android-stop-emulator' 'Stop the Android emulator'
	@printf '  %-27s - %s\n' 'make android-sdk' 'Install required Android SDK packages'
	@printf '  %-27s - %s\n' 'make generate-sdks' 'Regenerate protocol SDKs after DTO or route changes'
	@printf '  %-27s - %s\n' 'make refresh-generated' 'Regenerate SDKs and AGENTS file indexes'
	@printf '  %-27s - %s\n' 'make upgrade' 'Upgrade Go and pnpm dependencies'

fix: tools android-sdk node_modules/.modules.yaml
	@go tool golangci-lint fmt
	@pnpm exec eslint web/src web/tests scripts/quiet-test-reporter.mjs --fix
	@pnpm exec prettier --write --log-level warn web/src web/tests scripts/quiet-test-reporter.mjs eslint.config.js package.json .github/workflows
	@ruff check --quiet --fix scripts
	@ruff format --quiet scripts
	@go tool shfmt -w scripts/check-staged.sh scripts/install-git-hooks.sh scripts/hooks/*
	@cd ./android && ./gradlew :gomode:ktlintFormat :halo-sdk:ktlintFormat --quiet
	@python3 scripts/update_agents_file_index.py

verify: tools android-sdk node_modules/.modules.yaml
	@go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12
	@./scripts/run-concurrently.sh go,format,lint,ts,python,shell,misc,android '$(VERIFY_GO)' 'pnpm exec prettier --check --log-level warn web/src web/tests scripts/quiet-test-reporter.mjs eslint.config.js package.json .github/workflows' 'pnpm exec eslint --max-warnings 0 web/src web/tests scripts/quiet-test-reporter.mjs' 'pnpm typecheck' '$(VERIFY_PY)' '$(VERIFY_SH)' '$(VERIFY_MISC)' '$(VERIFY_ANDROID)'

git-hooks:
	@./scripts/install-git-hooks.sh

test: android-sdk node_modules/.modules.yaml
	@./scripts/run-concurrently.sh go,browser,android 'go test ./...' 'pnpm --silent test' 'cd ./android && ./gradlew :gomode:testDebugUnitTest :halo-sdk:testDebugUnitTest --quiet'

# The Opus codec is deliberately disabled in race builds, so skip the two
# tests that require it while running the rest of the voice gateway suite.
test-race:
	@go test -race -skip 'TestVoiceRTCLocalStackPlaceholders|TestEncodeDecodeRoundtrip' ./voicegateway/voicertc

build: android-sdk
	@go build ./...
	@cd ./android && ./gradlew :gomode:assembleDebug :halo-sdk:assembleDebug :gomode-sdk:assemble :mcp-sdk:assemble :oauth-sdk:assemble :voicegateway-sdk:assemble --quiet

# Benchmarks exercise the Go request paths; the browser package has no benchmark suite.
benchmark:
	@go test ./... -run '^$$' -bench . -benchmem

coverage:
	@go test -coverprofile=coverage.out ./...
	@go tool cover -func=coverage.out | tail -1
	@go tool cover -html=coverage.out -o coverage.html

# Slow: starts local ASR, LLM, and TTS models and sends spoken audio through
# the voice gateway over WebRTC. The smoke build tag keeps it out of make test.
test-smoke:
	@go test -tags=smoke -run '^TestSmoke' -v -timeout 30m ./voicegateway/voicertc/

upgrade:
	@go get -u ./...
	@go mod tidy
	@pnpm update --latest
	@cd ./android && ./gradlew --no-daemon dependencyUpdates -Drevision=release

android-check: android-sdk
	@cd ./android && ./gradlew :gomode:assembleDebug :halo-sdk:assembleDebug :gomode-sdk:assemble :mcp-sdk:assemble :oauth-sdk:assemble :voicegateway-sdk:assemble :gomode:assembleDebugAndroidTest :halo-sdk:assembleDebugAndroidTest :gomode:detekt :halo-sdk:detekt :gomode:ktlintCheck :halo-sdk:ktlintCheck :gomode:lintDebug :halo-sdk:lintDebug :gomode:testDebugUnitTest :halo-sdk:testDebugUnitTest :gomode:createDebugUnitTestCoverageReport :halo-sdk:createDebugUnitTestCoverageReport --quiet

generate-sdks:
	@go run ./internal/cmd/gen-sdk

refresh-generated: generate-sdks
	@python3 scripts/update_agents_file_index.py

# Installs the Android SDK packages required by the app build.
android-sdk:
	@python3 scripts/android_sdk.py check

# Slow: installs an emulator system image and creates the Go Mode test AVD.
android-setup-emulator:
	@python3 scripts/android_sdk.py setup-emulator

# Slow: starts or reuses the headless Go Mode test emulator.
android-start-emulator: android-setup-emulator
	@python3 scripts/android_start_emulator.py --auto-reuse

android-stop-emulator:
	@python3 scripts/android_stop_emulator.py

# Slow: compiles, checks, installs, and launches the app on a connected device.
android-push: android-check
	@python3 scripts/android_push.py

# Slow: starts or reuses an emulator and runs shell behavior on a hosted fixture.
android-e2e:
	@python3 scripts/android_start_emulator.py --reuse-connected-device
	@python3 scripts/android_e2e.py
