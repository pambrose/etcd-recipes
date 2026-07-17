.PHONY: default help clean clean-all stop etcd etcd-stop build tests tests-tc tests-container all-tests coverage kdocs \
        site clean-site check-site upgrade-site docs-check \
        lint detekt detekt-baseline refresh versions publish-local publish-local-snapshot \
        publish-snapshot publish-maven-central upgrade-wrapper \
        _check-gpg-env _require-version _require-gradle-version

VERSION := $(shell sed -n 's/^version=\(.*\)/\1/p' gradle.properties)
GRADLE_VERSION := $(shell sed -n 's/^gradle-wrapper = "\(.*\)"/\1/p' gradle/libs.versions.toml)

# The uv project root: where pyproject.toml and uv.lock live. The check-site /
# upgrade-site targets cd here so `uv lock` finds the project.
WEBSITE_DIR := website

# The zensical config dir, holding zensical.toml and docs/. zensical MUST run
# with this as its working directory: pymdownx.snippets resolves base_path
# against the process CWD rather than against zensical.toml's location, so
# `zensical -f website/etcd-recipes/...` from the repo root silently finds no
# snippets. Hence the cd in the site / docs-check targets.
SITE_DIR := $(WEBSITE_DIR)/etcd-recipes

GPG_ENV = \
	ORG_GRADLE_PROJECT_signingInMemoryKey="$$(gpg --armor --export-secret-keys $$GPG_SIGNING_KEY_ID)" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyId="$$GPG_SIGNING_KEY_ID" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$$(security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w)"

default: help

help:  ## Show this help (list of targets)
	@awk 'BEGIN {FS = ":.*?## "; printf "Usage: make <target>\n\nTargets:\n"} \
		/^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

clean: ## Run gradle clean and remove the root build/ directory
	./gradlew clean
	rm -rf build

# Wipe every build / IDE / tool artifact a fresh checkout would not have.
# Stops any Gradle daemons first so .gradle is not held open.
clean-all: stop ## Wipe all build/IDE/tool artifacts (.gradle, .kotlin, build, out, default.etcd)
	./gradlew clean || true
	rm -rf .gradle .kotlin build out
	find . -type d \( -name build -o -name .gradle -o -name .kotlin -o -name out -o -name bin \) -prune -exec rm -rf {} +
	rm -rf default.etcd

stop: ## Stop running Gradle daemons
	./gradlew --stop

build: clean ## Clean and run a full build, skipping tests
	./gradlew build -x test

etcd: ## Start a local etcd at localhost:2379 (foreground; Ctrl-C to stop)
	./etcd.sh

etcd-stop: ## Gracefully stop the local etcd started by `make etcd`
	./etcd-stop.sh

tests: ## Run the full test suite against a local etcd at localhost:2379
	./gradlew check --rerun-tasks --no-build-cache

# DOCKER_HOST defaults to Docker Desktop's per-user routing socket on macOS
# (~/.docker/run/docker.sock) — the same socket `docker context` and
# ~/.testcontainers.properties use. Current Docker Desktop builds no longer
# serve the "raw" socket under ~/Library/Containers/...; pointing at it makes
# Testcontainers hang waiting on a dead socket. Override with
# `DOCKER_HOST=unix://...` if your setup differs.
ifeq ($(shell uname -s),Darwin)
DOCKER_HOST ?= unix://$(HOME)/.docker/run/docker.sock
endif

tests-tc: ## Run the full test suite under Testcontainers (no local etcd required)
	# Fault-injection tests (io.etcd.recipes.fault.*) only run here: they pause/restart/
	# compact their per-class etcd container and skip cleanly against a shared local etcd.
	DOCKER_HOST="$(DOCKER_HOST)" ./gradlew check --rerun-tasks --no-build-cache -PuseTestcontainers

tests-container: ## Run only the multi-container tests (each participant in its own container)
	DOCKER_HOST="$(DOCKER_HOST)" ./gradlew :etcd-recipes-core:cleanTest :etcd-recipes-core:test --tests "io.etcd.recipes.container.*" --no-build-cache -PuseTestcontainers

all-tests: tests tests-tc tests-container ## Run all tests (local etcd, Testcontainers, and multi-container)

coverage: ## Generate Kover HTML + XML coverage reports and print the summary
	./gradlew koverHtmlReport koverXmlReport koverLog

kdocs: ## Generate Dokka HTML and Javadoc documentation
	./gradlew dokkaGenerate

# The site embeds code examples straight out of the Gradle test source sets, so a
# renamed snippet section breaks the docs build. This compiles the examples and
# then builds the site in strict mode, which is what CI does.
docs-check: ## Compile the doc snippets, then build the site in strict mode
	./gradlew compileTestKotlin compileTestJava
	cd $(SITE_DIR) && uv run zensical build --clean --strict

check-site:  ## Check for outdated website dependencies
	cd $(WEBSITE_DIR) && env -u VIRTUAL_ENV uv lock --upgrade --dry-run

upgrade-site:  ## Upgrade the website dependencies
	cd $(WEBSITE_DIR) && env -u VIRTUAL_ENV uv lock --upgrade

clean-site:  ## Remove generated zensical site and cache
	rm -rf $(SITE_DIR)/site
	rm -rf $(SITE_DIR)/.cache

site: clean-site  ## Serve the docs site locally with zensical
	cd $(SITE_DIR) && uv run zensical serve

lint: ## Run kotlinter and detekt (style + static analysis)
	./gradlew lintKotlin detekt

detekt: ## Run detekt static analysis
	./gradlew detekt

detekt-baseline: ## (Re)generate detekt baseline files
	./gradlew detektBaseline

refresh: ## Force-refresh Gradle dependencies
	./gradlew --refresh-dependencies

versions: ## Report dependencies with newer versions available
	./gradlew dependencyUpdates --no-parallel

publish-local: _require-version ## Publish artifacts to ~/.m2/repository
	./gradlew publishToMavenLocal

publish-local-snapshot: _require-version ## Publish a -SNAPSHOT to ~/.m2/repository (uses VERSION= or gradle.properties)
	./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenLocal

publish-snapshot: _require-version _check-gpg-env ## Publish a -SNAPSHOT to Maven Central (requires VERSION= and GPG)
	$(GPG_ENV) ./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenCentral

publish-maven-central: _require-version _check-gpg-env ## Publish and release the current version to Maven Central
	$(GPG_ENV) ./gradlew publishAndReleaseToMavenCentral

# Gradle's documented upgrade procedure: the first run rewrites
# gradle-wrapper.properties using the *old* wrapper jar; the second run
# regenerates the wrapper itself with the new version.
upgrade-wrapper: _require-gradle-version ## Upgrade the Gradle wrapper to the version pinned in libs.versions.toml
	./gradlew wrapper --gradle-version=$(GRADLE_VERSION) --distribution-type=bin
	./gradlew wrapper --gradle-version=$(GRADLE_VERSION) --distribution-type=bin

_check-gpg-env:
	@if [ -z "$$GPG_SIGNING_KEY_ID" ]; then \
		echo "Error: GPG_SIGNING_KEY_ID is not set" >&2; exit 1; \
	fi
	@if ! gpg --list-secret-keys "$$GPG_SIGNING_KEY_ID" >/dev/null 2>&1; then \
		echo "Error: no GPG secret key found for GPG_SIGNING_KEY_ID=$$GPG_SIGNING_KEY_ID" >&2; exit 1; \
	fi
	@if ! security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w >/dev/null 2>&1; then \
		echo "Error: keychain entry 'gradle-signing-password' (account 'gpg-signing') not found" >&2; exit 1; \
	fi

_require-version:
	@[ -n "$(VERSION)" ] || { echo "ERROR: Could not determine project version from gradle.properties" >&2; exit 1; }

_require-gradle-version:
	@[ -n "$(GRADLE_VERSION)" ] || { echo "ERROR: Could not determine gradle version from gradle/libs.versions.toml" >&2; exit 1; }
