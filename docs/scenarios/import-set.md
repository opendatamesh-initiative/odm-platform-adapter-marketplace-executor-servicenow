# Scenario: Import Set with transform map

[README](../../README.md) > **1. Scenario** > 2. Platform > 3. Test

When someone requests access to a data product, the executor writes **one row into a staging table**. A **transform map** you control turns it into the real record (here an `sc_task`) and copies the marketplace request id into its `correlation_id`. Closing the task as *Closed Complete* grants the access in the marketplace; *Closed Incomplete* denies it.

On this page you prepare ServiceNow and write the executor configuration. **You will leave with:**

- the **Client ID** and **Client Secret** of an OAuth application;
- the file **`config/application.yml`**.

You need an account with the `admin` role on a ServiceNow instance, Washington DC or later.

## 1. Create the staging table and the transform map

1. *All > System Definition > Tables > New*: **Label** `Marketplace access`, **Name** `u_marketplace_access`, **Extends table** *Import Set Row*. Save, then add the columns:

   | Column name | Type | Max length |
   |:--|:--|:--|
   | `u_marketplace_request_id` | String | at least 40 |
   | `u_short_description` | String | 160 |
   | `u_description` | String | 4000 |

2. *All > System Import Sets > Administration > Transform Maps > New*: **Name** `Marketplace access to sc_task`, **Source table** `u_marketplace_access`, **Target table** `sc_task`, **Active** and **Run business rules** checked. Save, then add the field maps, with **Coalesce** unchecked:

   | Source field | Target field |
   |:--|:--|
   | `u_marketplace_request_id` | `correlation_id` |
   | `u_short_description` | `short_description` |
   | `u_description` | `description` |

Add any default you need (an assignment group, for example) with another field map or a transform script. Keep the staging table on synchronous transform, so the executor learns which task was created.

## 2. Create the integration user

1. *All > User Administration > Users > New*: **User ID** `svc.odm.marketplace`, check **Web service access only** and **Active**. Save.
2. In the **Roles** related list add `import_transformer`, which can write to staging tables. Check on your release that it is enough for the Import Set API; `import_set_admin` is a broader alternative.

For least privilege, use a dedicated role with a create ACL on `u_marketplace_access` instead ([details](../reference/servicenow-setup.md#1-integration-user)).

## 3. Create the OAuth application

1. Open `sys_properties.list`. Find or create `glide.oauth.inbound.client.credential.grant_type.enabled` (type `true | false`) and set it to `true`.
2. *All > System OAuth > Application Registry > New > Create an OAuth API endpoint for external clients*. **Name** `ODM marketplace executor`, **OAuth Application User** `svc.odm.marketplace`. Save.
3. Open the record again and copy the **Client ID** and **Client Secret**.
4. Check the whole chain: get a token, post a test row, and look for an `sc_task` in the answer.

   ```bash
   TOKEN=$(curl -s -X POST "https://<instance>.service-now.com/oauth_token.do" \
     -d grant_type=client_credentials -d client_id="<client id>" -d client_secret="<client secret>" | jq -r .access_token)

   curl -s -X POST "https://<instance>.service-now.com/api/now/import/u_marketplace_access" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"u_marketplace_request_id":"test","u_short_description":"test","u_description":"test"}'
   # -> {"import_set":"ISET...","result":[{"table":"sc_task","display_value":"SCTASK...","status":"inserted",...}]}
   ```

   A `status` of `error` or `ignored` means the transform map did not create the task: check *System Import Sets > Advanced > Import Log*. Delete the test task afterwards.

If your instance enforces REST API access policies, also allow the **Import Set API** for this OAuth profile ([details](../reference/servicenow-setup.md#4-api-access-policies)).

## 4. Create the Business Rule

Attach it to the **transformed** record, `sc_task`, not to the staging row. It reads the gateway URL and credentials from system properties, which you set at the end of the platform quickstart: until then it does nothing.

*All > System Definition > Business Rules > New*:

| Field | Value |
|:--|:--|
| Name | `ODM marketplace - notify executor` |
| Table | Catalog Task [`sc_task`] |
| Advanced | checked |
| When | `after`, **Update** only |
| Condition (*Advanced* tab) | `current.state.changes() && (current.state == 3 \|\| current.state == 4) && !current.correlation_id.nil()` |

If other integrations also fill `correlation_id` on `sc_task`, narrow the condition (for example on the assignment group the transform map sets).

Script (*Advanced* tab):

```javascript
(function executeRule(current, previous /* null when async */) {
  // The marketplace request UUID, copied by the transform map into correlation_id
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
      type: IMPORT_SET
      target: u_marketplace_access
      fields:                        # keys = staging table columns
        u_short_description: "Grant access to {request.provider.dataProductFqn} for {request.consumer.identifier}"
        u_description: |-
          Marketplace request: {request.identifier}
          Data product: {request.provider.dataProductFqn}
          Ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}
          Consumer: {request.consumer.type} {request.consumer.identifier}
          Requested by: {request.requester.identifier}
          Period: {request.startDate} - {request.endDate}
      outcome-rules:                 # task states: 3 Closed Complete, 4 Closed Incomplete
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

The executor adds `u_marketplace_request_id` itself. Add state `7` (*Closed Skipped*) to the rules if you use it. Every key is explained in the [configuration reference](../reference/configuration-reference.md).

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
| `ERROR` "Import Set transform did not create the ticket (status error/ignored)" | The transform map (active, target `sc_task`, field maps) and the import log |
| `ERROR` "ServiceNow rejected the integration credentials (HTTP 401/403 ...)" | The integration user's write access to the staging table, the access policy |
| Task created without a correlation id | The field map `u_marketplace_request_id` → `correlation_id` |
| Task closed, request still `PENDING` | The Business Rule is on `sc_task`, then `ignored: <REASON>` in the executor log |

More in [Troubleshooting](../reference/troubleshooting.md). A step-by-step example run with every payload is in [Examples](../reference/examples.md#import-set-with-transform-map).
