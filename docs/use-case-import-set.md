# Use case: Import Set

[Back to README](../README.md) | [All use cases](use-cases.md)

## Overview

Choose this when you want ServiceNow itself to decide what record gets created from the incoming data. The executor writes **one row into a staging table**; a **transform map** you control turns that row into the real record (here an `sc_task`) and copies the marketplace request id into its `correlation_id`. The executor never touches the final record directly.

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
      type: IMPORT_SET
      target: u_marketplace_access
      correlation-field: u_marketplace_request_id      # default, shown for clarity
      fields:                                          # keys = staging table columns
        u_short_description: "Grant access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        u_description: |-
          Marketplace request: {request.identifier}
          Data product: {request.provider.dataProductFqn}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Consumer: {request.consumer.type} {request.consumer.identifier}
          Requested by: {request.requester.identifier}
          Period: {request.startDate} - {request.endDate}
      outcome-rules:
        - when:
            state: "3"
          outcome: FULFILLED
        - when:
            state: "4"
          outcome: REFUSED
    unsubscribe:
      type: IMPORT_SET
      target: u_marketplace_access
      fields:
        u_short_description: "Revoke access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        u_description: |-
          Marketplace request: {request.identifier}
          Data product: {request.provider.dataProductFqn}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Consumer: {request.consumer.type} {request.consumer.identifier}
      outcome-rules:
        - when:
            state: "3"
          outcome: FULFILLED
        - when:
            state: "4"
          outcome: REFUSED
```

The ticket reference defaults are `/result/0/display_value` (number), `/result/0/sys_id` and `/result/0/table`: see the [defaults table](configuration-reference.md#per-interface-defaults).

## ServiceNow objects

1. **Staging table** `u_marketplace_access` (extends *Import Set Row*) with the columns `u_marketplace_request_id` (String, length of at least 40), `u_short_description` and `u_description`.
2. **Transform map** from `u_marketplace_access` to `sc_task` (run synchronously when called through the Import Set API), with the field maps:

   | Source | Target |
   |:--|:--|
   | `u_marketplace_request_id` | `correlation_id` |
   | `u_short_description` | `short_description` |
   | `u_description` | `description` |

3. Integration user, OAuth application and API access policy for `POST /api/now/import/u_marketplace_access`; the integration user must be allowed to write the staging table.

**Error handling.** The Import Set API can answer *success* while no record was created. The executor reads the row status: when it is `error` or `ignored` the ticket is **not** considered opened, and an `ERROR` is reported with the ServiceNow message, for example `Technical error while processing MARKETPLACE_SUBSCRIBE on ServiceNow: Import Set transform did not create the ticket (status error): <error message>`. Check the transform map and the import log in that case.

## Business Rule

On table `sc_task` (the *transformed* record, not the staging row).

| Setting | Value |
|:--|:--|
| When / Update | after / update |
| Condition | `current.state.changes() && (current.state == 3 \|\| current.state == 4) && !current.correlation_id.nil()` |
| Correlation read-back | `current.getValue('correlation_id')` (the transform map copied the column here) |

Webhook payload (the [template script](servicenow-setup.md#6-make-servicenow-call-the-executor-a-business-rule) default `fields`; `close_code` is empty on a task):

```json
{
  "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "fields": {
    "state": "3",
    "close_code": "",
    "close_notes": "Access granted through AD group GRP-SALES-READ",
    "number": "SCTASK0010020",
    "sys_id": "f4e8a1c2db2a0110a5b4b3c9d9961937",
    "table": "sc_task",
    "closed_by": "Mario Rossi"
  }
}
```

## Example run

1. The Marketplace Service posts the [shared example request](use-cases.md#shared-example) and gets `202`.
2. The executor posts a row to `POST /api/now/import/u_marketplace_access`:

```json
{
  "u_short_description": "Grant access to urn:dp:finance:sales for jane.doe@acme.com",
  "u_description": "Marketplace request: 7c9e6679-7425-40de-944b-e07fc1f90ae7\nData product: urn:dp:finance:sales\nPorts: urn:dp:finance:sales:port:orders, urn:dp:finance:sales:port:customers\nConsumer: user jane.doe@acme.com\nRequested by: jane.doe@acme.com\nPeriod: 2026-11-01T00:00:00Z - 2027-11-01T00:00:00Z",
  "u_marketplace_request_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7"
}
```

3. ServiceNow answers `{"import_set": "ISET0010001", "staging_table": "u_marketplace_access", "result": [{"transform_map": "Marketplace access to sc_task", "table": "sc_task", "display_name": "number", "display_value": "SCTASK0010020", "status": "inserted", "sys_id": "f4e8a1c2db2a0110a5b4b3c9d9961937"}]}` and the transform map creates the `sc_task` with `correlation_id` set. The executor posts `PENDING` with the message `ServiceNow ticket SCTASK0010020 opened for MARKETPLACE_SUBSCRIBE of urn:dp:finance:sales (ports: ...). Awaiting fulfilment: https://acme.service-now.com/sc_task.do?sys_id=f4e8a1c2db2a0110a5b4b3c9d9961937`.
4. The task is closed complete: the Business Rule sends the payload above. The executor answers `200 REPORTED GRANTED` and posts:

```json
{
  "status": "GRANTED",
  "message": "GRANTED through ServiceNow ticket SCTASK0010020 (https://acme.service-now.com/sc_task.do?sys_id=f4e8a1c2db2a0110a5b4b3c9d9961937) closed by Mario Rossi. Close notes: Access granted through AD group GRP-SALES-READ",
  "provider": { "dataProductFqn": "urn:dp:finance:sales", "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"] },
  "createdAt": "2026-10-16T15:40:03Z"
}
```

---
[Back to README](../README.md) | [All use cases](use-cases.md) | [Troubleshooting](troubleshooting.md)
