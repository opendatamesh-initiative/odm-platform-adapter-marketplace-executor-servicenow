# Message templates and privacy

[Back to README](../../README.md)

## Overview

Two things in the executor are text you can customise: **what the ticket says** (its short description, description, catalog variables) and **what the marketplace shows to people** when the request progresses ("ticket INC0010042 opened, awaiting fulfilment", "access granted, closed by Mario Rossi"). Both are written as small templates with placeholders, using the Quarkus template engine **Qute**. This page explains the syntax, the data you can use, the default messages, and how to keep personal data out of the messages.

## Contents

- [Qute primer](#qute-primer)
- [Data model](#data-model)
- [Message templates](#message-templates)
- [Privacy](#privacy)

## Qute primer

Ticket fields and messages are written in [Qute](https://quarkus.io/guides/qute-reference).

| You want | Write |
|:--|:--|
| A value | `{request.provider.dataProductFqn}` (single braces; **not** Mustache's `{{...}}`) |
| A condition | `{#if ticket.closedBy} closed by {ticket.closedBy}{/if}` |
| If / else | `{#if operation == 'MARKETPLACE_SUBSCRIBE'}Grant{#else}Revoke{/if}` |
| A loop | `{#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}` |
| A literal brace | `{|{"a": 1}|}` (unparsed section) or `\{` |

- **Quote templates in YAML** whenever they contain `{`, `:` or `#`. Use double quotes for templates and single quotes for regexes. Multi-line texts use a block scalar (`|-`).
- **Never write `${...}` in a template**: Quarkus expands it as a configuration expression before the template is parsed.
- **No escaping**: rendered values are plain strings, serialised to JSON by the executor (no HTML escaping, no JSON-injection risk).
- **Missing values render empty**: `{request.properties.costCenter}` on a request without that property renders nothing and never fails. `{#if}` is false for empty and missing values.
- **External templates**: a value starting with `file:` is loaded at startup from that path, for example `description: "file:/etc/marketplace/description.qute"`. A file that does not exist or does not parse stops the executor at startup.
- Dates are ISO-8601 strings (empty when not set).
- `quarkus.qute.strict-rendering: false` and `quarkus.qute.property-not-found-strategy: noop` are preconfigured in `application.yml` so that missing data renders empty. Do not change them.

## Data model

| Available in | Expression | Content |
|:--|:--|:--|
| ticket fields and messages | `request.identifier`, `request.name`, `request.operation` | UUID, name, operation |
| | `request.startDate`, `request.endDate` | ISO-8601, or empty |
| | `request.properties.<key>` | request properties |
| | `request.provider.dataProductFqn`, `request.provider.dataProductPortsFqn` | provider and the list of **all** ports |
| | `request.consumer.type`, `request.consumer.identifier` | consumer |
| | `request.requester.type`, `request.requester.identifier` | requester |
| | `operation` | `MARKETPLACE_SUBSCRIBE` or `MARKETPLACE_UNSUBSCRIBE` |
| messages only | `ticket.number`, `ticket.sysId`, `ticket.table`, `ticket.link` | the ticket (`PENDING`: the created ticket; final: from the webhook `number`, `sys_id`, `table`) |
| | `ticket.closedBy` | the closing user. **Present only when closing-user inclusion is enabled** |
| | `fields.<name>` | the webhook `fields` (final results). With inclusion disabled, the closing-user field is removed from here too |
| | `outcome.cause`, `outcome.status` | `FULFILLED`/`REFUSED`/`FAILED` and the final status |
| | `error.message` | technical error text (`technical-error` only) |

## Message templates

All messages are Qute templates, trimmed and truncated to 4000 characters. The template used is the first available of:

1. the outcome **rule's** `message` (final results only),
2. the **profile's** `messages.<kind>`,
3. the **global** `servicenow.messages.<kind>`.

The global defaults (built in, and repeated in the packaged `application.yml`):

```yaml
servicenow:
  messages:
    pending: "ServiceNow ticket {ticket.number} opened for {operation} of {request.provider.dataProductFqn} (ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}). Awaiting fulfilment: {ticket.link}"
    fulfilled: "{outcome.status} through ServiceNow ticket {ticket.number} ({ticket.link}){#if ticket.closedBy} closed by {ticket.closedBy}{/if}.{#if fields.close_notes} Close notes: {fields.close_notes}{/if}"
    refused: "Request refused on ServiceNow: ticket {ticket.number} ({ticket.link}){#if ticket.closedBy} closed by {ticket.closedBy}{/if}.{#if fields.close_notes} Reason: {fields.close_notes}{/if}"
    failed: "ServiceNow reported a failure on ticket {fields.number} ({ticket.link}).{#if fields.close_notes} Details: {fields.close_notes}{/if}"
    technical-error: "Technical error while processing {operation} on ServiceNow: {error.message}"
    closing-user:
      include: ${SERVICENOW_MESSAGES_INCLUDE_CLOSING_USER:true}
      field: closed_by
```

| Message | Used for |
|:--|:--|
| `pending` | `PENDING` after the ticket is opened |
| `fulfilled` | Cause `FULFILLED` (`GRANTED` / `REVOKED`) |
| `refused` | Cause `REFUSED` (`DENIED` for subscribe, `ERROR` for unsubscribe) |
| `failed` | Cause `FAILED` (`ERROR`) |
| `technical-error` | `ERROR` when the ticket cannot be opened (ServiceNow unreachable, 401/403, requester not found, Import Set transform error, missing response pointer, ...) |

Example of a per-rule message:

```yaml
outcome-rules:
  - when:
      state: "8"
    outcome: REFUSED
    message: "Request cancelled on ServiceNow ({ticket.number}). {fields.close_notes}"
```

All templates are parsed at startup: a syntax error stops the executor and is listed in the startup error.

## Privacy

Result messages go to the Marketplace Service history and, are shown to marketplace users. By default a message contains the **name of the ServiceNow user who closed the ticket** and the **close notes**.

- To keep the closing user out of messages set `servicenow.messages.closing-user.include=false` (env `SERVICENOW_MESSAGES_INCLUDE_CLOSING_USER=false`). Then `ticket.closedBy` does not exist and the closing-user field (`servicenow.messages.closing-user.field`, default `closed_by`) is removed from `fields.*`, so no template can leak it, even by mistake.
- The close notes are free text written by the fulfiller. Remove `{fields.close_notes}` from the templates if they might hold personal or sensitive data, and/or do not send them in the webhook.
- You can also simply not send `closed_by` in the Business Rule payload.
- The executor never logs request headers, tokens, secrets or request bodies.

---
[Back to README](../../README.md) | [Configuration reference](configuration-reference.md)
