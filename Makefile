# Copy-Pasta — dev entry points. `make help` lists everything.
# Gradle comes from the wrapper; the only host requirement is JDK 21.

GRADLE := ./gradlew --console=plain

.DEFAULT_GOAL := help
.PHONY: help hooks check test lint format build run verify clean release

help: ## List targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

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

build: ## Build the plugin ZIP into build/distributions/
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
	sed -i -E 's/^pluginVersion = .*/pluginVersion = $(VERSION)/' gradle.properties
	$(GRADLE) -q patchChangelog
	git commit -am "Release v$(VERSION)"
	git tag -a "v$(VERSION)" -m "Copy-Pasta $(VERSION)"
	@echo "Tagged v$(VERSION). Publish with:  git push origin HEAD v$(VERSION)"
