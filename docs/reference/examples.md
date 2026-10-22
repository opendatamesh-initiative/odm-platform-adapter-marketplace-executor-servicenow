# Examples

[Back to README](../../README.md)

## Overview

Step-by-step runs of each scenario with every payload, and configuration recipes that work with any scenario. To set a scenario up, follow its page from the [README](../../README.md#1-pick-your-scenario).

## Contents

- [Shared example](#shared-example)
- [Simple access ticket](#simple-access-ticket)
- [Catalog order with approvals](#catalog-order-with-approvals)
- [Import Set with transform map](#import-set-with-transform-map)
- [Your own Scripted REST API](#your-own-scripted-rest-api)
- [Cross-cutting scenarios](#cross-cutting-scenarios)

## Shared example

All the examples use:

- instance `https://acme.service-now.com`, OAuth client credentials from the environment, and a filter that handles the `urn:dp:finance:*` data products;
- the Business Rule of each scenario page, with the properties `x_acme.marketplace.gateway_*`;
- this request, posted by the Marketplace Service to `POST /api/v1/up/marketplace-executor/requests`:

```json
{
  "v": "1",
  "operation": "MARKETPLACE_SUBSCRIBE",
  "request": {
    "name": "Sales data for Marketing",
    "identifier": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "provider": {
      "dataProductFqn": "urn:dp:finance:sales",
      "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"]
    },
    "consumer": { "type": "user", "identifier": "jane.doe@acme.com" },
    "requester": { "type": "user", "identifier": "jane.doe@acme.com" },
    "properties": {},
    "startDate": "2026-11-01T00:00:00Z",
    "endDate": "2027-11-01T00:00:00Z"
  }
}
```

## Simple access ticket

Setup: [scenario page](../scenarios/table-api.md).

1. The Marketplace Service posts the [shared example request](#shared-example) to `POST /api/v1/up/marketplace-executor/requests` and gets `202`.
2. The executor calls `POST https://acme.service-now.com/api/now/table/incident` with this JSON body (the `correlation_id` entry is added by the executor):

```json
{
  "short_description": "Grant access to urn:dp:finance:sales for jane.doe@acme.com",
  "description": "Marketplace request: 7c9e6679-7425-40de-944b-e07fc1f90ae7\nData product: urn:dp:finance:sales\nPorts: urn:dp:finance:sales:port:orders, urn:dp:finance:sales:port:customers\nConsumer: user jane.doe@acme.com\nRequested by: jane.doe@acme.com\nStart date: 2026-11-01T00:00:00Z\nEnd date: 2027-11-01T00:00:00Z",
  "category": "inquiry",
  "correlation_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

3. ServiceNow answers `{"result": {"number": "INC0010042", "sys_id": "9d385017c611228701d22104cc95c371", ...}}`. The executor posts to `/requests/7c9e6679-.../results`:

```json
{
  "status": "PENDING",
  "message": "ServiceNow ticket INC0010042 opened for MARKETPLACE_SUBSCRIBE of urn:dp:finance:sales (ports: urn:dp:finance:sales:port:orders, urn:dp:finance:sales:port:customers). Awaiting fulfilment: https://acme.service-now.com/incident.do?sys_id=9d385017c611228701d22104cc95c371",
  "provider": {
    "dataProductFqn": "urn:dp:finance:sales",
    "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"]
  },
  "createdAt": "2026-10-16T14:02:11Z"
}
```

4. A fulfiller resolves the incident (state 6, close code `Solution provided`). The Business Rule sends the webhook payload sent by the Business Rule through the gateway. The executor answers `200 {"outcome": "REPORTED", "status": "GRANTED"}` and posts:

```json
{
  "status": "GRANTED",
  "message": "GRANTED through ServiceNow ticket INC0010042 (https://acme.service-now.com/incident.do?sys_id=9d385017c611228701d22104cc95c371) closed by Mario Rossi. Close notes: Access granted to both ports through AD group GRP-SALES-READ",
  "provider": {
    "dataProductFqn": "urn:dp:finance:sales",
    "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"]
  },
  "createdAt": "2026-10-16T15:40:03Z"
}
```

If the incident is resolved with `No resolution provided` (or cancelled, state 8) the same event produces `DENIED` with `Request refused on ServiceNow: ticket INC0010042 (...) closed by ...`. For a `MARKETPLACE_UNSUBSCRIBE` the same closing produces `REVOKED`, and a refusal produces an `ERROR` with that same "refused on ServiceNow" message.

## Catalog order with approvals

Setup: [scenario page](../scenarios/service-catalog.md).

1. The Marketplace Service posts the [shared example request](#shared-example) and gets `202`.
2. The executor resolves the requester: `GET /api/now/table/sys_user?sysparm_query=email=jane.doe@acme.com&sysparm_fields=sys_id&sysparm_limit=1` answers `{"result":[{"sys_id":"6816f79cc0a8016401c5a33be04be441"}]}`.
3. The executor orders the item, `POST /api/sn_sc/servicecatalog/items/<item sys_id>/order_now`:

```json
{
  "sysparm_quantity": "1",
  "sysparm_requested_for": "6816f79cc0a8016401c5a33be04be441",
  "variables": {
    "data_product": "urn:dp:finance:sales",
    "ports": "urn:dp:finance:sales:port:orders, urn:dp:finance:sales:port:customers",
    "consumer": "user: jane.doe@acme.com",
    "period": "2026-11-01T00:00:00Z - 2027-11-01T00:00:00Z",
    "marketplace_request_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
  }
}
```

4. ServiceNow answers with the request (`"request_number": "REQ0010007"`, `"request_id": "a1b2c3d4db2a0110a5b4b3c9d9961937"`). The executor posts `PENDING`:

```json
{
  "status": "PENDING",
  "message": "ServiceNow ticket REQ0010007 opened for MARKETPLACE_SUBSCRIBE of urn:dp:finance:sales (ports: urn:dp:finance:sales:port:orders, urn:dp:finance:sales:port:customers). Awaiting fulfilment: https://acme.service-now.com/sc_request.do?sys_id=a1b2c3d4db2a0110a5b4b3c9d9961937",
  "provider": { "dataProductFqn": "urn:dp:finance:sales", "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"] },
  "createdAt": "2026-10-16T14:02:11Z"
}
```

5. The request is approved and the RITM is closed complete: the Business Rule sends the webhook payload sent by the Business Rule. The executor answers `200 REPORTED GRANTED` and posts:

```json
{
  "status": "GRANTED",
  "message": "GRANTED through ServiceNow ticket RITM0010011 (https://acme.service-now.com/sc_req_item.do?sys_id=c3a1f2e4db2a0110a5b4b3c9d9961937) closed by Mario Rossi. Close notes: Access granted through AD group GRP-SALES-READ",
  "provider": { "dataProductFqn": "urn:dp:finance:sales", "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"] },
  "createdAt": "2026-10-16T15:40:03Z"
}
```

If the approver rejects the request (`approval: rejected`), the result is `DENIED` with `Request refused on ServiceNow: ticket RITM0010011 (...) closed by .... Reason: <close notes>`.

## Import Set with transform map

Setup: [scenario page](../scenarios/import-set.md).

1. The Marketplace Service posts the [shared example request](#shared-example) and gets `202`.
2. The executor posts a row to `POST /api/now/import/u_marketplace_access`:

```json
{
  "u_short_description": "Grant access to urn:dp:finance:sales for jane.doe@acme.com",
  "u_description": "Marketplace request: 7c9e6679-7425-40de-944b-e07fc1f90ae7\nData product: urn:dp:finance:sales\nPorts: urn:dp:finance:sales:port:orders, urn:dp:finance:sales:port:customers\nConsumer: user jane.doe@acme.com\nRequested by: jane.doe@acme.com\nPeriod: 2026-11-01T00:00:00Z - 2027-11-01T00:00:00Z",
  "u_marketplace_request_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

3. ServiceNow answers `{"import_set": "ISET0010001", "staging_table": "u_marketplace_access", "result": [{"transform_map": "Marketplace access to sc_task", "table": "sc_task", "display_name": "number", "display_value": "SCTASK0010020", "status": "inserted", "sys_id": "f4e8a1c2db2a0110a5b4b3c9d9961937"}]}` and the transform map creates the `sc_task` with `correlation_id` set. The executor posts `PENDING` with the message `ServiceNow ticket SCTASK0010020 opened for MARKETPLACE_SUBSCRIBE of urn:dp:finance:sales (ports: ...). Awaiting fulfilment: https://acme.service-now.com/sc_task.do?sys_id=f4e8a1c2db2a0110a5b4b3c9d9961937`.
4. The task is closed complete: the Business Rule sends the payload sent by the Business Rule. The executor answers `200 REPORTED GRANTED` and posts:

```json
{
  "status": "GRANTED",
  "message": "GRANTED through ServiceNow ticket SCTASK0010020 (https://acme.service-now.com/sc_task.do?sys_id=f4e8a1c2db2a0110a5b4b3c9d9961937) closed by Mario Rossi. Close notes: Access granted through AD group GRP-SALES-READ",
  "provider": { "dataProductFqn": "urn:dp:finance:sales", "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"] },
  "createdAt": "2026-10-16T15:40:03Z"
}
```

## Your own Scripted REST API

Setup: [scenario page](../scenarios/scripted-rest.md).

1. The Marketplace Service posts the [shared example request](#shared-example) and gets `202`.
2. The executor calls `POST https://acme.service-now.com/api/x_acme_mkt/v1/access_tickets`:

```json
{
  "operation": "MARKETPLACE_SUBSCRIBE",
  "short_description": "Grant access to urn:dp:finance:sales for jane.doe@acme.com",
  "description": "Marketplace request: 7c9e6679-7425-40de-944b-e07fc1f90ae7\nPorts: urn:dp:finance:sales:port:orders, urn:dp:finance:sales:port:customers\nRequested by: jane.doe@acme.com\nPeriod: 2026-11-01T00:00:00Z - 2027-11-01T00:00:00Z",
  "correlation_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

3. The script answers `201 {"result": {"number": "SCTASK0010031", "sys_id": "0d9be3a5db2a0110a5b4b3c9d9961937", "table": "sc_task"}}`. The executor posts `PENDING`: `ServiceNow ticket SCTASK0010031 opened for MARKETPLACE_SUBSCRIBE of urn:dp:finance:sales (ports: ...). Awaiting fulfilment: https://acme.service-now.com/sc_task.do?sys_id=0d9be3a5db2a0110a5b4b3c9d9961937`.
4. The task is closed complete: the Business Rule sends the payload sent by the Business Rule. The executor answers `200 REPORTED GRANTED` and posts:

```json
{
  "status": "GRANTED",
  "message": "GRANTED through ServiceNow ticket SCTASK0010031 (https://acme.service-now.com/sc_task.do?sys_id=0d9be3a5db2a0110a5b4b3c9d9961937) closed by Mario Rossi. Close notes: Access granted through AD group GRP-SALES-READ",
  "provider": { "dataProductFqn": "urn:dp:finance:sales", "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"] },
  "createdAt": "2026-10-16T15:40:03Z"
}
```

## Cross-cutting scenarios

### Several executors behind one Marketplace Service

Give each executor an **exclusive** filter, for example:

```yaml
# executor A (this one)
servicenow:
  filter:
    provider-data-product-fqns: ['urn:dp:finance:.*']
# executor B
#   provider-data-product-fqns: ['(?!urn:dp:finance:).*']
```

A request no executor accepts is rejected by the Marketplace Service at submission. See [request filter](concepts.md#request-filter).

### Route only some requests to ServiceNow

Combine criteria, for example only subscriptions to finance and risk products requested by teams or users of a cost centre:

```yaml
servicenow:
  filter:
    operations: ['MARKETPLACE_SUBSCRIBE']
    provider-data-product-fqns: ['urn:dp:finance:.*', 'urn:dp:risk:.*']
    consumer-types: ['(?i)user|team']
    properties:
      costCenter: 'FIN-.*'
```

### Keep the closing user out of the marketplace

```yaml
servicenow:
  messages:
    closing-user:
      include: false
```

or `SERVICENOW_MESSAGES_INCLUDE_CLOSING_USER=false`. See [Privacy](message-templates.md#privacy).

### Different wording or different rules per operation

Per-profile `messages.*` and per-rule `message` override the global messages. See [Message templates](message-templates.md#message-templates).

---
[Back to README](../../README.md) | [Concepts](concepts.md) | [Configuration reference](configuration-reference.md)
