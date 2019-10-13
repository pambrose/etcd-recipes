default: versioncheck

clean:
	./gradlew clean

build:
	./gradlew build

tests:
	./gradlew testClasses

refresh:
	./gradlew build --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates

