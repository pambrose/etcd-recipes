.PHONY: default help clean clean-all stop build tests tests-tc tests-container coverage kdocs \
        lint detekt detekt-baseline refresh versioncheck publish-local publish-local-snapshot \
        publish-snapshot publish-maven-central upgrade-wrapper \
        _check-gpg-env _require-version _require-gradle-version

# Read the project version from gradle.properties; can be overridden on
# the command line, e.g. `make publish-snapshot VERSION=0.10.1`.
VERSION ?= $(shell awk -F= '/^version=/ {print $$2; exit}' gradle.properties)

# Read the Gradle wrapper version from the version catalog so
# `make upgrade-wrapper` always tracks the catalog's `gradle` entry.
GRADLE_VERSION ?= $(shell awk -F'"' '/^gradle = /{print $$2; exit}' gradle/libs.versions.toml)

GPG_ENV = \
	ORG_GRADLE_PROJECT_signingInMemoryKey="$$(gpg --armor --export-secret-keys $$GPG_SIGNING_KEY_ID)" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyId="$$GPG_SIGNING_KEY_ID" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$$(security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w)"

default: versioncheck

help: ## Show this help
	@awk 'BEGIN { FS = ":.*?## "; printf "Usage: make <target>\n\nTargets:\n" } \
	     /^[a-zA-Z_-]+:.*?## / { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 }' \
	     $(MAKEFILE_LIST)

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
	./gradlew build -xtest

tests: ## Run the full test suite against a local etcd at localhost:2379
	./gradlew check --rerun-tasks --no-build-cache

# DOCKER_HOST defaults to Docker Desktop's "raw" socket on macOS. The
# routing socket at ~/.docker/run/docker.sock returns a redirect stub
# that docker-java can't follow, so we point at the daemon's socket
# directly. Override with `DOCKER_HOST=unix://...` if needed.
ifeq ($(shell uname -s),Darwin)
DOCKER_HOST ?= unix://$(HOME)/Library/Containers/com.docker.docker/Data/docker.raw.sock
endif

tests-tc: ## Run the full test suite under Testcontainers (no local etcd required)
	DOCKER_HOST="$(DOCKER_HOST)" ./gradlew check --rerun-tasks --no-build-cache -PuseTestcontainers

tests-container: ## Run only the multi-container tests (each participant in its own container)
	DOCKER_HOST="$(DOCKER_HOST)" ./gradlew :etcd-recipes:cleanTest :etcd-recipes:test --tests "io.etcd.recipes.container.*" --no-build-cache -PuseTestcontainers

coverage: ## Generate Kover HTML + XML coverage reports and print the summary
	./gradlew koverHtmlReport koverXmlReport koverLog

kdocs: ## Generate Dokka HTML and Javadoc documentation
	./gradlew dokkaGenerate

lint: ## Run kotlinter and detekt (style + static analysis)
	./gradlew lintKotlin detekt

detekt: ## Run detekt static analysis
	./gradlew detekt

detekt-baseline: ## (Re)generate detekt baseline files
	./gradlew detektBaseline

refresh: ## Force-refresh Gradle dependencies
	./gradlew --refresh-dependencies

versioncheck: ## Report dependencies with newer versions available
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
