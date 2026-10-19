# Configuration reference

[Back to README](../README.md)

## Overview (in plain words)

Everything the executor does is driven by configuration: where ServiceNow is, how to log in, which requests to take, what the ticket looks like, and which ticket states mean "approved" or "rejected". This page lists every property. If you just want a working example, start from one of the [use cases](use-cases.md) and come back here for the details.

## Contents

- [How configuration is loaded](#how-configuration-is-loaded)
- [Connection and authentication](#connection-and-authentication)
- [Request filter](#request-filter)
- [Ticket profiles](#ticket-profiles)
- [Outcome rules](#outcome-rules)
- [Messages](#messages)
- [Marketplace Service and result delivery](#marketplace-service-and-result-delivery)
- [Server and other Quarkus settings](#server-and-other-quarkus-settings)
- [Startup validation](#startup-validation)

## How configuration is loaded

The executor is a Quarkus application. All `servicenow.*` keys use YAML kebab-case.

- The packaged `src/main/resources/application.yml` contains connection, authentication and message defaults, but **no ticket profiles**. You must provide `servicenow.profiles.subscribe` and `servicenow.profiles.unsubscribe` yourself, otherwise the executor refuses to start with a "required property ... not found" error.
- Put your profiles and other overrides in an **external file**: `config/application.yml` in the executor's working directory (easiest with a container volume mount), or any path passed with `QUARKUS_CONFIG_LOCATIONS=/path/file.yml` (or `-Dquarkus.config.locations=...`).
- Any key can be overridden by an environment variable using the standard Quarkus mapping: upper case, dots and dashes become underscores (`servicenow.auth.type` is `SERVICENOW_AUTH_TYPE`). Only the variables marked "explicit" below are referenced by name in the packaged `application.yml`.
- In an environment variable a comma separates list items. If a regex contains a comma, configure it in the YAML file.
- Secrets must come from the environment or from mounted secrets, never from a committed file.

> **Why no packaged profiles?** Quarkus merges configuration sources entry by entry: list items by index and map entries by key. Default `fields` or `outcome-rules` shipped in the jar would be **merged into** yours instead of being replaced. For example, a rule `state: "3"` would silently inherit a packaged `close_code` condition and never match. Scalar values (instance URL, credentials, messages, filter entries) can still be overridden safely through environment variables.

## Connection and authentication

| Key | Env var | Default | Required | Description |
|:--|:--|:--|:--|:--|
| `servicenow.instance-url` | `SERVICENOW_INSTANCE_URL` (explicit) | none | **yes** | Instance base URL. Must be `https` (`http` only for `http://localhost...`). A trailing `/` is ignored |
| `servicenow.auth.type` | `SERVICENOW_AUTH_TYPE` (mapping) | `OAUTH2_CLIENT_CREDENTIALS` | no | `OAUTH2_CLIENT_CREDENTIALS` (recommended) or `API_KEY` |
| `servicenow.auth.oauth2.client-id` | `SERVICENOW_CLIENT_ID` (explicit) | empty | yes, for OAuth | OAuth application client id |
| `servicenow.auth.oauth2.client-secret` | `SERVICENOW_CLIENT_SECRET` (explicit) | empty | yes, for OAuth | OAuth application client secret |
| `servicenow.auth.oauth2.token-url` | `SERVICENOW_AUTH_OAUTH2_TOKEN_URL` (mapping) | `{instance-url}/oauth_token.do` | no | Token endpoint override |
| `servicenow.auth.oauth2.scope` | `SERVICENOW_AUTH_OAUTH2_SCOPE` (mapping) | none | no | OAuth scope, omitted from the token request when not set |
| `servicenow.auth.api-key.header-name` | `SERVICENOW_AUTH_API_KEY_HEADER_NAME` (mapping) | `x-sn-apikey` | no | Header that carries the key |
| `servicenow.auth.api-key.value` | `SERVICENOW_API_KEY` (explicit) | empty | yes, for `API_KEY` | The API key |

With OAuth the executor requests a token with the `client_credentials` grant from the token URL, caches it until it expires (default 3600 seconds when the answer has no `expires_in`) and sends it as `Authorization: Bearer`. `401` and `403` answers and token failures become `ERROR` results whose message names the auth type and what to check (OAuth application, integration user roles, API access policy), never any secret. HTTP calls use a 60 second connect timeout.

## Request filter

`servicenow.filter.*`. Every value is a **list of regular expressions** that must **fully match** (`(?i)` makes a pattern case-insensitive). A criterion that is not configured matches everything; an empty filter handles every request. Semantics and the exclusivity rule are in [Concepts](concepts.md#request-filter).

| Key | Matched against |
|:--|:--|
| `servicenow.filter.operations` | `MARKETPLACE_SUBSCRIBE` / `MARKETPLACE_UNSUBSCRIBE` |
| `servicenow.filter.provider-data-product-fqns` | `request.provider.dataProductFqn` |
| `servicenow.filter.port-fqns` | **Every** port in `request.provider.dataProductPortsFqn` (a request without ports never matches a configured port criterion) |
| `servicenow.filter.consumer-types` | `request.consumer.type` |
| `servicenow.filter.consumer-identifiers` | `request.consumer.identifier` |
| `servicenow.filter.requester-identifiers` | `request.requester.identifier` |
| `servicenow.filter.properties.<key>` | `request.properties.<key>` (a single regex per key; a missing property does not match) |

```yaml
servicenow:
  filter:
    operations: ["MARKETPLACE_.*"]
    provider-data-product-fqns:
      - 'urn:dp:finance:.*'
      - 'urn:dp:risk:.*'
    consumer-types: ['(?i)user|team']
    properties:
      costCenter: 'FIN-.*'
```

## Ticket profiles

One profile per operation: `servicenow.profiles.subscribe.*` (for `MARKETPLACE_SUBSCRIBE`) and `servicenow.profiles.unsubscribe.*` (for `MARKETPLACE_UNSUBSCRIBE`). Both are required.

| Key (under `profiles.<op>`) | Default | Required | Description |
|:--|:--|:--|:--|
| `type` | none | **yes** | `TABLE`, `SERVICE_CATALOG`, `IMPORT_SET` or `SCRIPTED_REST` |
| `target` | none | for `TABLE`, `SERVICE_CATALOG`, `IMPORT_SET` | Table name (TABLE), catalog item sys_id (SERVICE_CATALOG), staging table (IMPORT_SET) |
| `path` | none | for `SCRIPTED_REST` | Endpoint path, relative to the instance URL |
| `method` | `POST` | no | `POST` or `PATCH` (used by SCRIPTED_REST; validated for every profile) |
| `correlation-field` | per interface, see below | no | Field, variable or column that stores the marketplace request UUID |
| `fields.<name>` | none | no | Ticket content: field, variable or column name to [Qute template](message-templates.md). The correlation entry is **always added by the executor** after rendering and cannot be overridden: a field named like the correlation target is a startup error |
| `requester-match-field` | `email` | no | SERVICE_CATALOG only: the `sys_user` field compared with the requester identifier |
| `submit-cart-when-two-step` | `true` | no | SERVICE_CATALOG only: submit the cart when the instance answers `order_now` with a cart (two-step checkout) |
| `response.number-pointer` | per interface | for `SCRIPTED_REST` | [JSON Pointer](https://datatracker.ietf.org/doc/html/rfc6901) of the ticket number in the creation response |
| `response.sys-id-pointer` | per interface | for `SCRIPTED_REST` | JSON Pointer of the ticket sys_id |
| `response.table-pointer` | per interface | no | JSON Pointer of the ticket table |
| `response.table` | per interface | no | Fixed table name, used when there is no table pointer (see precedence below) |
| `outcome-rules` | none | **yes** | See [Outcome rules](#outcome-rules) |
| `messages.pending` / `fulfilled` / `refused` / `failed` / `technical-error` | none | no | Per-profile message overrides |

### Per-interface defaults

Each default can be overridden by the profile.

| Type | Endpoint called | Body | Correlation default | Ticket reference default |
|:--|:--|:--|:--|:--|
| `TABLE` | `POST /api/now/table/{target}` | rendered fields + correlation field | field `correlation_id` | number `/result/number`, sys_id `/result/sys_id`, table = `target` |
| `SERVICE_CATALOG` | `POST /api/sn_sc/servicecatalog/items/{target}/order_now` (+ `POST /api/sn_sc/servicecatalog/cart/submit_order` for two-step checkout) | `sysparm_quantity: "1"`, `sysparm_requested_for` (resolved requester), `variables` = rendered fields + correlation | variable `marketplace_request_id` | number `/result/request_number` (else `/result/number`), sys_id `/result/request_id` (else `/result/sys_id`), table `sc_request` |
| `IMPORT_SET` | `POST /api/now/import/{target}` | rendered fields + correlation column | column `u_marketplace_request_id` | number `/result/0/display_value`, sys_id `/result/0/sys_id`, table `/result/0/table`. A row status of `error` or `ignored` fails the creation with the ServiceNow message |
| `SCRIPTED_REST` | `{method} {path}` | rendered fields + correlation field | field `correlation_id` | `number-pointer` and `sys-id-pointer` **required**; table from `table-pointer` or `table` (optional) |

Table resolution precedence: for `TABLE`, `SERVICE_CATALOG` and `SCRIPTED_REST`, a non-empty value found at `table-pointer` wins over the fixed `table`. For `IMPORT_SET`, the fixed `table` wins over the pointer.

The ticket link is `{instance-url}/{table}.do?sys_id={sysId}`, empty when the table or sys_id is unknown. Response shapes vary with the release and the instance configuration: that is why the pointers are overridable.

### Service Catalog requester resolution

Catalog orders are placed **on behalf of the marketplace requester**. Before ordering, the executor looks up `GET /api/now/table/sys_user?sysparm_query={requester-match-field}={requester identifier}&sysparm_fields=sys_id&sysparm_limit=1` and sends the sys_id as `sysparm_requested_for`.

- The requester must be of type `user`; otherwise no order is placed and an `ERROR` is reported.
- A requester identifier containing `^` is rejected (it would break the encoded query).
- A requester not found in `sys_user` produces an `ERROR` ("Requester ... not found in ServiceNow sys_user"). The executor never falls back to ordering for the integration user.
- ServiceNow-side prerequisites (ordering on behalf of others, mandatory variables) are in [ServiceNow setup](servicenow-setup.md#service-catalog-ordering-on-behalf-of-others).

## Outcome rules

`servicenow.profiles.<op>.outcome-rules` is a list. Rules are evaluated **in order** against the webhook `fields`; a rule matches when every `when` entry names a field that is present and whose value matches the regex entirely. The **first match wins**; no match means the event is ignored (`NON_TERMINAL_STATE`).

| Key | Description |
|:--|:--|
| `outcome-rules[n].when.<field>` | Condition: webhook field name to regex (full match, case-sensitive unless `(?i)`). `when` must not be empty |
| `outcome-rules[n].outcome` | `FULFILLED`, `REFUSED` or `FAILED` (see the [status table](concepts.md#status-taxonomy-and-rules)) |
| `outcome-rules[n].message` | Optional message template for this rule only |

Each profile needs at least one rule, and at least one with outcome `FULFILLED`.

```yaml
outcome-rules:
  - when:
      state: "6|7"
      close_code: '(?i)(Solution provided|Workaround provided|Resolved by .*)'
    outcome: FULFILLED
  - when:
      state: "8"
    outcome: REFUSED
    message: "Request cancelled on ServiceNow ({ticket.number}). {fields.close_notes}"
  - when:              # fallback, keep it last: a resolved/closed ticket always yields a final result
      state: "6|7"
    outcome: FAILED
```

- The incident close codes above are the ones shipped by current releases (verified on Zurich): `Solution provided`, `Workaround provided`, `Resolved by caller|change|problem|request`, `No resolution provided`, `Duplicate`, `Known error`, `User error`. Check **System Definition > Choice Lists** (`incident.close_code`) on your instance, since customers often customise them.
- Without a fallback rule, a resolved ticket whose close code matches no rule is ignored and the request stays `PENDING`.
- Use **single quotes** for regexes with backslashes (`'\d+'`); in double quotes they would need doubling.

## Messages

| Key | Env var | Default | Description |
|:--|:--|:--|:--|
| `servicenow.messages.pending` | mapping | built-in template | Message of the `PENDING` result |
| `servicenow.messages.fulfilled` | mapping | built-in template | Cause `FULFILLED` |
| `servicenow.messages.refused` | mapping | built-in template | Cause `REFUSED` |
| `servicenow.messages.failed` | mapping | built-in template | Cause `FAILED` |
| `servicenow.messages.technical-error` | mapping | built-in template | Ticket could not be opened |
| `servicenow.messages.closing-user.include` | `SERVICENOW_MESSAGES_INCLUDE_CLOSING_USER` (explicit) | `true` | `false` keeps the closing user out of messages |
| `servicenow.messages.closing-user.field` | mapping | `closed_by` | Webhook field holding the closing user |

Default texts, the template syntax, the data model and the privacy notes are in [Message templates and privacy](message-templates.md).

## Marketplace Service and result delivery

| Key | Env var | Default | Description |
|:--|:--|:--|:--|
| `odm.product-plane.marketplace-service.active` | mapping | `true` in the packaged `application.yml` | Required boolean of the configuration mapping |
| `odm.product-plane.marketplace-service.address` | `ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS` (mapping) | `http://localhost:8085` | Marketplace Service base URL |
| `odm.product-plane.marketplace-service.result-delivery.max-attempts` | mapping | `5` | Attempts to post a result |
| `odm.product-plane.marketplace-service.result-delivery.backoff` | mapping | `1s` | Wait between attempts |

Posting is retried on **404** (the Marketplace Service calls the executor inside its own transaction, so the request may not be visible yet), on **5xx** and on I/O errors; other 4xx answers fail immediately. The `PENDING` post is **best effort**: if it cannot be delivered a warning is logged and the ticket stays open. A **final** result that cannot be delivered makes the webhook answer **500**, so ServiceNow can retry.

## Server and other Quarkus settings

Set in the packaged `application.yml`:

| Key | Value | Notes |
|:--|:--|:--|
| `quarkus.http.port` | `9019` | Override with `QUARKUS_HTTP_PORT` |
| `quarkus.smallrye-openapi.path` | `/api-docs` | OpenAPI document |
| `quarkus.swagger-ui.path` / `always-include` | `/swagger-ui` / `true` | Swagger UI is also available in production builds |
| `quarkus.qute.strict-rendering` | `false` | Do not change |
| `quarkus.qute.property-not-found-strategy` | `noop` | Do not change |

## Startup validation

The executor refuses to start with an invalid configuration and prints a numbered list. The checks are:

- `servicenow.instance-url` present, `https` (or `http://localhost`).
- OAuth: `client-id` and `client-secret` present. API key: `api-key.value` present.
- Per profile: `target` for `TABLE` / `SERVICE_CATALOG` / `IMPORT_SET`; `path`, `response.number-pointer` and `response.sys-id-pointer` for `SCRIPTED_REST`; `method` is `POST` or `PATCH`; no `fields` entry named like the correlation target; at least one outcome rule, at least one `FULFILLED`, no empty `when`.
- All outcome-rule and filter regexes compile.
- All templates (fields, rule messages, profile messages, global messages) parse, and `file:` templates exist.

Secrets are never printed. See [Troubleshooting](troubleshooting.md).

---
[Back to README](../README.md) | [Provisioning](provisioning.md)
