# ServiceNow setup (for ServiceNow administrators)

[Back to README](../../README.md)

## Overview

For the executor to work, ServiceNow must be prepared in two directions:

1. **ServiceNow lets the executor in.** A dedicated service account, a way to log in (OAuth or API key) and permission to create the tickets.
2. **ServiceNow calls the executor back.** When a ticket is completed, a Business Rule (or Flow) sends a small JSON message so the marketplace learns the outcome. That message goes through an **authenticating gateway**, which checks who is calling.

This page covers the steps shared by every ticket type. The objects that depend on the ticket type (catalog items, staging tables, scripted APIs, Business Rule conditions) are in the [scenario pages](../../README.md#1-pick-your-scenario).

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

- **create** on each target table of your profiles ([Table API](../scenarios/table-api.md)), or write access to the staging table ([Import Set](../scenarios/import-set.md)), or the right to run your Scripted REST resource ([Scripted REST](../scenarios/scripted-rest.md));
- the right to **order the catalog items** (availability, user criteria) and to order **on behalf of other users**, if you use the [Service Catalog](../scenarios/service-catalog.md);
- **read** on `sys_user` (at least `sys_id` and the `requester-match-field`, by default `email`), for requester resolution.

The one credential below is used for _every_ interface your profiles use, so grant only what the configured profiles need. A missing permission only shows up at runtime as an `ERROR` result with a 401/403 message.

## 2. Authentication: OAuth 2.0 client credentials (recommended)

This is the default (`servicenow.auth.type: OAUTH2_CLIENT_CREDENTIALS`).

1. **Enable inbound client credentials** on the instance: it is off by default and is controlled by a system property. Use the property described in the ServiceNow _Client Credentials_ documentation for your release.
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

**1. Register the executor in the ODM proxy configuration.** Add the executor's base URL to `servicesURLs`, with the key `marketplace-executor`:

```json
{
  "servicesURLs": {
    "marketplace-executor": "http://marketplace-executor.internal:9019"
  }
}
```

The key `marketplace-executor` is a contract: it is the `servicesURLs` key, the `{service}` segment of the proxy URL, and the name of the agent authorization filter. The agent forwards `/agent-api/v1/odm/proxy/{configUuid}/{service}/**` to that base URL, keeping the rest of the path and the query string.

**2. The URL ServiceNow calls:**

```
https://<agent-host>/agent-api/v1/odm/proxy/<configUuid>/marketplace-executor/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events
```

`<configUuid>` is the identifier of the ODM proxy configuration in Blindata.

**3. A dedicated Blindata identity for ServiceNow.** Create an application user for ServiceNow with the `MARKETPLACE_EDITOR` permission and nothing else, and choose how it authenticates: API key or OAuth 2.0 (including Microsoft Entra ID). See [Authenticating ServiceNow to Blindata](#authenticating-servicenow-to-blindata) below.

**4. Authorization.** The agent's `marketplace-executor` authorization filter allows a caller with `MARKETPLACE_EDITOR` to `POST` **only inside the executors' webhooks namespace** `.../api/v1/up/marketplace-executor/webhooks/`**, which holds this executor's `.../webhooks/servicenow/ticket-events`. Every other method and path of the executor, notably `POST .../requests` (which would let a caller inject access requests bypassing the Marketplace Service), is answered with `403`. **Never use** `DATAOPS_ADMIN` **for the ServiceNow identity\*\*: it bypasses the filter and can reach every executor path. The filter is delivered with the agent and must be released together with this executor.

**5. Smoke test** (harmless: an unknown UUID is acknowledged and ignored):

```bash
curl -i -X POST "https://<agent-host>/agent-api/v1/odm/proxy/<configUuid>/marketplace-executor/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events" \
  -H "Content-Type: application/json" \
  -H "X-BD-User: <application user>" -H "X-BD-ApiKey: <api key>" -H "X-BD-Tenant: <tenant uuid>" \
  -d '{"correlationId":"00000000-0000-0000-0000-000000000000","fields":{"state":"7"}}'
# -> 200 {"outcome":"IGNORED","reason":"UNKNOWN_REQUEST"}
```

For OAuth, see [Using OAuth 2.0](#using-oauth-20). A `403` means the user lacks `MARKETPLACE_EDITOR` (or the route points to a different service key); a `200 IGNORED` proves the whole chain works.

> The agent forwards **all** the caller's headers to the executor, including `X-BD-User` and `X-BD-ApiKey`.

**6. Values for the Business Rule** of [section 6](#6-make-servicenow-call-the-executor-a-business-rule):

| Property          | Value with blindata-agent                                                                                                                             |
| :---------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `gateway_url`     | The agent URL of step 2                                                                                                                               |
| `gateway_user`    | API key method: the application user of step 3, sent as the `X-BD-User` header                                                                        |
| `gateway_api_key` | API key method: its API key, sent as the `X-BD-ApiKey` header                                                                                         |
| `gateway_tenant`  | The Blindata tenant UUID, sent as the `X-BD-Tenant` header (both methods). With OAuth, the other two properties are not needed: use an OAuth profile. |

Rotate the application user's key in the platform and in the `gateway_api_key` property together.

### Authenticating ServiceNow to Blindata

The agent does not keep its own list of users. It forwards the credentials it receives to the Blindata API, which identifies the caller and returns the caller's permissions. The agent authorization filter then applies those permissions. Every call needs the tenant in the `X-BD-Tenant` header, and the Business Rule must send it.

Two methods are available for ServiceNow. Both use a dedicated application user holding only `MARKETPLACE_EDITOR`.

|                           | API key                                   | OAuth 2.0 bearer token                                                                    |
| :------------------------ | :---------------------------------------- | :---------------------------------------------------------------------------------------- |
| Headers                   | `X-BD-User`, `X-BD-ApiKey`, `X-BD-Tenant` | `Authorization: Bearer <token>`, `X-BD-Tenant`                                            |
| Secret kept in ServiceNow | The API key (static)                      | The OAuth client secret or certificate. Tokens are short-lived and renewed automatically. |
| Best for                  | Simple setups, fast start                 | Customers that standardize on an identity provider such as Microsoft Entra ID             |

#### Using an API key

1. In Blindata, create an **application user** for ServiceNow (for example `servicenow-marketplace@<your-domain>`). Give it the `MARKETPLACE_EDITOR` permission and no other permission, and never `DATAOPS_ADMIN`.
2. Generate an **API key** for that user (see [User configuration](/administration/user-management/user-configuration/)).
3. Store the user name, the API key and the tenant UUID in ServiceNow system properties, as described in [section 6](#6-make-servicenow-call-the-executor-a-business-rule).
4. Send them on every call:
   ```javascript
   message.setRequestHeader('X-BD-User', user);
   message.setRequestHeader('X-BD-ApiKey', apiKey);
   message.setRequestHeader('X-BD-Tenant', tenantUuid);
   ```

At each call the agent exchanges the user and API key for a short-lived Blindata access token, then loads the user's permissions. To rotate the key, generate a new one in Blindata and update the ServiceNow property together, then revoke the old key.

#### Using OAuth 2.0

The agent also accepts `Authorization: Bearer <JWT>`. The Blindata API accepts two kinds of token:

- a token issued by Blindata itself (the same one the API-key exchange returns);
- an access token issued by **Microsoft Entra ID** (see the next section).

Any other issuer is rejected with `401`. In both cases the token must belong to an existing, valid Blindata user, whose permissions decide what the caller can do. Send the tenant in `X-BD-Tenant`.

##### Microsoft Entra ID with client credentials

ServiceNow can obtain Entra tokens itself, so the webhook can be authenticated with an Entra **service principal** and no API key.

**1. Entra ID.**

1. Register an application for the Blindata API audience (or reuse the one your Blindata tenant is configured for), and note its **Application (client) ID**.
2. Register a second application for ServiceNow. Create a **client secret** or, preferably, a **certificate**, and grant it access to the API application.
3. Note the **Directory (tenant) ID** and the **Object ID** of the ServiceNow _service principal_ (Enterprise applications, not App registrations). They build the Blindata user name below.

**2. Blindata.**

1. Make sure the Microsoft (`msazure`) provider is configured for your tenant, with the audience, issuer (`https://login.microsoftonline.com/<directory-id>/v2.0`), key-set URI and, optionally, the Entra tenant id (`tid`) that must match. This is the same configuration used for Microsoft sign-in, and the platform administrator manages it.
2. Create an **application user** whose user name is built from the service principal:

   ```
    msazure:<object-id>@<directory-id>
   ```

   Give it `MARKETPLACE_EDITOR` only. The Blindata API recognizes a token as a service principal when its `sub` and `oid` claims are equal, and looks the user up by this exact name. If the user does not exist or is disabled, the call is answered with `401`.

**3. ServiceNow.**

1. In **System OAuth > Application Registry**, create an **OAuth client** for the Entra token endpoint `https://login.microsoftonline.com/<directory-id>/oauth2/v2.0/token`, using the **client credentials** grant, with the ServiceNow application's client id and secret. Request the scope `<api-application-client-id>/.default` (or `api://<…>/.default` if your application uses an application ID URI).
2. Create an **OAuth profile** for it. The platform stores the token and renews it when it expires.
3. Attach the profile to the REST Message used by the Business Rule, instead of the API key headers:

   ```javascript
   message.setAuthenticationProfile('oauth2', '<oauth profile sys_id>');
   message.setRequestHeader('X-BD-Tenant', tenantUuid);
   ```

   In a Flow, use a Connection and Credential alias of type OAuth 2.0 and add the `X-BD-Tenant` header.

**4. Verify.** Request a token by hand and send the smoke test:

```bash
TOKEN=$(curl -s -X POST "https://login.microsoftonline.com/<directory-id>/oauth2/v2.0/token" \
  -d grant_type=client_credentials -d client_id=<servicenow-app-client-id> \
  -d client_secret=<secret> -d scope="<api-application-client-id>/.default" | jq -r .access_token)

curl -i -X POST "https://<agent-host>/agent-api/v1/odm/proxy/<configUuid>/marketplace-executor/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -H "X-BD-Tenant: <tenant uuid>" \
  -d '{"correlationId":"00000000-0000-0000-0000-000000000000","fields":{"state":"7"}}'
# -> 200 {"outcome":"IGNORED","reason":"UNKNOWN_REQUEST"}
```

| Answer        | Likely cause                                                                                                                                                                                                                 |
| :------------ | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `401`         | The token audience, issuer or tenant id does not match the `msazure` configuration. The token has more than one audience. The user `msazure:<object-id>@<directory-id>` does not exist or is disabled. The token is expired. |
| `403`         | The user exists but lacks `MARKETPLACE_EDITOR`, or the proxy route points to a different service key.                                                                                                                        |
| `200 IGNORED` | The whole chain works.                                                                                                                                                                                                       |

**Notes.**

- Use a **v2.0** token. Tokens from the v1.0 endpoint have a different issuer and are rejected.
- Prefer a **certificate** to a client secret, and rotate it before it expires. An expired credential makes ServiceNow fail the call with `401`.
- Entra and ServiceNow handle token renewal. Nothing needs to be stored in the Business Rule except the tenant UUID.
- The agent forwards the request headers to the executor, including `Authorization`. Keep the executor reachable only from the agent, as described in [section 5](#5-securing-the-webhook).

## 6. Make ServiceNow call the executor: a Business Rule

When the ticket reaches a closed state, ServiceNow must call the webhook through your authenticating gateway. The usual way is a **Business Rule** on the record that gets fulfilled:

| Setting         | Value                                                                                       |
| :-------------- | :------------------------------------------------------------------------------------------ |
| Table           | The fulfilled record (see each scenario page)                                     |
| When            | `after`                                                                                     |
| Insert / Update | Update only                                                                                 |
| Advanced        | checked                                                                                     |
| Condition       | State **changes into a closed state** and the correlation value is not empty (see each scenario page) |

Store the three connection values as **system properties**, never in the script:

| Property (example names, choose your own) | Value                                                                                                                                                            |
| :---------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `x_acme.marketplace.gateway_url`          | The gateway URL that fronts the webhook                                                                                                                          |
| `x_acme.marketplace.gateway_user`         | The gateway credential identifier                                                                                                                                |
| `x_acme.marketplace.gateway_api_key`      | The gateway secret. Prefer a `password2` property (decrypt it in the script with the platform's standard `GlideEncrypter`) or a Connection and Credential record |
| `x_acme.marketplace.gateway_tenant`       | blindata-agent only: the Blindata tenant UUID, sent as the `X-BD-Tenant` header                                                                                 |

Template script. Replace the marked parts according to the scenario (the correlation read-back, and optionally the fields you send):

```javascript
(function executeRule(current, previous /* null when async */) {
  // 1. Read the marketplace request UUID back from the ticket (scenario specific)
  var correlationId = current.getValue('correlation_id') || '';
  if (!correlationId) {
    return; // not a marketplace ticket
  }

  // 2. Connection values come from system properties: never hard-code or log them
  var url = gs.getProperty('x_acme.marketplace.gateway_url');
  var user = gs.getProperty('x_acme.marketplace.gateway_user');
  var apiKey = gs.getProperty('x_acme.marketplace.gateway_api_key');
  var tenant = gs.getProperty('x_acme.marketplace.gateway_tenant'); // blindata-agent only

  // 3. The webhook contract: {correlationId, fields{...}}
  var payload = {
    correlationId: correlationId,
    fields: {
      state: current.getValue('state') || '',
      close_code: current.getValue('close_code') || '',
      close_notes: current.getValue('close_notes') || '',
      number: current.getValue('number') || '',
      sys_id: current.getUniqueValue(),
      table: current.getTableName(),
      // incidents in state 6 (Resolved) have an empty closed_by: fall back to resolved_by when the table has it
      closed_by:
        current.closed_by.getDisplayValue() ||
        (current.isValidField('resolved_by')
          ? current.resolved_by.getDisplayValue()
          : ''),
    },
  };

  var message = new sn_ws.RESTMessageV2();
  message.setEndpoint(url);
  message.setHttpMethod('POST');
  message.setRequestHeader('Content-Type', 'application/json');
  // Credential headers expected by your gateway (see section 5)
  message.setRequestHeader('<USER_HEADER>', user);
  message.setRequestHeader('<API_KEY_HEADER>', apiKey);
  // blindata-agent: the headers above are X-BD-User and X-BD-ApiKey, plus the tenant
  message.setRequestHeader('X-BD-Tenant', tenant);
  message.setRequestBody(JSON.stringify(payload));
  message.executeAsync(); // fire and forget, does not block the user's update
})(current, previous);
```

`executeAsync()` does not wait for the answer, so a `500` from the executor is not visible and not retried. If you need that, call `message.execute()` from an **async** Business Rule, check `getStatusCode()` and log failures with `gs.error` (never log the headers), or use a Flow with retries.

**Alternative: Flow Designer.** If you have IntegrationHub, create a Flow with the trigger _Record updated_ on the fulfilled table (condition: state changes to a closed state), and a **REST** step: method `POST`, the URL above, headers `Content-Type` plus the credential headers expected by your gateway (from a Connection and Credential alias, not in clear text), and a JSON body with the same shape built from data pills. Add error handling on non-2xx so that a `500` can be retried.

> **Where to attach it.** The record the executor creates is not always the one that gets fulfilled. Attach the rule to the record that is **closed by the fulfiller** and make sure it carries the correlation value: the `incident` (Table API), the `sc_req_item` (Service Catalog), the transformed target such as `sc_task` (Import Set), or whatever your script creates (Scripted REST). The executor does not care which record calls it, as long as `correlationId` is right.

The payload contract and the answers are documented in the [API reference](api-reference.md#webhook-post-apiv1upmarketplace-executorwebhooksservicenowticket-events).

## Service Catalog ordering on behalf of others

Only needed for the [Service Catalog scenario](../scenarios/service-catalog.md). ServiceNow honours `sysparm_requested_for` only if the caller may request for other users. Otherwise it **silently** places the order for the integration user.

The permission is controlled by `glide.sc.req_for.roles`, which is empty by default; with `glide.sc.req_for.roles.default = deny`, nobody but admins can request for others. Create a dedicated role (for example `odm_marketplace_requester`), grant it to the integration user, and set **System Properties >** `glide.sc.req_for.roles` to that role (comma-separate it if the property already lists roles). Verify once by checking that a test REQ shows the requester, not the integration user, in _Requested for_.

Also supply every **mandatory variable** of the catalog item through the profile `fields`. Otherwise `order_now` fails with `400 "Mandatory Variables are required"`, which the executor reports as `ERROR`.

## Rotating secrets

- _OAuth_: create or regenerate the client secret in the OAuth application registry, update `SERVICENOW_CLIENT_SECRET` and restart the executor. Tokens already issued are cached until their expiry.
- _API key_: create the new key, update `SERVICENOW_API_KEY`, restart the executor, then revoke the old key.
- Between the rotation and the restart, ticket creation fails with an `ERROR` message that names an authentication failure.
- The credential ServiceNow uses to call the gateway is rotated on the gateway side and in the system property `gateway_api_key`.

---

[Back to README](../../README.md) | [Scenarios](../../README.md#1-pick-your-scenario) | [Build, run locally and release](build-and-run.md)
