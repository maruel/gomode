# Build and verification targets for the standalone Go Mode contracts and shell.
.DEFAULT_GOAL := help
.PHONY: android-e2e android-export-lfm android-lint android-push android-sdk android-setup-emulator android-start-emulator android-stop-emulator android-test benchmark build coverage fix frontend-build generate-sdks git-hooks help refresh-generated release screenshots-check screenshots-generate screenshots-update test test-race test-smoke upgrade verify

# Keep local and CI release tooling on the same version.
GORELEASER ?= go run github.com/goreleaser/goreleaser/v2@v2.20.0

ANDROID_LINT_TASKS := \
	:car-dial-fixture:detekt :car-dial-fixture:ktlintCheck \
	:gomode:detekt :gomode:ktlintCheck \
	:halo-sdk:detekt :halo-sdk:ktlintCheck

# Android lint needs compiled classes, so keep it with build-dependent checks.
ANDROID_TEST_TASKS := \
	:car-dial-fixture:lintDebug :gomode:lintDebug :halo-sdk:lintDebug \
	:gomode:assembleDebug :halo-sdk:assembleDebug \
	:gomode-sdk:assemble :mcp-sdk:assemble :oauth-sdk:assemble :voicegateway-sdk:assemble \
	:gomode:assembleDebugAndroidTest :halo-sdk:assembleDebugAndroidTest \
	:gomode:testDebugUnitTest :halo-sdk:testDebugUnitTest \
	:gomode:createDebugUnitTestCoverageReport :halo-sdk:createDebugUnitTestCoverageReport \
	:car-dial-fixture:assembleDebug :car-dial-fixture:assembleDebugAndroidTest

# Keep Java processes scoped to each build, including Gradle launched by Python helpers.
# Compile Kotlin inside Gradle's single-use JVM so it cannot leave a compiler daemon.
export GRADLE_OPTS := $(GRADLE_OPTS) -Dorg.gradle.daemon=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process

node_modules/.modules.yaml: package.json pnpm-lock.yaml
	@pnpm install --frozen-lockfile --silent
	@touch $@

help:
	@echo 'Go Mode - standalone contracts and shell'
	@echo ''
	@echo 'Available targets:'
	@printf '  %-27s - %s\n' 'make fix' 'Format Go and Android code'
	@printf '  %-27s - %s\n' 'make verify' 'Check Go, browser, Python, shell, and changed Android inputs'
	@printf '  %-27s - %s\n' 'make frontend-build' 'Rebuild embedded standalone frontend assets'
	@printf '  %-27s - %s\n' 'make git-hooks' 'Install versioned Git hooks'
	@printf '  %-27s - %s\n' 'make test' 'Run Go, browser, and changed Android builds and tests'
	@printf '  %-27s - %s\n' 'make test-race' 'Run race-safe voice gateway concurrency tests'
	@printf '  %-27s - %s\n' 'make build' 'Build Go packages and Android app and SDKs'
	@printf '  %-27s - %s\n' 'make release' 'Publish gateway archives from a version tag (requires GITHUB_TOKEN)'
	@printf '  %-27s - %s\n' 'make benchmark' 'Run Go benchmarks'
	@printf '  %-27s - %s\n' 'make coverage' 'Generate Go coverage report'
	@printf '  %-27s - %s\n' 'make test-smoke' 'Run local model voice smoke tests (slow, downloads models)'
	@printf '  %-27s - %s\n' 'make android-lint' 'Run Android source static analysis and formatting checks'
	@printf '  %-27s - %s\n' 'make android-test' 'Run Android builds, platform lint, unit tests, and coverage'
	@printf '  %-27s - %s\n' 'make android-e2e' 'Run all model-free app, gateway, and Telecom emulator tests'
	@printf '  %-27s - %s\n' 'make screenshots-update' 'Capture and publish repeatable native Android scenes'
	@printf '  %-27s - %s\n' 'make screenshots-check' 'Compare native Android captures with committed baselines'
	@printf '  %-27s - %s\n' 'make screenshots-generate' 'Render native captures once without baseline checks'
	@printf '  %-27s - %s\n' 'make android-export-lfm' 'Export missing 32K/64K LFM3 bundles (slow, downloads source weights)'
	@printf '  %-27s - %s\n' 'make android-push' 'Build and install Go Mode on connected devices'
	@printf '  %-27s - %s\n' 'make android-setup-emulator' 'Install emulator image and create the test AVD'
	@printf '  %-27s - %s\n' 'make android-start-emulator' 'Start or reuse the Android emulator'
	@printf '  %-27s - %s\n' 'make android-stop-emulator' 'Stop the Android emulator'
	@printf '  %-27s - %s\n' 'make android-sdk' 'Install required Android SDK packages'
	@printf '  %-27s - %s\n' 'make generate-sdks' 'Regenerate protocol SDKs after DTO or route changes'
	@printf '  %-27s - %s\n' 'make refresh-generated' 'Regenerate SDKs and AGENTS file indexes'
	@printf '  %-27s - %s\n' 'make upgrade' 'Upgrade Go and pnpm dependencies'

fix: node_modules/.modules.yaml
	@pnpm exec node scripts/css_module_types.mjs --source-path web/src --write
	@pnpm exec node scripts/css_module_types.mjs --source-path frontend/src --write
	@go tool golangci-lint fmt
	@go tool golangci-lint run --show-stats=false --fix ./...
	@pnpm exec eslint e2e/voice-preview/*.js e2e/voice-preview/*.tsx frontend/src scripts/*.mjs web/src web/tests --fix
	@pnpm exec prettier --write --log-level warn .github/workflows e2e/voice-preview eslint.config.js frontend/index.html frontend/src frontend/tsconfig.json package.json scripts/*.mjs web/src web/tests
	@uv run --locked ruff check --quiet --fix scripts
	@uv run --locked ruff format scripts >/dev/null
	@files=$$(git ls-files '*.sh' 'scripts/hooks/*'); [ -z "$$files" ] || go tool shfmt -w $$files
	@./scripts/android-checks.sh :gomode:ktlintFormat :halo-sdk:ktlintFormat :car-dial-fixture:ktlintFormat --quiet
	@pnpm exec node scripts/build_frontend.mjs --write
	@uv run --locked python scripts/update_agents_file_index.py

verify: node_modules/.modules.yaml
	@./scripts/run-concurrently.sh sdk,workflows,go,format,lint,ts,frontend,python,shell,misc,android \
		'go run ./internal/cmd/gen-sdk -check' \
		'go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12' \
		'go test -run=^$$ ./... && go test -race -run=^$$ ./voicegateway/voicertc && go tool golangci-lint run ./... && go mod tidy -diff && go build ./...' \
		'pnpm exec prettier --check --log-level warn .github/workflows e2e/voice-preview eslint.config.js frontend/index.html frontend/src frontend/tsconfig.json package.json scripts/*.mjs web/src web/tests' \
		'pnpm exec eslint --max-warnings 0 e2e/voice-preview/*.js e2e/voice-preview/*.tsx frontend/src scripts/*.mjs web/src web/tests' \
		'pnpm exec node scripts/css_module_types.mjs --source-path web/src && pnpm exec node scripts/css_module_types.mjs --source-path frontend/src && pnpm exec tsc --noEmit -p frontend/tsconfig.json' \
		'pnpm exec node scripts/build_frontend.mjs' \
		'uv run --locked ruff check --quiet scripts && uv run --locked ruff format --check --quiet scripts && uv run --locked python scripts/android_paths.py >/dev/null && uv run --locked python scripts/generate_thinking_score.py --check' \
		'files=$$(git ls-files "*.sh" "scripts/hooks/*"); [ -z "$$files" ] || go tool shfmt -d $$files' \
		'uv run --locked python scripts/lint_binaries.py && uv run --locked python scripts/update_agents_file_index.py --check && git diff --check' \
		'./scripts/android-checks.sh $(ANDROID_LINT_TASKS) --quiet'

git-hooks:
	@./scripts/install-git-hooks.sh

release:
	@$(GORELEASER) release --clean

test: node_modules/.modules.yaml
	@./scripts/run-concurrently.sh release,go,browser-scripts,browser \
		'$(GORELEASER) check' \
		'go test ./...' \
		'pnpm exec node --test --test-reporter=./scripts/quiet-test-reporter.mjs scripts/browser_integration.test.mjs scripts/build_frontend.test.mjs scripts/css_module_types.test.mjs scripts/voice_preview.test.mjs' \
		'NODE_OPTIONS=--no-experimental-webstorage pnpm exec tsx --test --conditions=browser --test-force-exit --test-reporter=./scripts/quiet-test-reporter.mjs --import ./web/tests/setup-dom.ts "frontend/src/**/*.test.tsx" "web/src/**/*.test.ts" "web/src/**/*.test.tsx"'
	@./scripts/android-checks.sh $(ANDROID_TEST_TASKS) --quiet

test-race:
	@go test -race ./voicegateway/voicertc

frontend-build: node_modules/.modules.yaml
	@pnpm exec node scripts/css_module_types.mjs --source-path web/src --write
	@pnpm exec node scripts/css_module_types.mjs --source-path frontend/src --write
	@pnpm exec node scripts/build_frontend.mjs --write

build: android-sdk node_modules/.modules.yaml frontend-build
	@pnpm exec node scripts/css_module_types.mjs --source-path web/src
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

android-lint: android-sdk
	@cd ./android && ./gradlew $(ANDROID_LINT_TASKS) --quiet

android-test: android-sdk
	@cd ./android && ./gradlew $(ANDROID_TEST_TASKS) --quiet

generate-sdks:
	@go run ./internal/cmd/gen-sdk

refresh-generated: generate-sdks
	@uv run --locked python scripts/update_agents_file_index.py

# Installs the Android SDK packages required by the app build.
android-sdk:
	@uv run --locked python scripts/android_sdk.py check

# Slow: installs an emulator system image and creates the Go Mode test AVD.
android-setup-emulator:
	@uv run --locked python scripts/android_sdk.py setup-emulator

# Slow: starts or reuses the headless Go Mode test emulator.
android-start-emulator:
	@uv run --locked python scripts/android_start_emulator.py --auto-reuse

android-stop-emulator:
	@uv run --locked python scripts/android_stop_emulator.py

# Builds, installs, and launches the app on a connected device.
android-push: android-sdk
	@cd ./android && ./gradlew :gomode:assembleDebug --quiet
	@uv run --locked python scripts/android_push.py

# Slow: runs all model-free app and independent Telecom tests on an emulator.
android-e2e:
	@uv run --locked python scripts/android_start_emulator.py --auto-reuse
	@uv run --locked python scripts/android_e2e.py

# Slow: runs the real app on the emulator, two passes for update/check.
# Encodes at most two lossless WebP files concurrently and publishes a catalog.
screenshots-check screenshots-generate screenshots-update: android-sdk
	@uv run --locked python scripts/android_start_emulator.py --auto-reuse
	@uv run --locked python scripts/android_screenshots.py $(subst screenshots-,,$@)

# Slow: streams the pinned source checkpoint through LiteRT conversion and quantization.
android-export-lfm:
	@uv run --locked --script scripts/export_lfm_litert.py
