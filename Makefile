default: versioncheck

clean:
	./gradlew clean

stop:
	./gradlew --stop

build: clean
	./gradlew build -xtest

tests:
	./gradlew check jacocoTestReport

lint:
	./gradlew lintKotlinMain lintKotlinTest

refresh:
	./gradlew --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates --no-parallel

upgrade-wrapper:
	./gradlew wrapper --gradle-version=9.5.0 --distribution-type=bin
