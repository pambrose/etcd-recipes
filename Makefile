default: versioncheck

clean:
	./gradlew clean

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

lint:
	./gradlew lintKotlinMain lintKotlinTest

detekt:
	./gradlew detekt

detekt-baseline:
	./gradlew detektBaseline

refresh:
	./gradlew --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates --no-parallel

upgrade-wrapper:
	./gradlew wrapper --gradle-version=9.5.0 --distribution-type=bin
