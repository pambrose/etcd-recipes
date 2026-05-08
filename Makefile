.PHONY: default clean clean-all stop build tests tests-tc coverage kdocs \
        lint detekt detekt-baseline refresh versioncheck \
        publish-local publish-local-snapshot check-gpg-env \
        publish-snapshot publish-maven-central upgrade-wrapper

# Read the project version from gradle.properties; can be overridden on
# the command line, e.g. `make publish-snapshot VERSION=0.10.1`.
VERSION ?= $(shell awk -F= '/^version=/ {print $$2; exit}' gradle.properties)

default: versioncheck

clean:
	./gradlew clean
	rm -rf build

# Wipe every build / IDE / tool artifact a fresh checkout would not have.
# Stops any Gradle daemons first so .gradle is not held open.
clean-all: stop
	./gradlew clean || true
	rm -rf .gradle .kotlin build out
	find . -type d \( -name build -o -name .gradle -o -name .kotlin -o -name out -o -name bin \) -prune -exec rm -rf {} +
	rm -rf default.etcd

stop:
	./gradlew --stop

build: clean
	./gradlew build -xtest

tests:
	./gradlew check --rerun-tasks --no-build-cache

tests-tc:
	./gradlew check --rerun-tasks --no-build-cache -PuseTestcontainers

coverage:
	./gradlew koverHtmlReport koverXmlReport koverLog

kdocs:
	./gradlew dokkaGenerate

lint: detekt
	./gradlew lintKotlinMain lintKotlinTest

detekt:
	./gradlew detekt

detekt-baseline:
	./gradlew detektBaseline

refresh:
	./gradlew --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates --no-parallel

publish-local:
	./gradlew publishToMavenLocal

publish-local-snapshot:
	./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenLocal

GPG_ENV = \
	ORG_GRADLE_PROJECT_signingInMemoryKey="$$(gpg --armor --export-secret-keys $$GPG_SIGNING_KEY_ID)" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyId="$$GPG_SIGNING_KEY_ID" \
	ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$$(security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w)"

check-gpg-env:
	@if [ -z "$$GPG_SIGNING_KEY_ID" ]; then \
		echo "Error: GPG_SIGNING_KEY_ID is not set" >&2; exit 1; \
	fi
	@if ! gpg --list-secret-keys "$$GPG_SIGNING_KEY_ID" >/dev/null 2>&1; then \
		echo "Error: no GPG secret key found for GPG_SIGNING_KEY_ID=$$GPG_SIGNING_KEY_ID" >&2; exit 1; \
	fi
	@if ! security find-generic-password -a "gpg-signing" -s "gradle-signing-password" -w >/dev/null 2>&1; then \
		echo "Error: keychain entry 'gradle-signing-password' (account 'gpg-signing') not found" >&2; exit 1; \
	fi

publish-snapshot: check-gpg-env
	$(GPG_ENV) ./gradlew -PoverrideVersion=$(VERSION)-SNAPSHOT publishToMavenCentral

publish-maven-central: check-gpg-env
	$(GPG_ENV) ./gradlew publishAndReleaseToMavenCentral

upgrade-wrapper:
	./gradlew wrapper --gradle-version=9.5.0 --distribution-type=bin
