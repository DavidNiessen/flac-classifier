.DEFAULT_GOAL := build

FILE ?=
OPTS ?=

.PHONY: build test clean install run

build:                  ## Build the fat CLI JAR  →  build/libs/flac-classifier-*-all.jar
	./gradlew shadowJar

test:                   ## Run unit tests
	./gradlew test

clean:                  ## Remove all build artefacts
	./gradlew clean

install:                ## Publish library JAR to ~/.m2 (local development)
	./gradlew publishToMavenLocal

run: build              ## Analyse a FLAC file:  make run FILE=/path/to/track.flac [OPTS="--json"]
	@[ -n "$(FILE)" ] || { echo "Usage: make run FILE=/path/to/track.flac [OPTS='--json --verbose']"; exit 1; }
	java -jar $$(find build/libs -name "flac-classifier-*-all.jar" | head -1) $(FILE) $(OPTS)

help:                   ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'
