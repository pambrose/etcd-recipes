default: versioncheck

clean:
	./gradlew clean

compile:
	./gradlew build -xtest

build: compile

tests:
	./gradlew check jacocoTestReport

refresh:
	./gradlew --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates

upgrade-wrapper:
	./gradlew wrapper --gradle-version=7.0 --distribution-type=bin