# Deployment

[Back to README](../../README.md)

The quickstarts deploy the executor in a few commands. This page explains the rules behind them, and covers platforms without a quickstart.

## Overview

The executor can run on serverless container platforms and scale to zero when no request is in progress, with **no code change**. One condition decides whether a platform fits: how it treats an instance **after** an HTTP response has been sent.

When the Marketplace Service submits a request, the executor validates it, answers `202` immediately, and only then opens the ServiceNow ticket and posts `PENDING`, on a background thread. That work takes a few seconds and happens **after** the response. A platform fits when it:

1. **keeps CPU for a running instance** after the response (it does not freeze or throttle it between requests), and
2. **scales an instance in only after an idle window** (minutes, not milliseconds) that you can configure or rely on.

Scale to zero and cold starts are not a problem:

- the image is a native executable and starts in well under a second;
- the Marketplace Service calls the executor without a client timeout, so a cold start only delays the answer;
- the executor is stateless: the OAuth token and the parsed templates are cached in memory and simply refill after a start.

The ServiceNow callback (the webhook) is processed entirely within its request, so it works on any platform.

## Contents

- [Which platforms fit](#which-platforms-fit)
- [Common requirements](#common-requirements)
- [Gateway for the callback](#gateway-for-the-callback)
- [Known limit: scale-in during background work](#known-limit-scale-in-during-background-work)
- [Cost](#cost)
- [Containers and Kubernetes](#containers-and-kubernetes)
- [Register with the Marketplace Service](#register-with-the-marketplace-service)
- [Verify](#verify)

## Which platforms fit

| Platform | Scale to zero | CPU after the response | Key setting | Supported | Guide |
|:--|:--|:--|:--|:--|:--|
| **Google Cloud Run**, instance-based billing | Yes | Yes | `--no-cpu-throttling`, `--min-instances=0` | ✅ | [quickstart](../platforms/cloud-run.md), [details](deploy-cloud-run.md) |
| **Knative Serving** on Kubernetes (GKE, EKS, AKS, on-premises) | Yes | Yes (Kubernetes does not throttle a running pod) | `scale-to-zero-pod-retention-period` (for example `5m`) | ✅ | [quickstart](../platforms/knative.md), [details](deploy-knative.md) |
| **KEDA HTTP add-on** on Kubernetes | Yes | Yes | `scaledownPeriod` (for example `300` seconds) | ✅ | [quickstart](../platforms/keda-http.md), [details](deploy-keda-http.md) |
| **Azure Container Apps** | Yes | Yes | `cooldownPeriod` (default `300` seconds) | ✅ | [quickstart](../platforms/azure-container-apps.md), [details](deploy-azure-container-apps.md) |
| Amazon ECS on Fargate | No scale to zero on HTTP traffic (minimum one task) | Yes | none | ⚠️ works, no scale to zero | [Containers and Kubernetes](#containers-and-kubernetes) |
| Google Cloud Run, request-based billing (the default) | Yes | **No**: CPU is throttled after the response | none | ❌ | |
| AWS Lambda | Yes | **No**: the runtime is frozen after the response | none | ❌ | |
| AWS App Runner | Instances stay provisioned | **No**: CPU is throttled between requests | none | ❌ | |

On the ❌ platforms the `202` is sent, but the ticket creation that follows stalls or never runs: the request gets no `PENDING` and no ticket. Docker, plain Kubernetes and VMs (no scale to zero) are covered in [Containers and Kubernetes](#containers-and-kubernetes).

## Common requirements

Whatever the platform, the executor needs the same things. Each platform quickstart shows how to provide them.

| Requirement | Value |
|:--|:--|
| Image | `docker.io/opendatamesh/odm-platform-adapter-marketplace-executor-servicenow:<version>` or `ghcr.io/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow:<version>`. Always pin a released version |
| Port | `9019` (the container does not read a `PORT` variable) |
| Ticket profiles | Your `application.yml` mounted as a file, with `QUARKUS_CONFIG_LOCATIONS` pointing at it (for example `/etc/executor/application.yml`). See [how configuration is loaded](configuration-reference.md#how-configuration-is-loaded) |
| Environment | `SERVICENOW_INSTANCE_URL`, `ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS`, plus any variable your profiles reference |
| Secrets | `SERVICENOW_CLIENT_ID` and `SERVICENOW_CLIENT_SECRET` (or `SERVICENOW_AUTH_TYPE=API_KEY` and `SERVICENOW_API_KEY`), from the platform's secret store, as environment variables |
| Network isolation | **Hard requirement.** The executor endpoints have no authentication of their own: only the Marketplace Service and the gateway may reach it. Never expose it publicly |
| Egress | HTTPS to the ServiceNow instance, and HTTP(S) to the Marketplace Service |
| Idle window | Several minutes between the last request and the scale-in (the default of every ✅ platform above is enough) |
| Registration | The executor's internal URL, in the Marketplace Service configuration (`odm.utility-plane.marketplace-executors`), see [Register with the Marketplace Service](#register-with-the-marketplace-service) |

The full list of variables is in the [configuration reference](configuration-reference.md).

## Gateway for the callback

ServiceNow calls the executor back through an **authenticating gateway** that forwards only the webhook path. The two options are independent of the platform:

- **blindata-agent**, deployed where it can reach the executor's internal URL, with `servicesURLs["marketplace-executor"]` set to that URL;
- **your own gateway** (API gateway, ingress with authentication, load balancer with a WAF rule), allowing only `POST /api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events`.

The requirements and the identity to create are in [Securing the webhook](servicenow-setup.md#5-securing-the-webhook). Each platform page shows where the gateway fits on that platform.

## Known limit: scale-in during background work

When a platform scales an instance in, it sends it `SIGTERM`. The executor's background pool uses daemon threads and does not wait for them on shutdown, so a ticket being opened at that exact moment can be lost.

- The window is small: the idle period before a scale-in is minutes, the background work takes seconds, and the platforms above do not stop an instance while it is serving a request.
- It is unlikely, not impossible. Keep the idle window at its default (or longer) and do not lower it to a few seconds.
- If it happens, the request shows neither `PENDING` nor `ERROR` in the marketplace and no ticket exists (or a ticket exists without a `PENDING`). Check the executor logs for the request UUID ([log lines](troubleshooting.md#logs)) and submit the request again.

## Cost

On every ✅ platform you pay while an instance is alive, **including the idle window** after the last request. An executor that handles a few requests a day therefore costs a few instance-minutes per burst of activity. Keeping one instance always on (minimum one replica) removes cold starts but is billed all the time.

## Containers and Kubernetes

For Docker, plain Kubernetes or a VM, without scale to zero:

- **Containers / Kubernetes.** Use the published image. Mount your `config/application.yml` at `/work/config/application.yml` (or set `QUARKUS_CONFIG_LOCATIONS`), and inject the `SERVICENOW_*` values from your secret store as environment variables. Expose port `9019` only inside the cluster. A sketch of the container spec:

  ```yaml
  containers:
    - name: executor
      image: ghcr.io/opendatamesh-initiative/odm-platform-adapter-marketplace-executor-servicenow:<version>
      ports:
        - containerPort: 9019
      envFrom:
        - secretRef:
            name: servicenow-executor-credentials   # SERVICENOW_CLIENT_ID, SERVICENOW_CLIENT_SECRET
      env:
        - name: SERVICENOW_INSTANCE_URL
          value: https://acme.service-now.com
        - name: ODM_PRODUCT_PLANE_MARKETPLACE_SERVICE_ADDRESS
          value: http://marketplace-service:8085
      volumeMounts:
        - name: profiles
          mountPath: /work/config
  volumes:
    - name: profiles
      configMap:
        name: servicenow-executor-profiles           # key: application.yml
  ```

  Use a cluster-internal `Service` only, and a `NetworkPolicy` that admits just the Marketplace Service and the gateway.
- **Network isolation (hard requirement).** The webhook has no authentication of its own. The executor must be reachable **only** by the Marketplace Service and by the authenticating gateway: never directly from ServiceNow or the internet. See [ServiceNow setup](servicenow-setup.md#5-securing-the-webhook).
- **Egress.** The executor must reach the ServiceNow instance (HTTPS) and the Marketplace Service.
- **Several executors** behind one Marketplace Service need exclusive filters: see [Concepts](concepts.md#request-filter).
- **Secrets** come from the environment or mounted secrets, never from a committed file. Rotation steps are in [ServiceNow setup](servicenow-setup.md#rotating-secrets).

### Container configuration

- **Environment variables.** The same ones as in [Build, run locally and release](build-and-run.md#configure); every property can be set this way ([configuration reference](configuration-reference.md)). Credentials (`SERVICENOW_CLIENT_SECRET`, `SERVICENOW_API_KEY`) go in secrets, not in the image or a committed file.
- **Ticket profiles.** The packaged `application.yml` has no `servicenow.profiles`, so the container will not start without them. The container working directory is `/work` and the executor reads `config/application.yml` relative to it: mount your file at `/work/config/application.yml`. Alternatively mount it anywhere and set `QUARKUS_CONFIG_LOCATIONS` to its path (for example `-e QUARKUS_CONFIG_LOCATIONS=/etc/executor/application.yml`). Other properties can be set in that file instead of environment variables.
- **Port.** `9019` is exposed. Override with `QUARKUS_HTTP_PORT` if needed and map it accordingly.
- **User.** The process runs as user `1001` (group `root`, as OpenShift-style platforms expect). Mounted files must be readable by it.
- **Startup validation.** The container exits with a numbered list of problems if the configuration is invalid; read them with `docker logs`.

## Register with the Marketplace Service

The Marketplace Service forwards every request to the executors listed in its own configuration (`odm.utility-plane.marketplace-executors`), calling `{address}/api/v1/up/marketplace-executor/requests`. Add this executor:

```yaml
odm:
  utility-plane:
    marketplace-executors:
      - name: SERVICENOW
        address: http://marketplace-executor.internal:9019
        active: true
```

The `name` is free. Restart the Marketplace Service if it does not reload configuration. Also [secure the webhook](servicenow-setup.md#5-securing-the-webhook) (register the executor in your authenticating gateway) and create the ServiceNow Business Rule.

## Verify

1. **Health of the process.** Swagger UI at [http://localhost:9019/swagger-ui](http://localhost:9019/swagger-ui) and the OpenAPI document at [http://localhost:9019/api-docs](http://localhost:9019/api-docs) should load.
2. **ServiceNow credentials.** Run the `curl` token test from [ServiceNow setup](servicenow-setup.md#2-authentication-oauth-20-client-credentials-recommended).
3. **Webhook chain** (harmless): the [smoke test](servicenow-setup.md#5-securing-the-webhook) must answer `200 {"outcome":"IGNORED","reason":"UNKNOWN_REQUEST"}`.
4. **End to end.** Submit a request for a data product matched by your filter in the marketplace. In the executor log you should see `Opened ServiceNow ticket <number> (<table>) for request <uuid> operation <op>`, the request shows `PENDING` with the ticket link in the marketplace, and closing the ticket in ServiceNow produces the final status.

---
[Back to README](../../README.md) | [Build, run locally and release](build-and-run.md) | [Troubleshooting](troubleshooting.md)
