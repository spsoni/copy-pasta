# Copy-Pasta — dev entry points. `make help` lists everything.
# Gradle comes from the wrapper; the only host requirement is JDK 21.
# On macOS, `make setup-mac` installs everything with Homebrew (see Brewfile).

# The JetBrains plugin lives in intellij/ (its own Gradle wrapper and build).
GRADLE := intellij/gradlew -p intellij --console=plain

.DEFAULT_GOAL := help
.PHONY: help setup-mac hooks check test lint format build run verify clean release

help: ## List targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

setup-mac: ## macOS: install dev dependencies with Homebrew (JDK 21, gh, linters) + git hook
	@test "$$(uname -s)" = Darwin || { echo "setup-mac is for macOS; on Linux install openjdk-21-jdk-headless"; exit 1; }
	@command -v brew >/dev/null || { echo "Homebrew not found: install it from https://brew.sh"; exit 1; }
	@# Bottles for some JDK dependencies (glib, gettext, fontconfig, python, openssl) hard-code the default prefix
	@prefix=$$(brew --prefix); case "$$(uname -m) $$prefix" in "arm64 /opt/homebrew"|"x86_64 /usr/local") ;; *) \
	  echo "warning: Homebrew is at $$prefix, not its default prefix, so some JDK dependencies (glib, python," >&2; \
	  echo "  openssl, ...) build from source: expect 30-60 minutes. Reinstall Homebrew at its default prefix to use bottles." >&2;; esac
	@# --verbose streams install output; without it brew bundle stays silent until each batch finishes
	brew bundle --verbose --file=Brewfile
	@# openjdk@21 is keg-only: link it where java_home, Gradle and IntelliJ look for JDKs (per user, no sudo)
	mkdir -p "$$HOME/Library/Java/JavaVirtualMachines"
	ln -sfn "$$(brew --prefix openjdk@21)/libexec/openjdk.jdk" "$$HOME/Library/Java/JavaVirtualMachines/openjdk-21.jdk"
	@home=$$(/usr/libexec/java_home --failfast -v 21) && echo "JDK 21: $$home"
	@$(MAKE) --no-print-directory hooks

hooks: ## Enable the git pre-commit hook (.githooks/)
	git config core.hooksPath .githooks
	@echo "pre-commit hook enabled (bypass in an emergency: git commit --no-verify)"

check: ## Lint + unit tests + coverage report (what CI runs)
	$(GRADLE) check

test: ## Unit tests only
	$(GRADLE) test

lint: ## ktlint check
	$(GRADLE) ktlintCheck

format: ## Auto-format Kotlin sources with ktlint
	$(GRADLE) ktlintFormat

build: ## Build the plugin ZIP into intellij/build/distributions/
	$(GRADLE) buildPlugin

run: ## Launch a sandbox IDE with the plugin installed
	$(GRADLE) runIde

verify: ## IntelliJ Plugin Verifier against the recommended IDEs (slow, downloads IDEs)
	$(GRADLE) verifyPlugin

clean: ## Remove build outputs
	$(GRADLE) clean

release: ## Cut a release: make release VERSION=X.Y.Z (bump version, date CHANGELOG, commit, tag)
	@test -n "$(VERSION)" || { echo "usage: make release VERSION=X.Y.Z"; exit 1; }
	@echo "$(VERSION)" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$$' || { echo "VERSION must be SemVer, e.g. 2.0.0 or 2.1.0-beta.1"; exit 1; }
	@test -z "$$(git status --porcelain)" || { echo "working tree not clean"; exit 1; }
	@if git rev-parse -q --verify "refs/tags/v$(VERSION)" >/dev/null; then echo "tag v$(VERSION) already exists"; exit 1; fi
	sed -i.bak -E 's/^pluginVersion = .*/pluginVersion = $(VERSION)/' intellij/gradle.properties && rm -f intellij/gradle.properties.bak
	$(GRADLE) -q patchChangelog
	git commit -am "Release v$(VERSION)"
	git tag -a "v$(VERSION)" -m "Copy-Pasta $(VERSION)"
	@echo "Tagged v$(VERSION). Publish with:  git push origin HEAD v$(VERSION)"
