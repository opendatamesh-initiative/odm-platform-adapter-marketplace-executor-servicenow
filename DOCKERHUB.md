# ODM Platform Marketplace Executor for ServiceNow

The ServiceNow executor is a marketplace executor adapter of [Open Data Mesh Platform](https://github.com/opendatamesh-initiative) V2. The [Marketplace Server](https://hub.docker.com/r/opendatamesh/odm-platform-pp-marketplace) forwards each access request (subscribe or unsubscribe) to it; the executor opens a ServiceNow ticket (a record in any table, a Service Catalog order, an Import Set row or a call to your own Scripted REST API) and reports `PENDING` with the ticket link. When the ticket is closed, a ServiceNow callback tells the executor, which maps the ticket state and close code to `GRANTED`, `DENIED`, `REVOKED` or `ERROR` and sends the result back to the Marketplace. It is stateless (no database) and ships as a Quarkus native executable that starts in well under a second, so it can scale to zero.

- **Source code:** [opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow)
- **Documentation:** [README](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/README.md)
- **Issues:** [GitHub issues](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/issues)

## Tags

Each [GitHub release](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/releases) publishes one image tagged with its version, without the `v` prefix:

```bash
docker pull opendatamesh/odm-platform-adapter-marketplace-executor-servicenow:<version>
```

There is no `latest` tag: always pin a version. The same image is also published to `ghcr.io/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow`.

## Requirements

| Dependency | Required | Notes |
| --- | --- | --- |
| [Marketplace Server](https://hub.docker.com/r/opendatamesh/odm-platform-pp-marketplace) | Yes | Lists the executor in `odm.utility-plane.marketplace-executors` and receives its results. |
| ServiceNow instance (Washington DC or later) | Yes | An integration user with OAuth 2.0 client credentials or an API key, and a Business Rule or Flow that calls the executor when a ticket closes. See [ServiceNow setup](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/servicenow-setup.md). |
| Authenticating gateway | Yes, to receive completions | The executor's endpoints have no authentication of their own: ServiceNow calls them through a gateway, such as blindata-agent. See [Securing the webhook](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/servicenow-setup.md#5-securing-the-webhook). |

## Quick start

The ticket profiles (`servicenow.profiles.subscribe` and `servicenow.profiles.unsubscribe`) are required and are not packaged in the image: without them the executor doesn't start. Put them in a YAML file, for example `application.yml`, with a profile like this one, which opens an incident:

```yaml
servicenow:
  profiles:
    subscribe:
      type: TABLE
      target: incident
      fields:
        short_description: "Grant access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
      outcome-rules:                 # first match wins; states: 6 Resolved, 7 Closed, 8 Canceled
        - when:
            state: "6|7"
            close_code: '(?i)(Solution provided|Workaround provided|Resolved by .*)'
          outcome: FULFILLED
        - when:
            state: "8"
          outcome: REFUSED
        - when:
            state: "6|7"
          outcome: FAILED
    unsubscribe:
      type: TABLE
      target: incident
      fields:
        short_description: "Revoke access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
      outcome-rules:
        - when:
            state: "6|7"
            close_code: '(?i)(Solution provided|Workaround provided|Resolved by .*)'
          outcome: FULFILLED
        - when:
            state: "6|7"
          outcome: FAILED
```

Then start the executor with OAuth 2.0 client credentials, with the file mounted where the executor reads it:

```bash
docker run --name odm-executor-servicenow -p 9019:9019 \
  -e SERVICENOW_INSTANCE_URL=https://example.service-now.com \
  -e SERVICENOW_CLIENT_ID=<client id> \
  -e SERVICENOW_CLIENT_SECRET=change-me \
  -e ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS=http://marketplace.example.com:8080 \
  -v "$(pwd)/application.yml:/work/config/application.yml:ro" \
  opendatamesh/odm-platform-adapter-marketplace-executor-servicenow:<version>
```

For an API key use `-e SERVICENOW_AUTH_TYPE=API_KEY -e SERVICENOW_API_KEY=...` instead of the client id and secret. The executor validates its configuration on startup and, if it's invalid, exits with a numbered list of problems (read it with `docker logs`).

When it's up, the API is documented at `http://localhost:9019/swagger-ui` (OpenAPI at `/api-docs`). It exposes:

- `POST /api/v1/up/marketplace-executor/requests`, called by the Marketplace Server.
- `POST /api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events`, called by ServiceNow through the gateway.

Register the executor in the Marketplace Server with its internal address, for example `{ "name": "servicenow", "address": "http://executor-servicenow.example.com:9019", "active": true }`. Keep it reachable only by the Marketplace Server and by the gateway.

## Environment variables

### Image

The image defines no environment variables of its own. Every setting is passed as an environment variable or in the mounted configuration file: an environment variable wins over the file.

### Application

Every property can be set in the configuration file or as an environment variable, using the Quarkus mapping (upper case, dots and dashes become underscores: `servicenow.auth.type` becomes `SERVICENOW_AUTH_TYPE`). Some properties also have a short variable name, shown in brackets.

| Property | Description | Default |
| --- | --- | --- |
| `servicenow.instance-url` (`SERVICENOW_INSTANCE_URL`) | ServiceNow instance URL. Must be `https` (`http` only for `http://localhost`). | none, required |
| `servicenow.auth.type` | `OAUTH2_CLIENT_CREDENTIALS` or `API_KEY`. | `OAUTH2_CLIENT_CREDENTIALS` |
| `servicenow.auth.oauth2.client-id` (`SERVICENOW_CLIENT_ID`) | OAuth client id. Required with OAuth. | none |
| `servicenow.auth.oauth2.client-secret` (`SERVICENOW_CLIENT_SECRET`) | OAuth client secret. Required with OAuth. | none |
| `servicenow.auth.oauth2.token-url` | OAuth token endpoint. | `<instance-url>/oauth_token.do` |
| `servicenow.auth.oauth2.scope` | OAuth scope, omitted from the token request when not set. | none |
| `servicenow.auth.api-key.value` (`SERVICENOW_API_KEY`) | API key. Required with `API_KEY`. | none |
| `servicenow.auth.api-key.header-name` | Header that carries the API key. | `x-sn-apikey` |
| `servicenow.filter.*` | Regular expressions that select the requests this executor handles (operations, data products, ports, consumers, requesters, properties). Requests that don't match are refused with `409`. | handles every request |
| `servicenow.profiles.subscribe` / `servicenow.profiles.unsubscribe` | Ticket profile of each operation: `type` (`TABLE`, `SERVICE_CATALOG`, `IMPORT_SET` or `SCRIPTED_REST`), target, templated fields and outcome rules. Set them in the configuration file. | none, required |
| `servicenow.messages.*` | Templates of the messages sent to the Marketplace with each result. | built-in |
| `servicenow.messages.closing-user.include` (`SERVICENOW_MESSAGES_INCLUDE_CLOSING_USER`) | `false` keeps the ServiceNow user who closed the ticket out of the messages. | `true` |
| `odm.product-plane.marketplace-service.address` | Base URL of the Marketplace Server, where results are sent. | `http://localhost:8085` |
| `odm.product-plane.marketplace-service.result-delivery.max-attempts` | Attempts to send a result to the Marketplace. | `5` |
| `odm.product-plane.marketplace-service.result-delivery.backoff` | Wait between result delivery attempts. | `1s` |
| `quarkus.config.locations` (`QUARKUS_CONFIG_LOCATIONS`) | Path of the configuration file, when it isn't mounted at `/work/config/application.yml`. | none |
| `quarkus.http.port` | HTTP port. | `9019` |

The full list, with the profile and filter keys and the startup checks, is in [Configuration reference](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/configuration-reference.md). Keep secrets in environment variables or mounted secrets, never in a committed file.

## Ports and volumes

- **Port:** `9019` (HTTP), or the value of `quarkus.http.port`.
- **Volumes:** none. Mount the configuration file at `/work/config/application.yml` (the working directory is `/work`), or anywhere with `QUARKUS_CONFIG_LOCATIONS`. The process runs as user `1001`, so mounted files must be readable by it.

## Learn more

- [Scenarios](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/README.md#1-pick-your-scenario): [simple access ticket](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/scenarios/table-api.md), [Service Catalog order](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/scenarios/service-catalog.md), [Import Set](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/scenarios/import-set.md), [Scripted REST API](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/scenarios/scripted-rest.md)
- [Deployment](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/deployment.md), with quickstarts for Cloud Run, Knative, KEDA and Azure Container Apps
- [Concepts](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/concepts.md) and [Message templates and privacy](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/message-templates.md)
- [API reference](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/api-reference.md) and [Troubleshooting](https://github.com/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow/blob/main/docs/reference/troubleshooting.md)

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
