# Use case: Scripted REST

[Back to README](../README.md) | [All use cases](use-cases.md)

## Overview

Choose this when the way a ticket is created (assignment group, templates, defaults, extra checks) should live **inside ServiceNow**, under your control. You write a small Scripted REST API; the executor sends it the rendered fields plus the marketplace request id, and your script creates whatever record it wants and tells the executor which ticket it made.

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
      type: SCRIPTED_REST
      path: /api/x_acme_mkt/v1/access_tickets          # required
      method: POST                                     # default (POST or PATCH)
      correlation-field: correlation_id                # default, shown for clarity
      fields:
        operation: "{operation}"
        short_description: "Grant access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        description: |-
          Marketplace request: {request.identifier}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Requested by: {request.requester.identifier}
          Period: {request.startDate} - {request.endDate}
      response:
        number-pointer: /result/number                 # required
        sys-id-pointer: /result/sys_id                 # required
        table: sc_task                                 # or table-pointer: /result/table
      outcome-rules:
        - when:
            state: "3"
          outcome: FULFILLED
        - when:
            state: "4"
          outcome: REFUSED
        - when:
            state: "7"
          outcome: FAILED
    unsubscribe:
      type: SCRIPTED_REST
      path: /api/x_acme_mkt/v1/access_tickets
      fields:
        operation: "{operation}"
        short_description: "Revoke access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        description: |-
          Marketplace request: {request.identifier}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Requested by: {request.requester.identifier}
      response:
        number-pointer: /result/number
        sys-id-pointer: /result/sys_id
        table: sc_task
      outcome-rules:
        - when:
            state: "3"
          outcome: FULFILLED
        - when:
            state: "4"
          outcome: REFUSED
        - when:
            state: "7"
          outcome: FAILED
```

The executor sends the JSON `{fields..., "correlation_id": "<uuid>"}` to the path with the configured method (`POST` or `PATCH`). If either pointer finds nothing in the answer, the ticket creation fails and an `ERROR` is reported (`ServiceNow response did not contain the ticket number at /result/number`): the `PENDING` is never sent without a ticket reference. A non-2xx answer from your script is reported as `ERROR` with the HTTP status and the (truncated to 500 characters) body. Without a table (neither `table` nor a `table-pointer` result) the ticket link in messages is empty.

## ServiceNow objects

A **Scripted REST API** (for example namespace `x_acme_mkt`, API ID `v1`) with a `POST` resource `/access_tickets`, **requires authentication**. Example scripted resource script:

```javascript
(function process(/*RESTAPIRequest*/ request, /*RESTAPIResponse*/ response) {

  var body = request.body.data;            // the JSON sent by the executor

  var gr = new GlideRecord('sc_task');
  gr.initialize();
  gr.short_description = body.short_description;
  gr.description = body.description;
  gr.correlation_id = body.correlation_id;  // the marketplace request UUID: always present
  gr.assignment_group.setDisplayValue(
    body.operation == 'MARKETPLACE_UNSUBSCRIBE' ? 'Access Revocation' : 'Data Governance');
  var sysId = gr.insert();

  if (!sysId) {
    response.setStatus(500);
    response.setBody({ error: { message: 'Unable to create the access task' } });
    return;
  }

  response.setStatus(201);
  // the executor reads these through number-pointer, sys-id-pointer and table
  response.setBody({
    result: {
      number: gr.getValue('number'),
      sys_id: sysId,
      table: 'sc_task'
    }
  });

})(request, response);
```

The pointers must match what your script really returns. Call the endpoint once with the REST API Explorer and look at the real answer before configuring `number-pointer` and `sys-id-pointer`: ServiceNow can wrap values returned by a script in a `result` object. Also create an **API access policy** for this Scripted REST API and let the integration user execute it.

## Business Rule

On the record the script creates (`sc_task` here).

| Setting | Value |
|:--|:--|
| When / Update | after / update |
| Condition | `current.state.changes() && (current.state == 3 \|\| current.state == 4 \|\| current.state == 7) && !current.correlation_id.nil()` |
| Correlation read-back | `current.getValue('correlation_id')` (the field your script wrote) |

Webhook payload:

```json
{
  "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "fields": {
    "state": "3",
    "close_code": "",
    "close_notes": "Access granted through AD group GRP-SALES-READ",
    "number": "SCTASK0010031",
    "sys_id": "0d9be3a5db2a0110a5b4b3c9d9961937",
    "table": "sc_task",
    "closed_by": "Mario Rossi"
  }
}
```

State `3` gives `FULFILLED`, `4` gives `REFUSED`, `7` gives `FAILED` (an `ERROR` for both operations, with the `failed` message).

## Example run

1. The Marketplace Service posts the [shared example request](use-cases.md#shared-example) and gets `202`.
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
4. The task is closed complete: the Business Rule sends the payload above. The executor answers `200 REPORTED GRANTED` and posts:

```json
{
  "status": "GRANTED",
  "message": "GRANTED through ServiceNow ticket SCTASK0010031 (https://acme.service-now.com/sc_task.do?sys_id=0d9be3a5db2a0110a5b4b3c9d9961937) closed by Mario Rossi. Close notes: Access granted through AD group GRP-SALES-READ",
  "provider": { "dataProductFqn": "urn:dp:finance:sales", "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"] },
  "createdAt": "2026-10-16T15:40:03Z"
}
```

---
[Back to README](../README.md) | [All use cases](use-cases.md) | [Troubleshooting](troubleshooting.md)
