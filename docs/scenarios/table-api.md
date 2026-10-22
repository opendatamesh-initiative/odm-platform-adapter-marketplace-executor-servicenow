# Scenario: simple access ticket

[README](../../README.md) > **1. Scenario** > 2. Platform > 3. Test

When someone requests access to a data product, the executor creates an **`incident`** for your data governance team. A fulfiller works it as usual. Resolving it with a positive resolution code grants the access in the marketplace; *No resolution provided* or cancelling it denies the access.

The same setup works with `sc_task`, `change_request` or a custom `u_` table: only `target`, the role and the outcome rules change.

On this page you prepare ServiceNow and write the executor configuration. **You will leave with:**

- the **Client ID** and **Client Secret** of an OAuth application;
- the file **`config/application.yml`**.

You need an account with the `admin` role on a ServiceNow instance, Washington DC or later.

## 1. Create the integration user

1. *All > User Administration > Users > New*.
2. **User ID** `svc.odm.marketplace`, check **Web service access only** and **Active**. Save.
3. In the **Roles** related list add `itil`. It can create incidents and read `sys_user`.

For least privilege, use a dedicated role with **create** on `incident` and **read** on `sys_user` instead ([details](../reference/servicenow-setup.md#1-integration-user)).

## 2. Create the OAuth application

1. Open `sys_properties.list`. Find or create `glide.oauth.inbound.client.credential.grant_type.enabled` (type `true | false`) and set it to `true`.
2. *All > System OAuth > Application Registry > New > Create an OAuth API endpoint for external clients*. **Name** `ODM marketplace executor`, **OAuth Application User** `svc.odm.marketplace`. Save.
3. Open the record again and copy the **Client ID** and **Client Secret**.
4. Check that a token can be obtained:

   ```bash
   curl -s -X POST "https://<instance>.service-now.com/oauth_token.do" \
     -d grant_type=client_credentials -d client_id="<client id>" -d client_secret="<client secret>"
   # -> {"access_token":"...","token_type":"Bearer","expires_in":1799,...}
   ```

If your instance enforces REST API access policies, also allow the **Table API** for this OAuth profile ([details](../reference/servicenow-setup.md#4-api-access-policies)).

## 3. Create the Business Rule

The Business Rule tells the executor when an incident is closed. It reads the gateway URL and credentials from system properties, which you set at the end of the platform quickstart: until then it does nothing.

*All > System Definition > Business Rules > New*:

| Field | Value |
|:--|:--|
| Name | `ODM marketplace - notify executor` |
| Table | Incident [`incident`] |
| Advanced | checked |
| When | `after`, **Update** only |
| Condition (*Advanced* tab) | `current.state.changes() && (current.state == 6 \|\| current.state == 7 \|\| current.state == 8) && !current.correlation_id.nil()` |

Script (*Advanced* tab):

```javascript
(function executeRule(current, previous /* null when async */) {
  // The marketplace request UUID, written by the executor into correlation_id
  var correlationId = current.getValue('correlation_id') || '';
  if (!correlationId) {
    return; // not a marketplace ticket
  }

  var payload = {
    correlationId: correlationId,
    fields: {
      state: current.getValue('state') || '',
      close_code: current.getValue('close_code') || '',
      close_notes: current.getValue('close_notes') || '',
      number: current.getValue('number') || '',
      sys_id: current.getUniqueValue(),
      table: current.getTableName(),
      // incidents in state 6 (Resolved) have an empty closed_by: fall back to resolved_by
      closed_by: current.closed_by.getDisplayValue() || current.resolved_by.getDisplayValue(),
    },
  };

  var message = new sn_ws.RESTMessageV2();
  message.setEndpoint(gs.getProperty('x_acme.marketplace.gateway_url'));
  message.setHttpMethod('POST');
  message.setRequestHeader('Content-Type', 'application/json');
  message.setRequestHeader('X-BD-User', gs.getProperty('x_acme.marketplace.gateway_user'));
  message.setRequestHeader('X-BD-ApiKey', gs.getProperty('x_acme.marketplace.gateway_api_key'));
  message.setRequestHeader('X-BD-Tenant', gs.getProperty('x_acme.marketplace.gateway_tenant'));
  message.setRequestBody(JSON.stringify(payload));
  message.executeAsync(); // fire and forget, does not block the user's update
})(current, previous);
```

## 4. Write the executor configuration

Save this as `config/application.yml`. Change the `filter` to the data products this executor should handle, or remove it to handle all of them.

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
      outcome-rules:                 # first match wins; states: 6 Resolved, 7 Closed, 8 Canceled
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
        - when:                      # any other close code (Duplicate, Known error, ...)
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
        - when:
            state: "6|7"
          outcome: FAILED
```

The close codes above are the ones shipped by current releases. If your instance uses others (*System Definition > Choice Lists*, table `incident`, element `close_code`), adapt the rules. Every key is explained in the [configuration reference](../reference/configuration-reference.md).

## Next: pick your platform

You now have the Client ID, the Client Secret and `config/application.yml`.

| You run on... | Continue with |
|:--|:--|
| Google Cloud | [Cloud Run](../platforms/cloud-run.md) |
| Kubernetes with Knative | [Knative Serving](../platforms/knative.md) |
| Kubernetes with KEDA | [KEDA HTTP add-on](../platforms/keda-http.md) |
| Azure | [Azure Container Apps](../platforms/azure-container-apps.md) |

## If something goes wrong

| Symptom | Check |
|:--|:--|
| `ERROR` "ServiceNow rejected the integration credentials (HTTP 401/403 ...)" | The integration user's role (create on `incident`), the access policy |
| `ERROR` "Unable to obtain a ServiceNow OAuth token" | Client credentials enabled (step 2.1), client id and secret, the executor's egress |
| Incident resolved, request still `PENDING` | The Business Rule condition, then `ignored: <REASON>` in the executor log. `NON_TERMINAL_STATE` means a close code no rule matches |

More in [Troubleshooting](../reference/troubleshooting.md). A step-by-step example run with every payload is in [Examples](../reference/examples.md#simple-access-ticket).
