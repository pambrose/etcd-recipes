default: versioncheck

clean:
	./gradlew clean

stop:
	./gradlew --stop

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
	./gradlew wrapper --gradle-version=8.4 --distribution-type=bin