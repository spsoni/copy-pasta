# Copy-Pasta — dev entry points. `make help` lists everything.
# Gradle comes from the wrapper; the only host requirement is JDK 21.

GRADLE := ./gradlew --console=plain

.DEFAULT_GOAL := help
.PHONY: help hooks check test lint format build run verify clean

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
