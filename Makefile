default: versioncheck

clean:
	./gradlew clean

compile:
	./gradlew build -xtest

tests:
	./gradlew check

refresh:
	./gradlew build --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates