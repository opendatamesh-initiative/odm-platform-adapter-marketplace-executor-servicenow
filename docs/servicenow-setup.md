# ServiceNow setup (for ServiceNow administrators)

[Back to README](../README.md)

## Overview (in plain words)

For the executor to work, ServiceNow must be prepared in two directions:

1. **ServiceNow lets the executor in.** A dedicated service account, a way to log in (OAuth or API key) and permission to create the tickets.
2. **ServiceNow calls the executor back.** When a ticket is completed, a Business Rule (or Flow) sends a small JSON message so the marketplace learns the outcome. That message goes through an **authenticating gateway**, which checks who is calling.

This page covers the steps shared by every ticket type. The objects that depend on the ticket type (catalog items, staging tables, scripted APIs, Business Rule conditions) are in the [use case guides](use-cases.md).

**Prerequisites:** ServiceNow **Washington DC or later** (OAuth 2.0 client credentials for inbound calls), and an **authenticating gateway** to authenticate the webhook (see [Securing the webhook](#5-securing-the-webhook)).

## Contents

1. [Integration user](#1-integration-user)
2. [Authentication: OAuth 2.0 client credentials (recommended)](#2-authentication-oauth-20-client-credentials-recommended)
3. [Authentication: API key (alternative)](#3-authentication-api-key-alternative)
4. [API access policies](#4-api-access-policies)
5. [Securing the webhook](#5-securing-the-webhook)
6. [Make ServiceNow call the executor: a Business Rule](#6-make-servicenow-call-the-executor-a-business-rule)
7. [Service Catalog ordering on behalf of others](#service-catalog-ordering-on-behalf-of-others)

## 1. Integration user

Create a dedicated, non-interactive service user (for example `svc.odm.marketplace`, with **Web service access only** checked). Give it the **minimum** needed, through a dedicated role and ACLs:

- **create** on each target table of your profiles ([Table API](use-case-table-api.md)), or write access to the staging table ([Import Set](use-case-import-set.md)), or the right to run your Scripted REST resource ([Scripted REST](use-case-scripted-rest.md));
- the right to **order the catalog items** (availability, user criteria) and to order **on behalf of other users**, if you use the [Service Catalog](use-case-service-catalog.md);
- **read** on `sys_user` (at least `sys_id` and the `requester-match-field`, by default `email`), for requester resolution.

The one credential below is used for *every* interface your profiles use, so grant only what the configured profiles need. A missing permission only shows up at runtime as an `ERROR` result with a 401/403 message.

## 2. Authentication: OAuth 2.0 client credentials (recommended)

This is the default (`servicenow.auth.type: OAUTH2_CLIENT_CREDENTIALS`).

1. **Enable inbound client credentials** on the instance: it is off by default and is controlled by a system property. Use the property described in the ServiceNow *Client Credentials* documentation for your release.
2. In **System OAuth > Application Registry**, choose **New** and **Create an OAuth API endpoint for external clients**. Give it a name.
3. Set the **OAuth Application User** to the integration user of step 1. Tokens issued to this application act as that user.
4. Save, then copy the generated **Client ID** and **Client Secret** into your secret store and expose them to the executor as `SERVICENOW_CLIENT_ID` and `SERVICENOW_CLIENT_SECRET`.
5. Verify:

```bash
curl -s -X POST "$SERVICENOW_INSTANCE_URL/oauth_token.do" \
  -d grant_type=client_credentials \
  -d client_id="$SERVICENOW_CLIENT_ID" \
  -d client_secret="$SERVICENOW_CLIENT_SECRET"
# -> {"access_token":"...","token_type":"Bearer","expires_in":1799,...}
```

The executor caches the token until it expires and sends it as `Authorization: Bearer`.

## 3. Authentication: API key (alternative)

For instances where client credentials cannot be enabled. Set `servicenow.auth.type: API_KEY` (`SERVICENOW_AUTH_TYPE=API_KEY`).

1. Activate the **API Key and HMAC Authentication** plugin.
2. Create an **inbound authentication profile** of type API key, using the header `x-sn-apikey` (the executor's default `servicenow.auth.api-key.header-name`).
3. Create a **REST API key** bound to the integration user, and expose it as `SERVICENOW_API_KEY`.

## 4. API access policies

If your instance enforces **REST API access policies**, create one for **each API** your profiles use: Table API, Import Set API, Service Catalog API and/or your Scripted REST API (and the Table API again for the `sys_user` read when you use the Catalog). Reference the inbound authentication profile that matches the mechanism you chose.

## 5. Securing the webhook

The webhook that ServiceNow calls (`POST /api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events`) has **no authentication of its own**: it trusts whoever can reach it, and a caller could forge final results. You must therefore:

- put an **authenticating gateway or reverse proxy** between ServiceNow and the executor, which authenticates ServiceNow and forwards **only** the webhook path (and only `POST`) to the executor;
- make the executor reachable **only by the Marketplace Service and by that gateway**, never directly from ServiceNow or the internet;
- expose the gateway to ServiceNow over TLS. ServiceNow is SaaS and your gateway is often inside your network: allow-list the ServiceNow egress IPs, or use a ServiceNow MID Server.

> **Network isolation is a hard requirement.** The executor never logs or echoes request headers; keep it that way in any gateway or access log you add in front of it, because the credentials ServiceNow sends are usually forwarded along with the request. Better still, have the gateway strip them before forwarding.

Choose one of the options below.

### Option A: your own authenticating gateway

Use any gateway you already operate (API gateway, ingress with authentication, reverse proxy with mTLS, API key or OAuth validation). It must:

1. authenticate the ServiceNow caller (API key, OAuth bearer token, mTLS, ...) and reject everything else with `401`/`403`;
2. authorize that identity for the webhook path only: allow `POST /api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events` and deny every other method and path of the executor, notably `POST .../requests`, which would let a caller inject access requests bypassing the Marketplace Service;
3. forward the request to the executor's internal base URL.

Use the gateway URL and the credentials it expects in the Business Rule properties of [section 6](#6-make-servicenow-call-the-executor-a-business-rule). Verify with a smoke test (harmless: an unknown UUID is acknowledged and ignored):

```bash
curl -i -X POST "https://<gateway-host>/<gateway-path>/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events" \
  -H "Content-Type: application/json" \
  -H "<gateway credential headers>" \
  -d '{"correlationId":"00000000-0000-0000-0000-000000000000","fields":{"state":"7"}}'
# -> 200 {"outcome":"IGNORED","reason":"UNKNOWN_REQUEST"}
```

A `401` or `403` comes from the gateway; a `200 IGNORED` proves the whole chain works.

### Option B: blindata-agent

blindata-agent is a ready-made option: its ODM proxy authenticates and authorizes the webhook, not the executor.

**a. Register the executor in the ODM proxy configuration.** Add the executor's base URL to `servicesURLs`, with the key `marketplace-executor`:

```json
{
  "servicesURLs": {
    "marketplace-executor": "http://marketplace-executor.internal:9019"
  }
}
```

The key `marketplace-executor` is a contract: it is the `servicesURLs` key, the `{service}` segment of the proxy URL, and the name of the agent authorization filter. The agent forwards `/agent-api/v1/odm/proxy/{configUuid}/{service}/**` to that base URL, keeping the rest of the path and the query string.

**b. The URL ServiceNow calls:**

```
https://<agent-host>/agent-api/v1/odm/proxy/<configUuid>/marketplace-executor/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events
```

`<configUuid>` is the identifier of the ODM proxy configuration in Blindata.

**c. A dedicated Blindata application user.** Create an application user (API key) for ServiceNow with the **`MARKETPLACE_EDITOR`** permission and nothing else. ServiceNow sends its credentials in the `X-BD-User` and `X-BD-ApiKey` headers.

**d. Authorization.** The agent's `marketplace-executor` authorization filter allows a caller with `MARKETPLACE_EDITOR` to **`POST` only inside the executors' webhooks namespace `.../api/v1/up/marketplace-executor/webhooks/**`**, which holds this executor's `.../webhooks/servicenow/ticket-events`. Every other method and path of the executor, notably `POST .../requests` (which would let a caller inject access requests bypassing the Marketplace Service), is answered with `403`. **Never use `DATAOPS_ADMIN` for the ServiceNow identity**: it bypasses the filter and can reach every executor path. The filter is delivered with the agent and must be released together with this executor.

**e. Smoke test** (harmless: an unknown UUID is acknowledged and ignored):

```bash
curl -i -X POST "https://<agent-host>/agent-api/v1/odm/proxy/<configUuid>/marketplace-executor/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events" \
  -H "Content-Type: application/json" \
  -H "X-BD-User: <application user>" -H "X-BD-ApiKey: <api key>" \
  -d '{"correlationId":"00000000-0000-0000-0000-000000000000","fields":{"state":"7"}}'
# -> 200 {"outcome":"IGNORED","reason":"UNKNOWN_REQUEST"}
```

A `403` means the user lacks `MARKETPLACE_EDITOR` (or the route points to a different service key); a `200 IGNORED` proves the whole chain works.

> The agent forwards **all** the caller's headers to the executor, including `X-BD-User` and `X-BD-ApiKey`.

**f. Values for the Business Rule** of [section 6](#6-make-servicenow-call-the-executor-a-business-rule):

| Property | Value with blindata-agent |
|:--|:--|
| `gateway_url` | The agent URL of step b |
| `gateway_user` | The application user of step c, sent as the `X-BD-User` header |
| `gateway_api_key` | Its API key, sent as the `X-BD-ApiKey` header |

Rotate the application user's key in the platform and in the `gateway_api_key` property together.

## 6. Make ServiceNow call the executor: a Business Rule

When the ticket reaches a closed state, ServiceNow must call the webhook through your authenticating gateway. The usual way is a **Business Rule** on the record that gets fulfilled:

| Setting | Value |
|:--|:--|
| Table | The fulfilled record (per interface: see each use case) |
| When | `after` |
| Insert / Update | Update only |
| Advanced | checked |
| Condition | State **changes into a closed state** and the correlation value is not empty (per use case) |

Store the three connection values as **system properties**, never in the script:

| Property (example names, choose your own) | Value |
|:--|:--|
| `x_acme.marketplace.gateway_url` | The gateway URL that fronts the webhook |
| `x_acme.marketplace.gateway_user` | The gateway credential identifier |
| `x_acme.marketplace.gateway_api_key` | The gateway secret. Prefer a `password2` property (decrypt it in the script with the platform's standard `GlideEncrypter`) or a Connection and Credential record |

Template script. Replace the marked parts according to the use case (the correlation read-back, and optionally the fields you send):

```javascript
(function executeRule(current, previous /* null when async */) {

  // 1. Read the marketplace request UUID back from the ticket (use case specific)
  var correlationId = current.getValue('correlation_id') || '';
  if (!correlationId) {
    return; // not a marketplace ticket
  }

  // 2. Connection values come from system properties: never hard-code or log them
  var url    = gs.getProperty('x_acme.marketplace.gateway_url');
  var user   = gs.getProperty('x_acme.marketplace.gateway_user');
  var apiKey = gs.getProperty('x_acme.marketplace.gateway_api_key');

  // 3. The webhook contract: {correlationId, fields{...}}
  var payload = {
    correlationId: correlationId,
    fields: {
      state:       current.getValue('state') || '',
      close_code:  current.getValue('close_code') || '',
      close_notes: current.getValue('close_notes') || '',
      number:      current.getValue('number') || '',
      sys_id:      current.getUniqueValue(),
      table:       current.getTableName(),
      // incidents in state 6 (Resolved) have an empty closed_by: fall back to resolved_by when the table has it
      closed_by:   current.closed_by.getDisplayValue()
                   || (current.isValidField('resolved_by') ? current.resolved_by.getDisplayValue() : '')
    }
  };

  var message = new sn_ws.RESTMessageV2();
  message.setEndpoint(url);
  message.setHttpMethod('POST');
  message.setRequestHeader('Content-Type', 'application/json');
  // Credential headers expected by your gateway (see section 5)
  message.setRequestHeader('<USER_HEADER>', user);
  message.setRequestHeader('<API_KEY_HEADER>', apiKey);
  message.setRequestBody(JSON.stringify(payload));
  message.executeAsync(); // fire and forget, does not block the user's update

})(current, previous);
```

`executeAsync()` does not wait for the answer, so a `500` from the executor is not visible and not retried. If you need that, call `message.execute()` from an **async** Business Rule, check `getStatusCode()` and log failures with `gs.error` (never log the headers), or use a Flow with retries.

**Alternative: Flow Designer.** If you have IntegrationHub, create a Flow with the trigger *Record updated* on the fulfilled table (condition: state changes to a closed state), and a **REST** step: method `POST`, the URL above, headers `Content-Type` plus the credential headers expected by your gateway (from a Connection and Credential alias, not in clear text), and a JSON body with the same shape built from data pills. Add error handling on non-2xx so that a `500` can be retried.

> **Where to attach it.** The record the executor creates is not always the one that gets fulfilled. Attach the rule to the record that is **closed by the fulfiller** and make sure it carries the correlation value: the `incident` (Table API), the `sc_req_item` (Service Catalog), the transformed target such as `sc_task` (Import Set), or whatever your script creates (Scripted REST). The executor does not care which record calls it, as long as `correlationId` is right.

The payload contract and the answers are documented in the [API reference](api-reference.md#webhook-post-apiv1upmarketplace-executorwebhooksservicenowticket-events).

## Service Catalog ordering on behalf of others

Only needed for the [Service Catalog use case](use-case-service-catalog.md). ServiceNow honours `sysparm_requested_for` only if the caller may request for other users. Otherwise it **silently** places the order for the integration user.

The permission is controlled by `glide.sc.req_for.roles`, which is empty by default; with `glide.sc.req_for.roles.default = deny`, nobody but admins can request for others. Create a dedicated role (for example `odm_marketplace_requester`), grant it to the integration user, and set **System Properties > `glide.sc.req_for.roles`** to that role (comma-separate it if the property already lists roles). Verify once by checking that a test REQ shows the requester, not the integration user, in *Requested for*.

Also supply every **mandatory variable** of the catalog item through the profile `fields`. Otherwise `order_now` fails with `400 "Mandatory Variables are required"`, which the executor reports as `ERROR`.

## Rotating secrets

- *OAuth*: create or regenerate the client secret in the OAuth application registry, update `SERVICENOW_CLIENT_SECRET` and restart the executor. Tokens already issued are cached until their expiry.
- *API key*: create the new key, update `SERVICENOW_API_KEY`, restart the executor, then revoke the old key.
- Between the rotation and the restart, ticket creation fails with an `ERROR` message that names an authentication failure.
- The credential ServiceNow uses to call the gateway is rotated on the gateway side and in the system property `gateway_api_key`.

---
[Back to README](../README.md) | [Use cases](use-cases.md) | [Provisioning](provisioning.md)
