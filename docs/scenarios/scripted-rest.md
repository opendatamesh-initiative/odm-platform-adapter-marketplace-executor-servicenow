# Scenario: your own Scripted REST API

[README](../../README.md) > **1. Scenario** > 2. Platform > 3. Test

The ticket creation logic (assignment group, templates, defaults, extra checks) lives **inside ServiceNow**, in a small Scripted REST API you own. The executor sends it the ticket fields and the marketplace request id; your script creates the record (here an `sc_task`) and returns its number. Closing the task as *Closed Complete* grants the access in the marketplace; *Closed Incomplete* denies it.

On this page you prepare ServiceNow and write the executor configuration. **You will leave with:**

- the **Client ID** and **Client Secret** of an OAuth application;
- the file **`config/application.yml`**.

You need an account with the `admin` role on a ServiceNow instance, Washington DC or later (or developer rights on the scoped application that holds the API).

## 1. Create the integration user

1. *All > User Administration > Roles > New*: **Name** `odm_marketplace_integration`. Save.
2. *All > User Administration > Users > New*: **User ID** `svc.odm.marketplace`, check **Web service access only** and **Active**. Save.
3. In the user's **Roles** related list add `odm_marketplace_integration`. The API resource of step 2 requires it. The example script uses `GlideRecord`, which does not enforce ACLs on `sc_task`: the role on the resource is what controls access.

## 2. Create the Scripted REST API

1. *All > System Web Services > Scripted Web Services > Scripted REST APIs > New*: **Name** `Marketplace access`, **API ID** `v1`. Save. Note the **Base API path** the form shows, for example `/api/x_acme_mkt/v1`.
2. In the *Resources* related list, **New**: **Name** `access_tickets`, **HTTP method** `POST`, **Relative path** `/access_tickets`, **Requires authentication** checked, **Requires ACL authorization** checked with an ACL that requires `odm_marketplace_integration`.
3. Paste the script. The assignment groups are examples: use groups that exist on your instance.

```javascript
(function process(/*RESTAPIRequest*/ request, /*RESTAPIResponse*/ response) {
  var body = request.body.data; // the JSON sent by the executor

  var gr = new GlideRecord('sc_task');
  gr.initialize();
  gr.short_description = body.short_description;
  gr.description = body.description;
  gr.correlation_id = body.correlation_id; // the marketplace request UUID: always present
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
  response.setBody({ result: { number: gr.getValue('number'), sys_id: sysId, table: 'sc_task' } });
})(request, response);
```

## 3. Create the OAuth application

1. Open `sys_properties.list`. Find or create `glide.oauth.inbound.client.credential.grant_type.enabled` (type `true | false`) and set it to `true`.
2. *All > System OAuth > Application Registry > New > Create an OAuth API endpoint for external clients*. **Name** `ODM marketplace executor`, **OAuth Application User** `svc.odm.marketplace`. Save.
3. Open the record again and copy the **Client ID** and **Client Secret**.
4. Check the whole chain: get a token and call your API once.

   ```bash
   TOKEN=$(curl -s -X POST "https://<instance>.service-now.com/oauth_token.do" \
     -d grant_type=client_credentials -d client_id="<client id>" -d client_secret="<client secret>" | jq -r .access_token)

   curl -s -X POST "https://<instance>.service-now.com/api/x_acme_mkt/v1/access_tickets" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"operation":"MARKETPLACE_SUBSCRIBE","short_description":"test","description":"test","correlation_id":"test"}'
   # -> {"result":{"number":"SCTASK...","sys_id":"...","table":"sc_task"}}
   ```

   ServiceNow can wrap a script's answer in another `result` object. If the number is somewhere else (for example `/result/result/number`), adapt `number-pointer` and `sys-id-pointer` in step 5. Delete the test task afterwards.

If your instance enforces REST API access policies, also allow this Scripted REST API for the OAuth profile ([details](../reference/servicenow-setup.md#4-api-access-policies)).

## 4. Create the Business Rule

Attach it to the record your script creates (`sc_task`). It reads the gateway URL and credentials from system properties, which you set at the end of the platform quickstart: until then it does nothing.

*All > System Definition > Business Rules > New*:

| Field | Value |
|:--|:--|
| Name | `ODM marketplace - notify executor` |
| Table | Catalog Task [`sc_task`] |
| Advanced | checked |
| When | `after`, **Update** only |
| Condition (*Advanced* tab) | `current.state.changes() && (current.state == 3 \|\| current.state == 4 \|\| current.state == 7) && !current.correlation_id.nil()` |

Script (*Advanced* tab):

```javascript
(function executeRule(current, previous /* null when async */) {
  // The marketplace request UUID, written by your Scripted REST resource into correlation_id
  var correlationId = current.getValue('correlation_id') || '';
  if (!correlationId) {
    return; // not a marketplace task
  }

  var payload = {
    correlationId: correlationId,
    fields: {
      state: current.getValue('state') || '',
      close_code: current.getValue('close_code') || '',
      close_notes: current.getValue('close_notes') || '',
      number: current.getValue('number') || '',
      sys_id: current.getUniqueValue(),
      table: current.getTableName(), // sc_task
      closed_by: current.closed_by.getDisplayValue(),
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

## 5. Write the executor configuration

Save this as `config/application.yml`. Set `path` to your resource, and change the `filter` to the data products this executor should handle (or remove it to handle all of them).

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
      path: /api/x_acme_mkt/v1/access_tickets
      fields:                        # the JSON keys your script reads
        operation: "{operation}"
        short_description: "Grant access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        description: |-
          Marketplace request: {request.identifier}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Requested by: {request.requester.identifier}
          Period: {request.startDate} - {request.endDate}
      response:                      # where the ticket reference is in your script's answer
        number-pointer: /result/number
        sys-id-pointer: /result/sys_id
        table: sc_task
      outcome-rules:                 # task states: 3 Closed Complete, 4 Closed Incomplete, 7 Closed Skipped
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

The executor adds `correlation_id` to the body itself. Every key is explained in the [configuration reference](../reference/configuration-reference.md).

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
| `ERROR` "ServiceNow response did not contain the ticket number at /result/number" | The real answer of your script (step 3.4) and the pointers |
| `ERROR` "ServiceNow call ... failed with HTTP 403" | The resource's ACL and the role of the integration user, the access policy |
| `ERROR` with HTTP 500 and your script's message | The script failed to insert the task: check the system log |
| Task closed, request still `PENDING` | The Business Rule is on the table your script writes, then `ignored: <REASON>` in the executor log |

More in [Troubleshooting](../reference/troubleshooting.md). A step-by-step example run with every payload is in [Examples](../reference/examples.md#your-own-scripted-rest-api).
