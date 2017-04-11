
py-stubs:
	cd src/main/proto; python -m grpc_tools.protoc -I. --python_out=../../../pb/ --grpc_python_out=../../../pb/ ./proxy_service.proto


build:
	mvn -DskipTests=true clean package

tree:
	mvn dependency:tree

jarcheck:
	mvn versions:display-dependency-updates

plugincheck:
	mvn versions:display-plugin-updates

versioncheck: jarcheck plugincheck
