default: versioncheck

clean:
	./gradlew clean

versioncheck:
	./gradlew dependencyUpdates

