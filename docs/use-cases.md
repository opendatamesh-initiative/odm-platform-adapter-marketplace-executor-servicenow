# Use cases

[Back to README](../README.md)

## Overview (in plain words)

The executor always does the same thing: a marketplace request becomes a ServiceNow ticket, and the ticket's outcome goes back to the marketplace. What changes from company to company is **which kind of ServiceNow record is the "ticket"**. The executor supports four ways to create it. Pick the one that matches how your organisation already handles access requests, and use the matching guide to configure both the executor and ServiceNow.

## Which one should I use?

| If your organisation... | Use | Ticket is | Guide |
|:--|:--|:--|:--|
| just needs a ticket for someone to action (incident, task, change, a custom table) | **Table API** | a record in any table, for example `incident` | [Table API](use-case-table-api.md) |
| runs access requests through the Service Catalog with approvals and workflows, and wants the request to appear as the requester's own order | **Service Catalog** | a request (`REQ`) with a requested item (`RITM`), ordered on behalf of the requester | [Service Catalog](use-case-service-catalog.md) |
| wants ServiceNow to transform inbound data into the right records with transform maps | **Import Set** | a row in a staging table, transformed into a record such as `sc_task` | [Import Set](use-case-import-set.md) |
| wants the ticket creation logic (assignment, templates, defaults) to live in ServiceNow | **Scripted REST** | whatever your own Scripted REST API creates | [Scripted REST](use-case-scripted-rest.md) |

A deployment can mix interfaces: the subscribe and unsubscribe operations have separate profiles, so each can use a different type.

## What every use case has in common

1. **Executor configuration**: connection and auth (shared, see [Configuration reference](configuration-reference.md#connection-and-authentication)), a request filter, and one profile per operation with `type`, `target`, `fields` and `outcome-rules`.
2. **ServiceNow objects**: integration user, authentication and API access policies ([ServiceNow setup](servicenow-setup.md)), plus the objects specific to the interface.
3. **A Business Rule (or Flow)** that calls the webhook when the ticket is closed ([template](servicenow-setup.md#6-make-servicenow-call-the-executor-a-business-rule)).
4. **Outcome rules** that translate the ticket state into `FULFILLED`, `REFUSED` or `FAILED`.

The use case pages use the packaged default messages (see [Message templates](message-templates.md#message-templates)), so no `messages` block is needed. You can add one to any of them.

## Shared example

All the guides use:

- instance `https://acme.service-now.com`, OAuth client credentials from the environment, and a filter that handles the `urn:dp:finance:*` data products;
- the Business Rule template from [ServiceNow setup](servicenow-setup.md#6-make-servicenow-call-the-executor-a-business-rule) with the properties `x_acme.marketplace.gateway_*`;
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
[Back to README](../README.md) | [Concepts](concepts.md) | [ServiceNow setup](servicenow-setup.md)
