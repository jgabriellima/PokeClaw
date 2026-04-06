# PokeClaw — local Android dev environment
ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
# Usage:
#   make setup              Install/check JDK 17, Android SDK, licenses, local.properties
#   make devices            List USB + AVDs (default)
#   make devices list|help|farms|create-slim|start NAME|wait|install-debug|kvm-check
#   make emulator-test      Headless AVD (poke_slim) if needed → connectedDebugAndroidTest
#   make build              ./gradlew assembleDebug (APK only — does not install)
#   make release            auto bump patch + versionCode, commit, push, assembleDebug, gh release
#   make install            ./gradlew installDebug (needs adb device)
#   make run                installDebug + launch launcher activity (fastest dev loop from CLI)
#
# Pass extra args after the devices target name:
#   make devices start poke_slim
#   make devices create-slim

.PHONY: default help setup devices emulator-test build release install run install-apk run-apk uninstall clean-gradle

default: help

help:
	@echo "PokeClaw Makefile"
	@echo ""
	@echo "  make setup          — JDK 17 + Android SDK (cmdline-tools) + platforms/build-tools for this project"
	@echo "  make devices        — list adb + AVDs (same as: make devices list)"
	@echo "  make devices help   — all device / emulator / farm subcommands"
	@echo "  make emulator-test  — start headless emulator if none online, then :app:connectedDebugAndroidTest"
	@echo "                        — env: POKECLAW_SLIM_AVD POKECLAW_EMU_EXTRA POKECLAW_EMU_BOOT_TIMEOUT POKECLAW_EMU_AUTOKILL"
	@echo "  make build          — ./gradlew assembleDebug (compile only)"
	@echo "  make release        — auto version bump in app/build.gradle.kts, commit, push, build, gh release"
	@echo "                        — override: GH_REPO=… RELEASE_NOTES='…'  RELEASE_SKIP_PUSH=1  GIT_REMOTE=origin"
	@echo "  make install        — ./gradlew installDebug"
	@echo "  make run            — ./gradlew installDebug + start app"
	@echo "  make install-apk    — push latest debug APK only (no Gradle — needs prior make build)"
	@echo "  make run-apk        — install-apk + start (no Gradle)"
	@echo "  make uninstall      — remove io.agents.pokeclaw (fix signature clash with store/other key)"
	@echo "  make clean-gradle   — ./gradlew clean"
	@echo ""
	@echo "Examples:"
	@echo "  make devices create-slim"
	@echo "  make devices start poke_slim"
	@echo "  make devices farms"
	@echo ""
	@echo "Physical device (per skill access-mobile-device):"
	@echo "  adb devices -l"
	@echo "  ANDROID_SERIAL=<serial> make run"
	@echo ""
	@echo "Signature clash (INSTALL_FAILED_UPDATE_INCOMPATIBLE):"
	@echo "  make uninstall && make install-apk   # or: make uninstall && make run"

setup:
	@bash "$(ROOT)/scripts/dev-setup.sh"

# Allow: make devices <subcommand> [args...]
ifeq ($(firstword $(MAKECMDGOALS)),devices)
  DEVICES_ARGS := $(wordlist 2,$(words $(MAKECMDGOALS)),$(MAKECMDGOALS))
  ifneq ($(strip $(DEVICES_ARGS)),)
    $(eval $(DEVICES_ARGS):;@:)
  endif
endif

devices:
	@bash "$(ROOT)/scripts/devices.sh" $(DEVICES_ARGS)

emulator-test:
	@bash "$(ROOT)/scripts/emulator-test.sh"

build:
	@bash "$(ROOT)/gradlew" assembleDebug

release:
	@bash "$(ROOT)/scripts/release.sh"

install:
	@bash "$(ROOT)/gradlew" installDebug

run:
	@bash "$(ROOT)/scripts/run-on-device.sh"

install-apk:
	@bash "$(ROOT)/scripts/install-debug-apk.sh"

run-apk:
	@LAUNCH=1 bash "$(ROOT)/scripts/install-debug-apk.sh"

uninstall:
	@bash "$(ROOT)/scripts/adb-uninstall.sh"

clean-gradle:
	@bash "$(ROOT)/gradlew" clean
