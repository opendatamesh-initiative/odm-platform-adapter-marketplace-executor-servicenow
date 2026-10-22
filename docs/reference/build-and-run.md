# Build, run locally and release

[Back to README](../../README.md)

## Overview

The executor is a small, stateless Java service. This page covers building it, running it on your machine (with Maven or Docker), the tests, and how releases are published. To deploy it, follow a [platform quickstart](../../README.md#2-pick-your-platform) or [Deployment](deployment.md). Every release is published as a Docker image on GitHub Container Registry and Docker Hub, built by GitHub Actions. The repository does not ship a Helm chart or Kubernetes manifests; the image runs on any container platform, and the JVM build also runs as a plain Java 21 process.

## Contents

- [Requirements](#requirements)
- [Build](#build)
- [Configure](#configure)
- [Run](#run)
- [Run with Docker](#run-with-docker)
- [Tests](#tests)
- [CI/CD and releases](#cicd-and-releases)

## Requirements

- To run the published image: Docker (or any container runtime). Nothing else on the host.
- To build from source: Java **21** and Maven **3.9+** (or the included `./mvnw`). The project is Quarkus 3.40.1. A native image build also needs Docker (no local GraalVM or Mandrel is required).
- A ServiceNow instance, Washington DC or later, prepared as in [ServiceNow setup](servicenow-setup.md).
- A running ODM Marketplace Service (default address `http://localhost:8085`).
- An authenticating gateway (reverse proxy) for the webhook leg: see [Securing the webhook](servicenow-setup.md#5-securing-the-webhook).

## Build

```bash
git clone <repository-url>
cd odm-platform-adapter-marketplace-executor-servicenow

./mvnw package                  # JVM build, runner in target/quarkus-app/
./mvnw -Pnative package         # native build (Mandrel or GraalVM 21 installed locally)
```

The JVM build is started with `java -jar target/quarkus-app/quarkus-run.jar`. The artifact is `org.opendatamesh:odm-platform-adapter-marketplace-executor-servicenow` (version in `pom.xml`). To build the native executable that goes into the Docker image, see [Build the image locally](#build-the-image-locally).

## Configure

1. **Environment variables** for the connection and secrets:

| Environment variable | Purpose |
|:--|:--|
| `SERVICENOW_INSTANCE_URL` | **Required.** Instance base URL, `https` (`http://localhost...` is accepted for tests only) |
| `SERVICENOW_CLIENT_ID`, `SERVICENOW_CLIENT_SECRET` | OAuth 2.0 client credentials (default auth mode) |
| `SERVICENOW_API_KEY` | API key, used only when `servicenow.auth.type=API_KEY` |
| `SERVICENOW_AUTH_TYPE` | `OAUTH2_CLIENT_CREDENTIALS` (default) or `API_KEY` |
| `SERVICENOW_MESSAGES_INCLUDE_CLOSING_USER` | `false` to keep the closing user out of result messages (default `true`) |
| `ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS` | Marketplace Service base URL (`odm.product-plane.marketplace-service.address`) |
| `QUARKUS_CONFIG_LOCATIONS` | Path of an external configuration file (optional) |

Every property is listed in the [configuration reference](configuration-reference.md).

2. **Ticket profiles** in an external file: the packaged `application.yml` contains **no** `servicenow.profiles`, so the executor will not start without them. Create `config/application.yml` in the working directory, or point `QUARKUS_CONFIG_LOCATIONS` at your file. Start from the [scenario](../../README.md#1-pick-your-scenario) that matches your ServiceNow practice; the [simple access ticket](../scenarios/table-api.md) works as-is on a standard instance.

## Run

Development mode:

```bash
export SERVICENOW_INSTANCE_URL=https://acme.service-now.com
export SERVICENOW_CLIENT_ID=<oauth application client id>
export SERVICENOW_CLIENT_SECRET=<oauth application client secret>
export ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS=http://localhost:8085

./mvnw quarkus:dev
```

Packaged JVM build:

```bash
cd /opt/executor   # a directory containing config/application.yml
java -jar /path/to/target/quarkus-app/quarkus-run.jar
```

The executor listens on port **9019** (`quarkus.http.port`, override with `QUARKUS_HTTP_PORT`).

> **The executor refuses to start with an invalid configuration** and prints a numbered list of the problems (blank instance URL, missing credentials, unparsable template, profile without a `FULFILLED` rule, invalid regex, ...). Fix them and start again. Secrets are never printed. See [Troubleshooting](troubleshooting.md#the-executor-does-not-start).

## Run with Docker

The image contains the Quarkus **native** executable on `ubi9/ubi-minimal`. It runs as the non-root user `1001`, listens on port **9019** and needs no Java installation. Settings are passed as environment variables, plus a mounted file for the ticket profiles.

### Use the published image

Every release is published to two registries:

```bash
docker pull ghcr.io/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow:<version>
# or
docker pull opendatamesh/odm-platform-adapter-marketplace-executor-servicenow:<version>
```

Run it with the ticket profiles mounted where the executor looks for them (`/work/config/application.yml`):

```bash
docker run --rm -p 9019:9019 \
  -e SERVICENOW_INSTANCE_URL=https://acme.service-now.com \
  -e SERVICENOW_CLIENT_ID=<oauth application client id> \
  -e SERVICENOW_CLIENT_SECRET=<oauth application client secret> \
  -e ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS=http://host.docker.internal:8085 \
  -v "$(pwd)/config/application.yml:/work/config/application.yml:ro" \
  ghcr.io/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow:<version>
```

Replace `<version>` with a released version (for example `1.0.2`). `host.docker.internal` reaches a Marketplace Service running on the Docker host (on Linux add `--add-host=host.docker.internal:host-gateway`); in a real deployment use the service's real address. For API key authentication pass `-e SERVICENOW_AUTH_TYPE=API_KEY -e SERVICENOW_API_KEY=...` instead of the client id and secret. To keep secrets out of the command line, use `--env-file`.

### Build the image locally

You need Docker; no local GraalVM or Mandrel. The native executable is built inside a builder container, then copied into the runtime image:

```bash
./mvnw package -DskipTests -Dnative -Dquarkus.native.container-build=true
docker build -t odm-platform-adapter-marketplace-executor-servicenow .
```

The `Dockerfile` copies `target/*-runner` to `/work/application`, so the native build must run first. It takes several minutes.

## Tests

```bash
./mvnw test      # outbound calls are mocked
```

## CI/CD and releases

Two GitHub Actions workflows live in `.github/workflows/`:

| Workflow | Runs on | What it does |
|:--|:--|:--|
| `ci.yml` | Every push and pull request to `main` | Sets up JDK 21 (Temurin) and runs `./mvnw -B verify` |
| `cicd.yml` | A GitHub release is created | Checks the version, runs the tests, builds the native image and publishes it |

On a release, `cicd.yml` runs two jobs in sequence:

1. **`verify-version`** takes the release tag, strips every character that is not a digit or a dot (`v1.0.2` becomes `1.0.2`) and compares it with the version in `pom.xml`. If they differ, the workflow fails. It then runs the tests.
2. **`deploy-docker-images`** builds the native executable (`./mvnw -B package -DskipTests -Dnative -Dquarkus.native.container-build=true`), builds the image from the `Dockerfile` and pushes it, tagged with the release version, to:
   - GitHub Container Registry, with the workflow's `GITHUB_TOKEN`: `ghcr.io/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow:<version>`
   - Docker Hub: `opendatamesh/odm-platform-adapter-marketplace-executor-servicenow:<version>`. This needs the repository secrets `DOCKER_USERNAME` and `DOCKER_PASSWORD`.

To make a release:

1. Set the POM version to the release version, without `-SNAPSHOT` (for example `1.0.2`).
2. Merge the change to `main`.
3. Create a GitHub release with a tag that matches it: `v1.0.2` or `1.0.2`.
4. Check that the workflow is green and that the image is available in both registries.
5. Bump the POM to the next `-SNAPSHOT` version.

---
[Back to README](../../README.md) | [Troubleshooting](troubleshooting.md)
