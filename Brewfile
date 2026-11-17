# macOS dev dependencies. Install with `make setup-mac` (runs `brew bundle`).
# Gradle comes from the wrapper: upgrade it with `./gradlew wrapper --gradle-version X`, no system Gradle needed.
# Preview the plugin icons without librsvg: `sips -s format png -Z 160 src/main/resources/META-INF/pluginIcon.svg --out icon.png`.

brew "openjdk@21"   # JDK 21: runs Gradle and compiles the plugin (jvmToolchain 21)
brew "gh"           # GitHub CLI: PRs, releases, repo secrets
brew "actionlint"   # lint .github/workflows (runs shellcheck on run: blocks)
brew "shellcheck"
