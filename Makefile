default: build

build:
	mvn -DskipTests=true clean package

tree:
	mvn dependency:tree

jarcheck:
	mvn versions:display-dependency-updates

plugincheck:
	mvn versions:display-plugin-updates

versioncheck: jarcheck plugincheck

docker-build:
	./bin/build-docker-images.sh

docker-push:
	./bin/push-docker-images.sh

docker-all: build docker-build docker-push