# API reference

[Back to README](../README.md)

## Overview

The executor talks to two parties through HTTP. The **Marketplace Service** sends it access requests and receives results back. **ServiceNow** (through an authenticating gateway) tells it when a ticket is done. This page lists exactly what those messages look like.

Interactive documentation is available when the executor runs: Swagger UI at `http://localhost:9019/swagger-ui` and the OpenAPI document at `http://localhost:9019/api-docs`.

## Contents

- [Submit a request](#submit-a-request-post-apiv1upmarketplace-executorrequests)
- [Result sent to the Marketplace Service](#result-sent-to-the-marketplace-service)
- [Webhook](#webhook-post-apiv1upmarketplace-executorwebhooksservicenowticket-events)
- [Calls the executor makes](#calls-the-executor-makes)
- [Error body](#error-body)

## Submit a request: `POST /api/v1/up/marketplace-executor/requests`

Called by the Marketplace Service. Answers **202 Accepted** and works asynchronously.

| Status | Meaning |
|:--|:--|
| `202` | Accepted. The ticket is created in the background |
| `400` | Invalid request: a required field is missing, the operation is not `MARKETPLACE_SUBSCRIBE` / `MARKETPLACE_UNSUBSCRIBE`, or `startDate` is after `endDate` |
| `409` | `RequestNotHandledException`: the request does not match this executor's [request filter](concepts.md#request-filter). Nothing is created on ServiceNow and nothing is reported |
| `500` | Unexpected error |

Request body:

```json
{
  "v": "1",
  "operation": "MARKETPLACE_SUBSCRIBE | MARKETPLACE_UNSUBSCRIBE",
  "request": {
    "name": "",
    "identifier": "marketplace request UUID",
    "provider": {
      "dataProductFqn": "",
      "dataProductPortsFqn": [""]
    },
    "consumer": {
      "type": "dataproduct | user | team",
      "identifier": "fqn | email | ?"
    },
    "requester": {
      "type": "user",
      "identifier": "email"
    },
    "properties": {},
    "startDate": "Date",
    "endDate": "Date"
  }
}
```

Bean validation requires `name`, `identifier`, `provider`, `consumer`, `requester`, `startDate` and `endDate`.

## Result sent to the Marketplace Service

The executor posts results to `{marketplaceServiceAddress}/api/v1/pp/marketplace/requests/{identifier}/results`. The `identifier` is the Marketplace Service **internal UUID**: it is also the *correlation value* stored on the ServiceNow ticket.

```json
{
  "status": "PENDING | GRANTED | DENIED | REVOKED | ERROR",
  "message": "",
  "provider": {
    "dataProductFqn": "",
    "dataProductPortsFqn": [""]
  },
  "createdAt": "Date"
}
```

Delivery is retried: see [Result delivery](configuration-reference.md#marketplace-service-and-result-delivery).

## Webhook: `POST /api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events`

Called by ServiceNow, through an authenticating gateway. **The endpoint has no authentication of its own**: see the [network isolation requirement](servicenow-setup.md#5-securing-the-webhook).

This is a fixed contract: your Business Rule or Flow must send exactly this shape. `fields` is a free map, and the outcome rules and message templates read from it. Values that are not strings are converted to text (objects and arrays become JSON, `null` entries are dropped).

```json
{
  "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "fields": {
    "state": "7",
    "close_code": "Solution provided",
    "close_notes": "Access granted through AD group GRP-SALES-READ",
    "number": "INC0010042",
    "sys_id": "9d385017c611228701d22104cc95c371",
    "table": "incident",
    "closed_by": "Mario Rossi"
  }
}
```

| Field | Meaning |
|:--|:--|
| `correlationId` | The marketplace request UUID, read back from the ticket |
| `fields.number`, `fields.sys_id`, `fields.table` | The ticket that was **fulfilled** (used for the final message and link) |
| `fields.state`, `fields.close_code`, `fields.approval`, ... | Whatever your outcome rules need |
| `fields.close_notes` | Free text for the message |
| `fields.closed_by` | Closing user (removed from templates when [closing-user inclusion](message-templates.md#privacy) is disabled). The field name is configurable |

### Answers

| Status | Body | Meaning |
|:--|:--|:--|
| `200` | `{"outcome": "REPORTED", "status": "GRANTED"}` | The final result was posted to the Marketplace Service (`status` is the status reported) |
| `200` | `{"outcome": "IGNORED", "reason": "..."}` | Acknowledged, nothing was posted. See the reasons below |
| `500` | error body | The Marketplace Service (lookup or result delivery) failed. **Nothing was lost: the caller should retry** |
| `400` | error body | Malformed JSON only |

| `IGNORED` reason | Why |
|:--|:--|
| `MISSING_CORRELATION` | The payload has no `correlationId` (the ticket is not a marketplace ticket, or the read-back is wrong) |
| `UNKNOWN_REQUEST` | The Marketplace Service does not know that UUID |
| `NOT_HANDLED_BY_EXECUTOR` | The request does not match this executor's [filter](concepts.md#request-filter) |
| `FINAL_RESULT_ALREADY_REPORTED` | A final result already exists for the request (first final status wins) |
| `NON_TERMINAL_STATE` | No outcome rule matched the fields: the ticket is not in a final state |

The checks run in the order of this table.

## Calls the executor makes

| To | Call | Purpose |
|:--|:--|:--|
| Marketplace Service | `GET {address}/api/v1/pp/marketplace/requests/{uuid}` | Load operation, provider, ports and the rest of the request when a webhook arrives (`404` means `UNKNOWN_REQUEST`) |
| Marketplace Service | `GET {address}/api/v1/pp/marketplace/executor-responses?accessRequestUuid={uuid}&page=0&size=100` | Check whether a final result already exists |
| Marketplace Service | `POST {address}/api/v1/pp/marketplace/requests/{uuid}/results` | Post `PENDING`, the final result or a technical `ERROR` |
| ServiceNow | Depends on the profile type, see [Concepts](concepts.md#mapping-marketplace-request-to-servicenow-record) | Create the ticket |
| ServiceNow | `GET /api/now/table/sys_user?sysparm_query=...&sysparm_fields=sys_id&sysparm_limit=1` | Resolve the requester (Service Catalog only) |
| ServiceNow | `POST {token-url}` (default `{instance-url}/oauth_token.do`) | Obtain an OAuth token (OAuth mode only) |

Calls to the Marketplace Service carry no authentication headers.

## Error body

Errors raised by the executor are returned as JSON with the fields `timestamp`, `status`, `error`, `message` and `path`. Validation errors are `400` with the text `Errors: ...` listing the violated constraints. Unexpected exceptions are `500` with the message `Unknown Internal Server Error`.

---
[Back to README](../README.md)
