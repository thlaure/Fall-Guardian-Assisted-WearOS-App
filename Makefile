.PHONY: help test lint build check assemble-debug install-debug clean

.DEFAULT_GOAL := help

ADB ?= $(HOME)/Library/Android/sdk/platform-tools/adb
WEAR_DEVICE ?= $(shell $(ADB) devices -l 2>/dev/null | grep sdk_gwear | awk '{print $$1}')

help: ## Show available commands
	@echo "Fall Guardian Wear OS app"
	@echo ""
	@grep -E '(^[a-zA-Z_-]+:.*?##.*$$)' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

test: ## Run JVM tests
	./gradlew test

lint: ## Run Android lint
	./gradlew lint

build: ## Build the Wear OS app
	./gradlew build

check: test lint build ## Run deterministic quality checks

assemble-debug: ## Build the debug APK
	./gradlew assembleDebug

install-debug: assemble-debug ## Install debug APK on the detected Wear OS emulator
	$(ADB) -s $(WEAR_DEVICE) install -r app/build/outputs/apk/debug/app-debug.apk

clean: ## Clean Gradle build artifacts
	./gradlew clean
