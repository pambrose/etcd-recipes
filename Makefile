default: versioncheck

clean:
	./gradlew clean

compile:
	./gradlew build -xtest

build: compile

tests:
	./gradlew check jacocoTestReport

refresh:
	./gradlew build --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates