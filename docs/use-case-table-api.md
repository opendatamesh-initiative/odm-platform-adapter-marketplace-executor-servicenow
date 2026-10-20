# Use case: Table API (incident)

[Back to README](../README.md) | [All use cases](use-cases.md)

## Overview

The simplest option. When someone requests access to a data product, the executor creates **a record in a ServiceNow table of your choice**, here an `incident`, and writes the marketplace request id into the standard `correlation_id` field. A fulfiller works the incident as usual. When it is resolved, closed or cancelled, ServiceNow tells the executor, which turns the close code into granted, denied, revoked or failed.

Use `sc_task`, `change_request` or a custom `u_` table the same way: only `target` and the outcome rules change.

## Contents

- [Executor configuration](#executor-configuration)
- [ServiceNow objects](#servicenow-objects)
- [Business Rule](#business-rule)
- [Example run](#example-run)

## Executor configuration

```yaml
servicenow:
  instance-url: ${SERVICENOW_INSTANCE_URL}
  auth:
    type: OAUTH2_CLIENT_CREDENTIALS
    oauth2:
      client-id: ${SERVICENOW_CLIENT_ID}
      client-secret: ${SERVICENOW_CLIENT_SECRET}
  filter:
    provider-data-product-fqns:
      - 'urn:dp:finance:.*'
  profiles:
    subscribe:
      type: TABLE
      target: incident
      fields:
        short_description: "Grant access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        description: |-
          Marketplace request: {request.identifier}
          Data product: {request.provider.dataProductFqn}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Consumer: {request.consumer.type} {request.consumer.identifier}
          Requested by: {request.requester.identifier}
          Start date: {request.startDate}
          End date: {request.endDate}
        category: "inquiry"
      outcome-rules:
        - when:
            state: "6|7"
            close_code: '(?i)(Solution provided|Workaround provided|Resolved by .*)'
          outcome: FULFILLED
        - when:
            state: "6|7"
            close_code: '(?i)No resolution provided'
          outcome: REFUSED
        - when:
            state: "8"
          outcome: REFUSED
        - when:            # fallback: any other close code (Duplicate, Known error, User error, ...)
            state: "6|7"
          outcome: FAILED
    unsubscribe:
      type: TABLE
      target: incident
      fields:
        short_description: "Revoke access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        description: |-
          Marketplace request: {request.identifier}
          Data product: {request.provider.dataProductFqn}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Consumer: {request.consumer.type} {request.consumer.identifier}
          Requested by: {request.requester.identifier}
        category: "inquiry"
      outcome-rules:
        - when:
            state: "6|7"
            close_code: '(?i)(Solution provided|Workaround provided|Resolved by .*)'
          outcome: FULFILLED
        - when:
            state: "6|7"
            close_code: '(?i)No resolution provided'
          outcome: REFUSED
        - when:
            state: "8"
          outcome: REFUSED
        - when:            # fallback: any other close code (Duplicate, Known error, User error, ...)
            state: "6|7"
          outcome: FAILED
```

Put this in `config/application.yml` (see [how configuration is loaded](configuration-reference.md#how-configuration-is-loaded)). It works as-is on a standard instance once the environment variables are set.

Incident states used: `6` Resolved, `7` Closed, `8` Canceled. Close codes that none of the rules match (for example `Closed/Resolved by Caller`, or custom codes) leave the request `PENDING`: add a rule for them. Defaults for this interface: correlation field `correlation_id`, ticket number at `/result/number`, sys_id at `/result/sys_id`, table = `target` (see the [defaults table](configuration-reference.md#per-interface-defaults)).

## ServiceNow objects

Integration user, OAuth application and API access policies for `POST /api/now/table/incident` ([common setup](servicenow-setup.md)). The integration user needs create rights on `incident`. Nothing else.

## Business Rule

On table `incident`, using the [template script](servicenow-setup.md#6-make-servicenow-call-the-executor-a-business-rule) unchanged.

| Setting | Value |
|:--|:--|
| When / Update | after / update |
| Condition | `current.state.changes() && (current.state == 6 \|\| current.state == 7 \|\| current.state == 8) && !current.correlation_id.nil()` |
| Correlation read-back | `current.getValue('correlation_id')` (already the default in the template script) |

Webhook payload sent when the incident is closed:

```json
{
  "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "fields": {
    "state": "7",
    "close_code": "Solution provided",
    "close_notes": "Access granted to both ports through AD group GRP-SALES-READ",
    "number": "INC0010042",
    "sys_id": "9d385017c611228701d22104cc95c371",
    "table": "incident",
    "closed_by": "Mario Rossi"
  }
}
```

## Example run

1. The Marketplace Service posts the [shared example request](use-cases.md#shared-example) to `POST /api/v1/up/marketplace-executor/requests` and gets `202`.
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

4. A fulfiller resolves the incident (state 6, close code `Solution provided`). The Business Rule sends the webhook payload above through the gateway. The executor answers `200 {"outcome": "REPORTED", "status": "GRANTED"}` and posts:

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

---
[Back to README](../README.md) | [All use cases](use-cases.md) | [Troubleshooting](troubleshooting.md)
