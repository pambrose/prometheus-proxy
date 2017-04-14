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

docker-build: build
	./bin/build-docker-images.sh
