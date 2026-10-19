# Provisioning: build, deploy, run and verify

[Back to README](../README.md)

## Overview (in plain words)

The executor is a small, stateless Java service. You build it (or take a build), give it a configuration file and a few secrets, start it, and tell the ODM Marketplace Service where it lives. After that, a test request should create a ticket in ServiceNow. This page walks through those steps. The repository does not ship a Dockerfile, Helm chart or Kubernetes manifests: run the service however your platform runs Java 21 applications.

## Contents

- [Requirements](#requirements)
- [Build](#build)
- [Configure](#configure)
- [Run](#run)
- [Deployment notes](#deployment-notes)
- [Register with the Marketplace Service](#register-with-the-marketplace-service)
- [Verify](#verify)
- [Tests](#tests)

## Requirements

- Java **21** and Maven **3.9+** (or the included `./mvnw`) to build. The project is Quarkus 3.40.1.
- A ServiceNow instance, Washington DC or later, prepared as in [ServiceNow setup](servicenow-setup.md).
- A running ODM Marketplace Service (default address `http://localhost:8085`).
- An authenticating gateway (reverse proxy) for the webhook leg: see [Securing the webhook](servicenow-setup.md#5-securing-the-webhook).

## Build

```bash
git clone <repository-url>
cd odm-platform-adapter-marketplace-executor-servicenow

./mvnw package                  # JVM build, runner in target/quarkus-app/
./mvnw -Pnative package         # native build (Mandrel or GraalVM 21)
```

The JVM build is started with `java -jar target/quarkus-app/quarkus-run.jar`. The artifact is `org.opendatamesh:odm-platform-adapter-marketplace-executor-servicenow` (version in `pom.xml`).

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

2. **Ticket profiles** in an external file: the packaged `application.yml` contains **no** `servicenow.profiles`, so the executor will not start without them. Create `config/application.yml` in the working directory, or point `QUARKUS_CONFIG_LOCATIONS` at your file. Start from the [use case](use-cases.md) that matches your ServiceNow practice; the [Table API](use-case-table-api.md) one works as-is on a standard instance.

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

## Deployment notes

- **Containers / Kubernetes.** No image or manifests are provided. If you containerise the JVM build, copy the whole `target/quarkus-app/` directory (not just the jar), mount your `config/application.yml` (or set `QUARKUS_CONFIG_LOCATIONS`), and inject the `SERVICENOW_*` values from your secret store as environment variables. Expose port `9019` only inside the cluster.
- **Network isolation (hard requirement).** The webhook has no authentication of its own. The executor must be reachable **only** by the Marketplace Service and by the authenticating gateway: never directly from ServiceNow or the internet. See [ServiceNow setup](servicenow-setup.md#5-securing-the-webhook).
- **Egress.** The executor must reach the ServiceNow instance (HTTPS) and the Marketplace Service.
- **Several executors** behind one Marketplace Service need exclusive filters: see [Concepts](concepts.md#request-filter).
- **Secrets** come from the environment or mounted secrets, never from a committed file. Rotation steps are in [ServiceNow setup](servicenow-setup.md#rotating-secrets).

## Register with the Marketplace Service

The Marketplace Service forwards every request to the executors listed in its own configuration (`odm.utility-plane.marketplace-executors`), calling `{address}/api/v1/up/marketplace-executor/requests`. Add this executor:

```yaml
odm:
  utility-plane:
    marketplace-executors:
      - name: SERVICENOW
        address: http://marketplace-executor.internal:9019
        active: true
```

The `name` is free. Restart the Marketplace Service if it does not reload configuration. Also [secure the webhook](servicenow-setup.md#5-securing-the-webhook) (register the executor in your authenticating gateway) and create the ServiceNow Business Rule.

## Verify

1. **Health of the process.** Swagger UI at [http://localhost:9019/swagger-ui](http://localhost:9019/swagger-ui) and the OpenAPI document at [http://localhost:9019/api-docs](http://localhost:9019/api-docs) should load.
2. **ServiceNow credentials.** Run the `curl` token test from [ServiceNow setup](servicenow-setup.md#2-authentication-oauth-20-client-credentials-recommended).
3. **Webhook chain** (harmless): the [smoke test](servicenow-setup.md#5-securing-the-webhook) must answer `200 {"outcome":"IGNORED","reason":"UNKNOWN_REQUEST"}`.
4. **End to end.** Submit a request for a data product matched by your filter in the marketplace. In the executor log you should see `Opened ServiceNow ticket <number> (<table>) for request <uuid> operation <op>`, the request shows `PENDING` with the ticket link in the marketplace, and closing the ticket in ServiceNow produces the final status.

## Tests

```bash
./mvnw test      # outbound calls are mocked
```

---
[Back to README](../README.md) | [Troubleshooting](troubleshooting.md)
