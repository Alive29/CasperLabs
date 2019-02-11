DOCKER_USERNAME ?= io.casperlabs
DOCKER_PUSH_LATEST ?= false
# Use the git tag / hash as version. Easy to pinpoint. `git tag` can return more than 1 though. `git rev-parse --short HEAD` would just be the commit.
$(eval TAGS_OR_SHA = $(shell git tag -l --points-at HEAD | grep -e . || git describe --tags --always --long))
# Try to use the semantic version with any leading `v` stripped.
$(eval SEMVER_REGEX = 'v?\K\d+\.\d+(\.\d+)?')
$(eval VER = $(shell echo $(TAGS_OR_SHA) | grep -Po $(SEMVER_REGEX) | tail -n 1 | grep -e . || echo $(TAGS_OR_SHA))) 


# Refresh Scala build artifacts.
sbt-stage/%:
	$(eval PROJECT = $*)
	sbt -mem 5000 $(PROJECT)/universal:stage


# Build the `latest` docker image for local testing. Works with Scala.
docker-build-universal/%: sbt-stage/%
	$(eval PROJECT = $*)
	$(eval STAGE = $(PROJECT)/target/universal/stage)
	rm -rf $(STAGE)/.docker
	# Copy the 3rd party dependencies to a separate directory so if they don't change we can push faster.
	mkdir -p $(STAGE)/.docker/layers/3rd
	find $(STAGE)/lib \
	    -type f ! -iregex ".*/io.casperlabs.*jar" \
	    -exec cp {} $(STAGE)/.docker/layers/3rd \;
	# Copy our own code.
	mkdir -p $(STAGE)/.docker/layers/1st
	find $(STAGE)/lib \
	    -type f -iregex ".*/io.casperlabs.*jar" \
	    -exec cp {} $(STAGE)/.docker/layers/3rd \;
	# Use the Dockerfile to build the project. Has to be within the context.
	cp $(PROJECT)/Dockerfile $(STAGE)/Dockerfile
	docker build -f $(STAGE)/Dockerfile -t $(DOCKER_USERNAME)/$(PROJECT):latest $(STAGE)
	rm -rf $(STAGE)/.docker $(STAGE)/Dockerfile

docker-build/node: docker-build-universal/node
docker-build/client: docker-build-universal/client


docker-build/execution-engine: .mark.rustup-update .mark.protoc-install
	cd execution-engine/comm && \
	cargo run --bin grpc-protoc && \
	cargo build --release 
	# Just copy the executable to the container.
	$(eval RELEASE = execution-engine/comm/target/release)
	cp execution-engine/Dockerfile $(RELEASE)/Dockerfile
	docker build -f $(RELEASE)/Dockerfile -t $(DOCKER_USERNAME)/execution-engine:latest $(RELEASE)
	rm -rf $(RELEASE)/Dockerfile


# Tag the `latest` build with the version from git and push it.
# Call it like `DOCKER_PUSH_LATEST=true make docker-push/node`
docker-push/%:
	$(eval PROJECT = $*)
	docker tag $(DOCKER_USERNAME)/$(PROJECT):latest $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	docker push $(DOCKER_USERNAME)/$(PROJECT):$(VER)
	if [ "$(DOCKER_PUSH_LATEST)" = "true" ]; then \
		echo docker push $(DOCKER_USERNAME)/$(PROJECT):latest ; \
	fi


docker-build-all: docker-build/node docker-build/client docker-build/execution-engine
docker-push-all: docker-push/node docker-push/client docker-push/execution-engine


# Miscellaneous tools to install once.

.mark.rustup-update:
	rustup update 
	rustup toolchain install nightly
	rustup target add wasm32-unknown-unknown --toolchain nightly
	touch .mark.rustup-update

.mark.protoc-install:
	if [ -z "$$(which protoc)" ]; then \
		curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v3.6.1/protoc-3.6.1-linux-x86_64.zip ; \
		unzip protoc-3.6.1-linux-x86n_64.zip -d protoc ; \
		mv protoc/bin/* /usr/local/bin/ ; \
		mv protoc/include/* /usr/local/include/ ; \
		chmod +x /usr/local/bin/protoc ; \
		rm -rf protoc* ; \
	fi
	touch .mark.protoc-install