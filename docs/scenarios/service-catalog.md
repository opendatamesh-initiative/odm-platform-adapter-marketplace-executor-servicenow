# Scenario: catalog order with approvals

[README](../../README.md) > **1. Scenario** > 2. Platform > 3. Test

When someone requests access to a data product, the executor **orders a catalog item on behalf of that person**, so the order shows up as their own request (`REQ`, with a requested item `RITM`). Your catalog flow runs the approvals and fulfilment. Closing the RITM as *Closed Complete* grants the access in the marketplace; a rejected approval denies it.

On this page you prepare ServiceNow and write the executor configuration. **You will leave with:**

- the **Client ID** and **Client Secret** of an OAuth application;
- the file **`config/application.yml`**.

You need an account with the `admin` role on a ServiceNow instance, Washington DC or later, with the Service Catalog in use. Every marketplace requester must exist in `sys_user` with the same email as in the marketplace.

## 1. Create the integration user

1. *All > User Administration > Roles > New*: **Name** `odm_marketplace_requester`. Save.
2. *All > User Administration > Users > New*: **User ID** `svc.odm.marketplace`, check **Web service access only** and **Active**. Save.
3. In the user's **Roles** related list add `odm_marketplace_requester` and `itil` (`itil` lets it read `sys_user`, to find the requester).
4. Let the role order for other users: in `sys_properties.list`, set `glide.sc.req_for.roles` to `odm_marketplace_requester` (comma-separated if it already lists roles). Without it, ServiceNow **silently** places every order for the integration user.

[Details and least-privilege alternatives](../reference/servicenow-setup.md#service-catalog-ordering-on-behalf-of-others).

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

If your instance enforces REST API access policies, also allow the **Service Catalog API** and the **Table API** for this OAuth profile ([details](../reference/servicenow-setup.md#4-api-access-policies)).

## 3. Create the two catalog items

*All > Service Catalog > Catalog Definitions > Maintain Items > New*, once per item:

| Field | Subscribe item | Unsubscribe item |
|:--|:--|:--|
| Name | `Data product access` | `Data product access removal` |
| Catalogs / Category | your catalog and category | your catalog and category |
| Fulfilment (*Process Engine* tab) | the flow with your approvals and fulfilment tasks | the flow with your revocation tasks |

Save, then add these **variables** to each item (*Variables > New*). The names must match the `fields` keys of step 5.

| Name | Type | Notes |
|:--|:--|:--|
| `marketplace_request_id` | Single Line Text | **Required.** Holds the marketplace request id. Hide it with a *Catalog UI Policy* (no condition, **Visible** `false`) |
| `data_product` | Single Line Text | |
| `ports` | Multi Line Text | |
| `consumer` | Single Line Text | |
| `period` | Single Line Text | subscribe item only |

Then, on each item:

- **Available For**: add a user criteria that includes the integration user (for example by role `odm_marketplace_requester`).
- **Approvals**: let the flow set the RITM `approval` to `rejected` when an approver refuses.
- Copy the item's **sys_id** (right-click the form header > *Copy sys_id*). You need both in step 5.

Every **mandatory** variable must be filled by `fields` in step 5, or the order fails with `400 "Mandatory Variables are required"`.

## 4. Create the Business Rule

The record that is closed is the **requested item** (`sc_req_item`). The Business Rule tells the executor when that happens. It reads the gateway URL and credentials from system properties, which you set at the end of the platform quickstart: until then it does nothing.

*All > System Definition > Business Rules > New*:

| Field | Value |
|:--|:--|
| Name | `ODM marketplace - notify executor` |
| Table | Requested Item [`sc_req_item`] |
| Advanced | checked |
| When | `after`, **Update** only |
| Condition (*Advanced* tab) | `(current.state.changes() && (current.state == 3 \|\| current.state == 4)) \|\| (current.approval.changes() && current.approval == 'rejected')` |

Script (*Advanced* tab):

```javascript
(function executeRule(current, previous /* null when async */) {
  // The marketplace request UUID, written by the executor into the catalog variable
  var correlationId = current.variables.marketplace_request_id + '';
  if (!correlationId || correlationId === 'undefined') {
    return; // not a marketplace order
  }

  var payload = {
    correlationId: correlationId,
    fields: {
      state: current.getValue('state') || '',
      approval: current.getValue('approval') || '',
      close_notes: current.getValue('close_notes') || '',
      number: current.getValue('number') || '',
      sys_id: current.getUniqueValue(),
      table: current.getTableName(), // sc_req_item
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

Save this as `config/application.yml`. Paste the two sys_ids of step 3 into `target`, and change the `filter` to the data products this executor should handle (or remove it to handle all of them).

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
      target: <sys_id of "Data product access">
      fields:                        # keys = catalog variable names
        data_product: "{request.provider.dataProductFqn}"
        ports: "{#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}"
        consumer: "{request.consumer.type}: {request.consumer.identifier}"
        period: "{request.startDate} - {request.endDate}"
      outcome-rules:                 # first match wins; RITM states: 3 Closed Complete, 4 Closed Incomplete
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
      target: <sys_id of "Data product access removal">
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

Before ordering, the executor looks the requester's email up in `sys_user` and orders on their behalf. Two-step checkout is handled automatically. In the marketplace, the `PENDING` message refers to the request (`REQ`) and the final message to the RITM, the record that is closed. Every key is explained in the [configuration reference](../reference/configuration-reference.md).

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
| `ERROR` "Requester ... not found in ServiceNow sys_user" | The requester's email in `sys_user`, and the integration user's read access to `sys_user` |
| `ERROR` "Catalog orders require a user requester" | The marketplace requester must be of type `user` |
| `ERROR` with `Mandatory Variables are required` (HTTP 400) | A mandatory variable of the item is missing from `fields` |
| *Requested for* is the integration user | `glide.sc.req_for.roles` (step 1.4) |
| RITM closed, request still `PENDING` | The Business Rule is on `sc_req_item`, then `ignored: <REASON>` in the executor log |

More in [Troubleshooting](../reference/troubleshooting.md). A step-by-step example run with every payload is in [Examples](../reference/examples.md#catalog-order-with-approvals).
