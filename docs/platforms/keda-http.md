# Quickstart with the KEDA HTTP add-on

[README](../../README.md) > 1. Scenario > **2. Platform** > 3. Test

You deploy the executor as a plain Kubernetes `Deployment` that the [KEDA](https://keda.sh/) HTTP add-on scales to zero, let ServiceNow call it back through blindata-agent, register it in the Marketplace Service and test the whole flow. Choose this over [Knative](knative.md) when you already run KEDA.

**You need:**

- the **Client ID**, **Client Secret** and **`config/application.yml`** from your [scenario page](../../README.md#1-pick-your-scenario);
- KEDA and the KEDA HTTP add-on installed (here in the namespace `keda`), and `kubectl` access to a namespace (here `odm`);
- the Marketplace Service and blindata-agent running in the cluster, with blindata-agent exposed to ServiceNow over HTTPS.

```mermaid
flowchart LR
  subgraph K8S[Kubernetes cluster]
    M[Marketplace Service]
    G[blindata-agent]
    I[KEDA HTTP interceptor]
    E[Executor<br/>Deployment, 0..3 replicas]
  end
  SN[ServiceNow]
  M --> I --> E
  E --> SN
  SN -->|ticket closed| G -->|webhook only| I
```

Every call goes through the KEDA **interceptor**, which wakes the executor up and routes by `Host` header. Callers therefore use the name `servicenow-executor-scaled`, never the executor's own `Service`.

## 1. Deploy the executor

From the directory that contains `config/application.yml`, create the secret and the profiles:

```bash
kubectl -n odm create secret generic servicenow-executor-credentials \
  --from-literal=SERVICENOW_CLIENT_ID='<client id>' \
  --from-literal=SERVICENOW_CLIENT_SECRET='<client secret>'

kubectl -n odm create configmap servicenow-executor-profiles \
  --from-file=application.yml=config/application.yml
```

Save this as `servicenow-executor.yaml`, replacing the three marked values:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: servicenow-executor
  namespace: odm
spec:
  selector:
    matchLabels:
      app: servicenow-executor
  template:
    metadata:
      labels:
        app: servicenow-executor
    spec:
      terminationGracePeriodSeconds: 30
      containers:
        - name: executor
          image: docker.io/opendatamesh/odm-platform-adapter-marketplace-executor-servicenow:<version>   # <-- 1
          ports:
            - containerPort: 9019
          resources:
            requests:
              cpu: 250m
              memory: 256Mi
            limits:
              memory: 512Mi
          env:
            - name: SERVICENOW_INSTANCE_URL
              value: https://<instance>.service-now.com                             # <-- 2
            - name: ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS
              value: http://marketplace-service.odm.svc.cluster.local:8085         # <-- 3
            - name: QUARKUS_CONFIG_LOCATIONS
              value: /etc/executor/application.yml
          envFrom:
            - secretRef:
                name: servicenow-executor-credentials
          volumeMounts:
            - name: profiles
              mountPath: /etc/executor
              readOnly: true
      volumes:
        - name: profiles
          configMap:
            name: servicenow-executor-profiles
---
apiVersion: v1
kind: Service
metadata:
  name: servicenow-executor              # the real backend, called only by the interceptor
  namespace: odm
spec:
  selector:
    app: servicenow-executor
  ports:
    - port: 9019
      targetPort: 9019
---
apiVersion: v1
kind: Service
metadata:
  name: servicenow-executor-scaled       # the name callers use
  namespace: odm
spec:
  type: ExternalName
  externalName: keda-add-ons-http-interceptor-proxy.keda.svc.cluster.local
---
apiVersion: http.keda.sh/v1alpha1
kind: HTTPScaledObject
metadata:
  name: servicenow-executor
  namespace: odm
spec:
  hosts:                                 # blindata-agent sends the host without the port,
    - servicenow-executor-scaled.odm.svc.cluster.local          # the Marketplace Service with it
    - servicenow-executor-scaled.odm.svc.cluster.local:8080
  scaleTargetRef:
    name: servicenow-executor
    kind: Deployment
    apiVersion: apps/v1
    service: servicenow-executor
    port: 9019
  replicas:
    min: 0
    max: 3
  scaledownPeriod: 300                   # mandatory, see below
```

Apply it and check it:

```bash
kubectl apply -f servicenow-executor.yaml
kubectl -n odm get httpscaledobject servicenow-executor
kubectl -n odm get deployment servicenow-executor     # READY 0/0 when idle
```

The first request starts a pod. If it does not become ready, read its logs: the executor prints a numbered list of configuration problems (`kubectl -n odm logs deployment/servicenow-executor`).

> `scaledownPeriod: 300` keeps the executor up for 5 minutes after the last request, so the ticket it opens after answering the Marketplace Service is not cut off by scale-to-zero. The `HTTPScaledObject` field names change between add-on releases: check yours with `kubectl explain httpscaledobject.spec`. [Why each setting](../reference/deploy-keda-http.md#settings-that-matter).

## 2. Connect ServiceNow back through blindata-agent

ServiceNow reaches the executor only through blindata-agent, which authenticates it and lets through only the webhook.

1. **Route the agent to the executor.** In the ODM proxy configuration of blindata-agent, set the `marketplace-executor` key to the interceptor-routed address:

   ```json
   { "servicesURLs": { "marketplace-executor": "http://servicenow-executor-scaled.odm.svc.cluster.local:8080" } }
   ```

2. **Create the ServiceNow identity in Blindata.** An application user (for example `servicenow-marketplace@<your-domain>`) with the `MARKETPLACE_EDITOR` permission only (never `DATAOPS_ADMIN`), and an API key for it.
3. **Smoke test** (harmless: an unknown request id is acknowledged and ignored):

   ```bash
   export HOOK_URL="https://<agent-host>/agent-api/v1/odm/proxy/<configUuid>/marketplace-executor/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events"
   curl -i -X POST "$HOOK_URL" -H "Content-Type: application/json" \
     -H "X-BD-User: <application user>" -H "X-BD-ApiKey: <api key>" -H "X-BD-Tenant: <tenant uuid>" \
     -d '{"correlationId":"00000000-0000-0000-0000-000000000000","fields":{"state":"7"}}'
   # -> 200 {"outcome":"IGNORED","reason":"UNKNOWN_REQUEST"}
   ```

   `403`: the user lacks `MARKETPLACE_EDITOR`. `404` from the interceptor: the `Host` header is not in `hosts` ([fix](../reference/deploy-keda-http.md#how-traffic-reaches-the-executor)).

4. **Give the values to ServiceNow.** *All > sys_properties.list > New*, once per row, with **Read roles** `admin`. The Business Rule of your scenario reads them.

   | Name | Type | Value |
   |:--|:--|:--|
   | `x_acme.marketplace.gateway_url` | string | `$HOOK_URL` |
   | `x_acme.marketplace.gateway_user` | string | the application user |
   | `x_acme.marketplace.gateway_api_key` | password2 | its API key |
   | `x_acme.marketplace.gateway_tenant` | string | the Blindata tenant UUID |

Prefer your own ingress or API gateway, or OAuth with Entra ID? See [KEDA gateway options](../reference/deploy-keda-http.md#expose-the-callback-through-a-gateway).

## 3. Register the executor in the Marketplace Service

Use the interceptor-routed address, never the backend `Service`, and restart the Marketplace Service if it does not reload configuration:

```yaml
odm:
  utility-plane:
    marketplace-executors:
      - name: SERVICENOW
        address: http://servicenow-executor-scaled.odm.svc.cluster.local:8080
        active: true
```

## 4. Test end to end

1. In the marketplace, request access to a data product matched by your filter (for example `urn:dp:finance:sales`).
2. The request shows **`PENDING`** with a ServiceNow ticket number and link.
3. In ServiceNow, close the ticket the way your scenario approves it:

   | Scenario | Grant | Deny |
   |:--|:--|:--|
   | Simple access ticket | Resolve the incident with *Solution provided* | Resolve with *No resolution provided*, or cancel |
   | Catalog order | Approve, then close the RITM as *Closed Complete* | Reject the approval |
   | Import Set | Close the `sc_task` as *Closed Complete* | Close it as *Closed Incomplete* |
   | Scripted REST | Close the `sc_task` as *Closed Complete* | Close it as *Closed Incomplete* |

4. The request becomes **`GRANTED`** (or **`DENIED`**), with the ticket link and the close notes.
5. Ask to remove the access: a revoke ticket opens, and closing it the same way makes the request **`REVOKED`**.

**It works.** You have tested the whole concept. About 5 minutes later the deployment returns to `0/0`.

## If something goes wrong

| Symptom | Check |
|:--|:--|
| No ticket and no `PENDING` | The deployment was scaled in too early: check `scaledownPeriod` |
| `404` from the interceptor | The caller's `Host` is not in `hosts`, for example sent with the port ([fix](../reference/deploy-keda-http.md#how-traffic-reaches-the-executor)) |
| `ERROR` with a ServiceNow message | Your scenario page, *If something goes wrong* |
| Ticket closed, request still `PENDING` | *All > System Logs > Outbound HTTP Requests* in ServiceNow, the blindata-agent log, then `ignored: <REASON>` in the executor log |

More in [KEDA troubleshooting](../reference/deploy-keda-http.md#troubleshooting-with-keda) and [Troubleshooting](../reference/troubleshooting.md).

## Before production

- Apply the [network policies](../reference/deploy-keda-http.md#network-policy) on the executor and on the shared interceptor.
- Pin a released image version and keep `servicenow-executor.yaml` in version control.
