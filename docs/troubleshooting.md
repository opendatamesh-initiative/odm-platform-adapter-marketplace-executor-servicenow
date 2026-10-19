# Troubleshooting and operations

[Back to README](../README.md)

## Overview (in plain words)

When something does not work, it is almost always one of three things: the configuration is invalid (the executor will not start), the ticket was not created (the request shows `ERROR`), or the ticket was closed but the marketplace still says `PENDING` (the call back from ServiceNow did not arrive or was ignored). This page helps you find which, and lists what the logs mean.

## Contents

- [The executor does not start](#the-executor-does-not-start)
- [The request shows ERROR immediately](#the-request-shows-error-immediately)
- [The request stays PENDING](#the-request-stays-pending)
- [Logs](#logs)
- [Operational notes](#operational-notes)

## The executor does not start

The executor validates its configuration at startup and prints `Invalid ServiceNow executor configuration:` followed by a numbered list. Typical items:

| Message contains | Fix |
|:--|:--|
| `required property ... not found` (`servicenow.profiles...`) | Provide `servicenow.profiles.subscribe` and `.unsubscribe` in an external file: see [how configuration is loaded](configuration-reference.md#how-configuration-is-loaded) |
| `servicenow.instance-url is required` / `must be a valid https URL` | Set `SERVICENOW_INSTANCE_URL` to an `https` URL |
| `client-id is required` / `client-secret is required` / `api-key.value is required` | Set the credentials that match `servicenow.auth.type` |
| `target is required for ...` / `path is required for SCRIPTED_REST` / `response.number-pointer ...` | Complete the profile |
| `field '...' overrides the correlation field` | Remove that entry from `fields`: the executor adds the correlation value itself |
| `at least one outcome rule is required` / `must have outcome FULFILLED` / `must declare a non-empty 'when'` | Fix the `outcome-rules` |
| `Invalid outcome rule regex` / `Invalid filter regex` | Fix the regular expression |
| `Invalid template '...'` | Fix the Qute syntax: see [message templates](message-templates.md#qute-primer). A `file:` template must exist and parse |

## The request shows ERROR immediately

The message is the `technical-error` template with the cause. Common causes:

| Cause in the message | Check |
|:--|:--|
| `ServiceNow rejected the integration credentials (HTTP 401/403 ...)` | OAuth application or API key, integration user roles/ACLs, API access policy for that API ([setup](servicenow-setup.md)) |
| `Unable to obtain a ServiceNow OAuth token` | Client id/secret, and that inbound client credentials are enabled on the instance |
| `Requester ... not found in ServiceNow sys_user` | The requester must exist with the matching `requester-match-field` (default `email`) |
| `Catalog orders require a user requester` | Service Catalog needs a requester of type `user` |
| `Mandatory Variables are required` (HTTP 400) | Provide every mandatory catalog variable in `fields` |
| `Import Set transform did not create the ticket (status error/ignored)` | Check the transform map and the import log |
| `ServiceNow response did not contain the ticket number at <pointer>` | Fix `response.number-pointer` / `sys-id-pointer` to match the real answer |
| `Catalog order returned a cart (two-step checkout)` | Enable `submit-cart-when-two-step` |
| `ServiceNow call ... failed with HTTP <code>` | The truncated ServiceNow body follows in the message |

## The request stays PENDING

The final status only ever comes from the webhook. Check in this order:

1. **Did the Business Rule run?** Check its condition and the ServiceNow system log. Are the `gateway_url`, `gateway_user` and `gateway_api_key` properties set?
2. **Did the gateway accept the call?** `401` means wrong or missing credentials; `403` means the caller is authenticated but not authorized for the webhook path. Check your gateway configuration and run the [smoke test](servicenow-setup.md#5-securing-the-webhook).3. **What did the executor answer?** Look for `ignored: <REASON>` in the executor log:
   - `MISSING_CORRELATION`: the read-back returned nothing (wrong field or variable name in the Business Rule);
   - `UNKNOWN_REQUEST`: the correlation value is not a Marketplace Service UUID;
   - `NOT_HANDLED_BY_EXECUTOR`: the filter does not accept the request any more;
   - `FINAL_RESULT_ALREADY_REPORTED`: a final result already exists;
   - `NON_TERMINAL_STATE`: no outcome rule matched the fields. Compare the real `state` / `close_code` with your rules (regexes are full matches and case-sensitive unless `(?i)`). Add a fallback `FAILED` rule so a closed ticket always yields a final result.
4. A `500` from the webhook means the Marketplace Service was unreachable or rejected the result: fix it and re-trigger the Business Rule.

## Other symptoms

| Symptom | Explanation |
|:--|:--|
| Marketplace submission fails with "No executor was able to handle the request" | No executor's filter accepted it. Check `servicenow.filter.*` (ports: every port must match) |
| `409` in the executor log / Marketplace Service log | Expected when the filter declines a request ([exclusive filters](concepts.md#request-filter)) |
| Catalog order shows the integration user as *Requested for* | `glide.sc.req_for.roles` is not set for the integration user: [setup](servicenow-setup.md#service-catalog-ordering-on-behalf-of-others) |
| `Result delivery attempt n/m failed` warnings | The Marketplace Service is not ready (404 is retried too) or unreachable: see [result delivery](configuration-reference.md#marketplace-service-and-result-delivery) |

## Logs

Look for the request UUID and the ticket number, they appear in every business log line:

| Log line | Meaning |
|:--|:--|
| `Opened ServiceNow ticket <number> (<table>) for request <uuid> operation <op>` | Leg 1 succeeded |
| `PENDING result for request <uuid> not delivered; ticket <number> is open` | The (best effort) `PENDING` post failed; the ticket exists and the webhook will still work |
| `Unable to open ServiceNow ticket for request <uuid>: ...` | Ticket creation failed; an `ERROR` result is sent |
| `Ticket event for request <uuid> ignored: <REASON>` | Webhook acknowledged without a result |
| `Ticket event for request <uuid> reported as <STATUS>` | Final result posted |
| error with the request UUID | A result could not be delivered |

Request headers, tokens, secrets and request bodies are never logged. The executor reads a `CORRELATION_ID` request header, when present, into the log context key `correlation_id`.

## Operational notes

- **Unsubscribe while the subscribe ticket is still open.** Two tickets exist at the same time. The executor does not resolve this: handle it in your ServiceNow process.
- **Secret rotation.** See [ServiceNow setup](servicenow-setup.md#rotating-secrets).
- **Coexisting executors.** Give each one an exclusive filter ([Concepts](concepts.md#request-filter)).
- **Privacy of messages.** See [Message templates](message-templates.md#privacy).

---
[Back to README](../README.md) | [Configuration reference](configuration-reference.md)
