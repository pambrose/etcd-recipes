default: versioncheck

clean:
	./gradlew clean

compile:
	./gradlew build -x test

tests:
	./gradlew check

refresh:
	./gradlew build --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates