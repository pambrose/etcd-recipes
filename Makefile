default: versioncheck

clean:
	./gradlew clean

compile:
	./gradlew build -xtest

build: compile

tests:
	./gradlew check jacocoTestReport

lint:
	./gradlew lintKotlinMain
	./gradlew lintKotlinTest

refresh:
	./gradlew --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates

upgrade-wrapper:
	./gradlew wrapper --gradle-version=7.5.1 --distribution-type=bin