# Use case: Service Catalog

[Back to README](../README.md) | [All use cases](use-cases.md)

## Overview (in plain words)

Choose this when access requests already go through the ServiceNow **Service Catalog**, with approvals and fulfilment workflows. The executor **orders a catalog item on behalf of the person who made the marketplace request**, so the order shows up as that person's own request. Your catalog workflow does the rest. When the requested item is closed (or the approval is rejected), the executor reports the outcome back to the marketplace.

An order creates a request (`REQ`) with a requested item (`RITM`) and tasks under your workflow. The `PENDING` message refers to the **request** (`REQ...`); the final message refers to the **RITM**, which is the record that is closed and calls the webhook.

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
      type: SERVICE_CATALOG
      target: ${SERVICENOW_SUBSCRIBE_ITEM_SYS_ID}      # sys_id of the "Data product access" catalog item
      correlation-field: marketplace_request_id        # default, shown for clarity
      requester-match-field: email                     # default, sys_user field compared with the requester
      submit-cart-when-two-step: true                  # default
      fields:                                          # keys = catalog variable names
        data_product: "{request.provider.dataProductFqn}"
        ports: "{#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}"
        consumer: "{request.consumer.type}: {request.consumer.identifier}"
        period: "{request.startDate} - {request.endDate}"
      outcome-rules:
        - when:
            approval: "rejected"
          outcome: REFUSED
        - when:
            state: "3"
          outcome: FULFILLED
        - when:
            state: "4"
          outcome: REFUSED
    unsubscribe:
      type: SERVICE_CATALOG
      target: ${SERVICENOW_UNSUBSCRIBE_ITEM_SYS_ID}    # sys_id of the "Data product access removal" item
      fields:
        data_product: "{request.provider.dataProductFqn}"
        ports: "{#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}"
        consumer: "{request.consumer.type}: {request.consumer.identifier}"
      outcome-rules:
        - when:
            approval: "rejected"
          outcome: REFUSED
        - when:
            state: "3"
          outcome: FULFILLED
        - when:
            state: "4"
          outcome: REFUSED
```

Notes:

- **Two-step checkout.** If the instance uses two-step checkout, `order_now` answers with a `cart_id` and no request number. With `submit-cart-when-two-step: true` (default) the executor then calls `POST /api/sn_sc/servicecatalog/cart/submit_order` and takes the ticket reference from that answer. With `false`, a cart answer is reported as `ERROR`.
- In the outcome rules, the RITM `state` `3` is *Closed Complete* and `4` *Closed Incomplete*; add `7` (*Closed Skipped*) if you use it.
- Requester resolution (`requester-match-field`, user type, `^` restriction) is described in the [configuration reference](configuration-reference.md#service-catalog-requester-resolution).

## ServiceNow objects

1. A **catalog item** per operation (for example *Data product access* and *Data product access removal*), orderable by the integration user, with these **variables**:
   - `marketplace_request_id` (Single Line Text; **required name**, unless you change `correlation-field`): this is where the correlation UUID goes. Hide it from the end user.
   - `data_product`, `ports`, `consumer`, `period` (names must equal the keys of `fields`).
2. The catalog workflow/flow with your approval and fulfilment tasks. Put the catalog item sys_ids in `SERVICENOW_SUBSCRIBE_ITEM_SYS_ID` / `SERVICENOW_UNSUBSCRIBE_ITEM_SYS_ID` (these are variables you define in your own configuration file, the executor does not read them by name).
3. Integration user, OAuth application and API access policies for the **Service Catalog API** and for the **Table API read on `sys_user`** (requester resolution). The requester's email must exist in `sys_user`: an unknown requester makes the executor report an `ERROR` ("Requester ... not found in ServiceNow sys_user") without ordering anything.
4. The integration user must be allowed to order for others, and every mandatory variable of the item must be provided: see [Service Catalog ordering on behalf of others](servicenow-setup.md#service-catalog-ordering-on-behalf-of-others).

## Business Rule

On table `sc_req_item`.

| Setting | Value |
|:--|:--|
| When / Update | after / update |
| Condition | `(current.state.changes() && (current.state == 3 \|\| current.state == 4)) \|\| (current.approval.changes() && current.approval == 'rejected')` |
| Correlation read-back | `current.variables.marketplace_request_id` |

In the [template script](servicenow-setup.md#6-make-servicenow-call-the-executor-a-business-rule) replace the first lines and the `fields`:

```javascript
  var correlationId = current.variables.marketplace_request_id + '';
  if (!correlationId || correlationId === 'undefined') {
    return;
  }
  // ...
  fields: {
    state:       current.getValue('state') || '',
    approval:    current.getValue('approval') || '',
    close_notes: current.getValue('close_notes') || '',
    number:      current.getValue('number') || '',
    sys_id:      current.getUniqueValue(),
    table:       current.getTableName(),          // sc_req_item
    closed_by:   current.closed_by.getDisplayValue()
  }
```

Webhook payload (`approval` is an extra field read by the first outcome rule):

```json
{
  "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "fields": {
    "state": "3",
    "approval": "approved",
    "close_notes": "Access granted through AD group GRP-SALES-READ",
    "number": "RITM0010011",
    "sys_id": "c3a1f2e4db2a0110a5b4b3c9d9961937",
    "table": "sc_req_item",
    "closed_by": "Mario Rossi"
  }
}
```

State `3` gives `FULFILLED`; state `4` gives `REFUSED`; `approval: rejected` gives `REFUSED`. These map to `GRANTED` / `DENIED` for a subscribe and `REVOKED` / `ERROR` for an unsubscribe. A rejection usually also closes the RITM as incomplete: the first event wins and the second is ignored (`FINAL_RESULT_ALREADY_REPORTED`).

## Example run

1. The Marketplace Service posts the [shared example request](use-cases.md#shared-example) and gets `202`.
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

5. The request is approved and the RITM is closed complete: the Business Rule sends the webhook payload above. The executor answers `200 REPORTED GRANTED` and posts:

```json
{
  "status": "GRANTED",
  "message": "GRANTED through ServiceNow ticket RITM0010011 (https://acme.service-now.com/sc_req_item.do?sys_id=c3a1f2e4db2a0110a5b4b3c9d9961937) closed by Mario Rossi. Close notes: Access granted through AD group GRP-SALES-READ",
  "provider": { "dataProductFqn": "urn:dp:finance:sales", "dataProductPortsFqn": ["urn:dp:finance:sales:port:orders", "urn:dp:finance:sales:port:customers"] },
  "createdAt": "2026-10-16T15:40:03Z"
}
```

If the approver rejects the request (`approval: rejected`), the result is `DENIED` with `Request refused on ServiceNow: ticket RITM0010011 (...) closed by .... Reason: <close notes>`.

---
[Back to README](../README.md) | [All use cases](use-cases.md) | [Troubleshooting](troubleshooting.md)
