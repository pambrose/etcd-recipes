default: versioncheck

clean:
	./gradlew clean

build:
	./gradlew build

tests:
	./gradlew check

refresh:
	./gradlew build --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates