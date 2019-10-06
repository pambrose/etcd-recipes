default: versioncheck

clean:
	./gradlew clean

build:
	./gradlew build

refresh:
	./gradlew build --refresh-dependencies

versioncheck:
	./gradlew dependencyUpdates

