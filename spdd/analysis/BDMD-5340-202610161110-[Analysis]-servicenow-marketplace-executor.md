# SPDD Analysis: ServiceNow Marketplace Executor (ticket-driven access request fulfilment)

## Original Business Requirement

BDMD-5340 on odm-platform-adapter-marketplace-executor-servicenow
The ServiceNow marketplace executor is an adapter service that allows to integrate the ODM marketplace workflows and processes into the ServiceNow infrastructure. 

The service should be easily configurable, allowing the user to specify the resource type created on ServiceNow, and its content structure.
It should also be configurable to adapt on different processes/practices the user has adopted on ServiceNow.

The resource created must contain in some part of its structure the marketplace request identifier, so when the ServiceNow webhook is triggered, it is possible to update the correct access request in the marketplace service. 

Workflow example (must be valid for every access request Operation)
Task Opened:
 - Marketplace Access Request --> Executor --> Task Opened on Service Now
Task Completed:
- Ticket Completed on Service Now --> Service Now WebHook --> Executor --> Marketplace Access Request Updated with the Operation result

The repository README.md should contain a detailed, user friendly section that explain how to configure the service and ServiceNow.

Nice to have but not mandatory :
The service should be able to be used also in a configuration where
- blindata-api webHooks--> executor --> ServiceNow
- Service Now webHooks --> executor --> blindata-api API 
(include it in the analysis and if it is actually usefull instead of doing something like blindata-api <--> ServiceNow)

### Requirement Clarifications (product owner answers to the first analysis round)

Requirement Ambiguities resolution
- "Resource type": support all Service Now interfaces from day one.
- "Adapt to different processes/practices": Ok per-operation configuration
- "Unsubscribe refused or failed": Use ERROR for both plaform( and services) errors/exceptions and user (service now side) denial. 
- "Who approves": yes, but only for MARKETPLACE_SUBSCRIBE operation. For the MARKETPLACE_UNSUBSCRIBE the denial is considered as an error (in this case the error message should be descriptive of the scenario)
- "Partial grants": Do not allow partial grants. The ServiceNow ticket is considered to handle all the ports (at once) in the access request.
- "Message content": The message should describe the grant process (resolution notes, close notes, ticket number, link,etc... and maybe username?)
- "Ticket lifecycle after completion": First final status wins. 
- "Time-bound access": automatic revoke is out of scope for now. (include in a "out of scope" subsection of the analysis how would you manage that, considering also the different authentication layers)
- "Multiple executors": yes, possibly. In the ServiceNow exectuor there should be the possibility to configure the filtering of requests, so only certain requests are handled.

### Requirement Clarifications (second round)

- ServiceNow → executor (webhook) the auth is not handled by the executor, but by the blindata-agent proxy
- executor --> serviceNow auth is handled by a serviceNow application token configured in the executor

Additional requirements: in the readme should be described a basic scenario (with the base config) for each ServiceNow Creation Interfaces use.

### Requirement Clarifications (third round)

"All ServiceNow interfaces": confirmed
"Catalog ordering style": yes requested_for must resolve to the marketplace requester
"Request filter criteria": ok the proposed ones. Use the regext for pattern matching
"Closing user in messages": confirmed inclusion. Make the inclusion configurable
"First-final-wins with co-handling executors"exlusive filters are an acceptable operating rule
"Agent authorization for the webhook route": A new executor auth filter should be included in the development
"Deployments without blindata-agent": confirmed that the agent is a hard prerequisite. Standalone deployments must provide their own gateway
"ServiceNow application token": use best practice and suggested auth mechanism for this scenario

Template engine (product owner, earlier round): "for the engine use the best fit for Quarkus" → Quarkus Qute.

---

## Domain Concept Identification

### Existing Concepts (from codebase)

**In this repository** (a fresh copy of `odm-platform-adapter-marketplace-executor-starter`: Quarkus 3.40, Java 21, stateless, no database):

- **MarketplaceRequest**: the access request the Marketplace Service forwards to the executor. It carries `identifier`, `name`, provider (data product FQN and port FQNs), consumer, requester, free-form `properties`, `startDate` and `endDate`.
  - It is the source data for the ServiceNow ticket content and for the request filter.
  - The `identifier` the executor receives is the Marketplace Service's **internal UUID**, not the business identifier. See `AccessRequestSubmitterExecutorOutputPortImpl` in the marketplace server: "the natural key is identifier + operation".
- **MarketplaceOperation**: `MARKETPLACE_SUBSCRIBE` and `MARKETPLACE_UNSUBSCRIBE`. "Every access request Operation" means exactly these two today.
- **MarketplaceResult / MarketplaceRequestStatus**: the outcome the executor reports. Statuses are `PENDING`, `GRANTED`, `DENIED`, `REVOKED` and `ERROR`, and each result also carries a message, the provider and `createdAt`.
- **ProcessMarketplaceRequest** (use case): validates invariants, then asynchronously calls `MarketplaceAccessOutboundPort.grantAccess/revokeAccess` and immediately reports `GRANTED` or `REVOKED`.
  - This **synchronous-completion model does not fit ServiceNow**. There, completion happens later, outside the executor.
- **MarketplaceAccessOutboundPort / MarketplaceAccessOutboundPortImpl**: the documented extension point. It is a TODO stub today, and it is where the "open a ticket" behaviour belongs.
- **MarketplaceResultOutboundPort**: posts results to `{marketplace}/api/v1/pp/marketplace/requests/{uuid}/results`. Before posting it sleeps a hard-coded 2 seconds, and on failure it only logs and swallows the error.
- **MarketplaceServiceConfig**: a Quarkus `@ConfigMapping` (`odm.product-plane.marketplace-service`). It sets the precedent for typed YAML configuration.
- **RestUtils** (the template's HTTP client abstraction, including `AuthenticatedRestUtilsTemplate` and the `Oauth2` model): reusable for ServiceNow outbound calls. Its OAuth2 client-credentials support (token request, caching until `expires_in`) is reused for the recommended ServiceNow auth mode.

**In the Marketplace Service** (`odm-platform-pp-marketplace-server`, boundary context):

- **AccessRequest**: identified by `uuid`.
  - It is persisted, then submitted **inside the same DB transaction** to **every active executor** listed in `odm.utility-plane.marketplace-executors`.
  - An executor that answers with a non-2xx response is treated as "not handled". The client exception is swallowed by `presentClientException`, which does nothing.
  - If **no** executor handles the request, the transaction is rolled back with "No executor was able to handle the request".
  - It can be retrieved with `GET /api/v1/pp/marketplace/requests/{uuid}`.
  - Search supports only `identifier` and `operation`, with **no date filters**.
- **ExecutorResponse**: stored **append-only**, with many rows per access request and no uniqueness constraint.
  - **It does not record which executor produced it.** The provider FQN must match the request, and the ports must be a subset. Each upload emits `MARKETPLACE_EXECUTOR_RESULT_RECEIVED` to the Notification Service.
  - Responses can be searched by `accessRequestUuid` and `status`.
- **No authentication** on the Marketplace Service itself, on the results endpoint, or on the calls it makes to executors.

**In the Blindata ecosystem** (boundary context, relevant to the nice-to-have and to the out-of-scope time-bound revoke):

- **Blindata observer** (`odm-platform-adapter-observer-blindata`, use case `MarketplaceAccessRequestsPortUpdate`):
  - Consumes `MARKETPLACE_EXECUTOR_RESULT_RECEIVED`, **ignores `PENDING`**, and maps the other statuses to Blindata port grant statuses (`ERROR` → `PLATFORM_ERROR`, …).
  - Copies the result `message` into `grantMessage`, which is visible to Blindata users.
  - Calls blindata-api `PATCH /api/v1/marketplace/requests/*/update/ports`.
  - So the path **ODM marketplace → executor → ODM → observer → Blindata already exists end-to-end**.
- **blindata-agent ODM proxy** (`rest/v1/odm/ODMProxyController`, `services/odm/ODMProxyService`, `jobs/odmproxy/OdmProxyConfig`): the authentication gateway in front of ODM services, and the component that will authenticate ServiceNow webhook calls.
  - It forwards `/agent-api/v1/odm/proxy/{configUuid}/{service}/**` to the URL configured under `servicesURLs[service]` in the ODM proxy job configuration.
  - Callers authenticate to the agent with Blindata credentials (for example the `X-BD-User` and `X-BD-ApiKey` headers), and their roles are passed to `checkAuth`.
  - **Authorization is per service**: `DATAOPS_ADMIN` may call anything. Every other caller needs an `OdmServiceAuthFilter` registered for the service in the `OdmService` enum (MARKETPLACE, BLUEPRINT, REGISTRY, DEVOPS, POLICY). **No filter exists for marketplace executors**, so today only `DATAOPS_ADMIN` could reach the executor through the proxy.
  - Filters are chains of `FilterValidationHandler`, for example `MarketplaceServiceAuthFilter`: MARKETPLACE_ADMIN bypass → request-submission handler → generic write (MARKETPLACE_EDITOR) → generic read. A chain where no handler matches throws `IllegalStateException`. They are tested by `*AuthFilterIT` with `OdmServiceClientMock`.
  - **Forwarding** (`OdmServiceClientImpl.forwardRequest`):
    - The target URL is `servicesURLs[service]` + every path segment after `{service}` + the query string, so the executor path is preserved.
    - The version is inferred only from `/api/v{n}/pp/{service}`; executor paths (`/api/v1/up/...`) fall back to v1.
    - **All incoming headers are forwarded unchanged**, including the caller's `X-BD-User` and `X-BD-ApiKey`. Only `x-odm-ag-*` secret placeholders are rewritten.
- **blindata-agent ODM marketplace filters** (`services/odm/filters/v1/marketplace/`): in Blindata deployments, submissions to the ODM marketplace go through the agent. `MarketplaceAuthHandlerRequestSubmission` requires the **calling user's roles** and a **marketplace role responsibility on the provider data product**. Submission is therefore user-bound, not service-bound.
- **blindata-api MarketplaceAccessRequest**:
  - Has its own lifecycle (`DRAFT, PENDING, DENIED, APPROVED, REVOKED, UNSUBSCRIBED`) and per-port `GrantStatus`.
  - Use-case endpoints: `/requests/{uuid}/revoke` and `/unsubscribe`, plus `PATCH /requests/*/update/ports`.
  - Authentication is by API key (`X-BD-User`, `X-BD-ApiKey`, application user only), JWT or tenant OAuth2, with MARKETPLACE_VIEWER, EDITOR and ADMIN roles.
- **blindata-api WebHooks**:
  - Per-tenant Mustache request templates and match rules on `MARKETPLACE_ACCESS_REQUEST` CRUD events (CREATED or OVERWRITTEN), with attribute matching.
  - Payloads are flattened to dot paths, so arrays cannot be iterated.
  - Delivery is async, fire-and-forget: **no retry, no signing, no delivery log**.
- **odm-platform-adapter-marketplace-emailsender**: a sibling executor that already uses **user-editable Mustache templates** loaded from the classpath or the filesystem, with `{{data.request.*}}` variables. This is a UX precedent for "configurable content structure".

### New Concepts Required

- **ServiceNow Ticket (Fulfilment Resource)**: the record created on ServiceNow for each marketplace request and operation. It is the external unit of work that covers **all ports of the request at once**, and its completion determines the operation result.
- **Creation Interface**: the ServiceNow inbound interface a profile uses to create the ticket. All interfaces must be supported from day one:
  - **Table API**: any table, such as incident, sc_task, change_request or custom `u_` tables.
  - **Service Catalog API**: ordering a catalog item with variables, which produces a REQ, an RITM and tasks under the customer's catalog workflow.
  - **Import Set API**: writes a staging table, and the customer's transform map creates the target record.
  - **Scripted REST API**: a customer-defined endpoint, treated as a configurable generic HTTP call.

  Each interface differs in its request shape, in where the correlation is stored, in which record identifies the created ticket in the response, and in which record later fires the completion webhook.
- **Ticket Profile (per-operation configuration)**: one profile per operation, as confirmed by the product owner. It defines the creation interface, the target (table, catalog item, import set table or endpoint), the field or variable map built from MarketplaceRequest data, the correlation target, the response-extraction rules and the outcome rules.
- **Ticket Reference**: the identity of the created ticket extracted from the interface response: number, sys_id, table, and a deep link built from the instance URL. It is reported in the `PENDING` message and reused in final messages.
- **Correlation Reference**: the binding between the marketplace request UUID and the ServiceNow record. It is written into the ticket and read back from the webhook, and it replaces executor-side state.
- **Request Filter**: configurable criteria that decide whether this executor instance handles a marketplace request. It is needed because several executors may be active and the Marketplace Service fans out to all of them.
  - Confirmed criteria: operation, provider data product FQN, port FQNs, consumer type and identifier, requester, and `properties` values.
  - Patterns are **regular expressions** (full match), per product owner decision.
  - **Exclusive filters** across co-existing executors are the accepted operating rule.
- **Requester Resolution** (Service Catalog): the marketplace requester (type `user`, identifier = email) must be resolved to a ServiceNow `sys_user` record, so the catalog order is placed **on behalf of the requester** (`requested_for`), per product owner decision.
- **Completion Webhook (inbound callback)**: a new executor endpoint that ServiceNow calls when a ticket reaches a relevant state. It carries the correlation reference, the outcome fields and the descriptive fields (resolution and close notes, closing user, number).
  - It is **reached through the blindata-agent proxy**, which authenticates and authorizes ServiceNow.
  - The executor performs no authentication of its own on this endpoint.
- **Outcome Mapping Rules**: configurable, ordered rules per operation that translate ServiceNow ticket fields into a MarketplaceRequestStatus. The **set of allowed target statuses is restricted by operation** (see the business rules).
- **Result Message Composition**: configurable, template-driven text that describes the grant process for each outcome. It draws on the ticket number and link, resolution and close notes, the closing user, and the outcome cause, and it distinguishes a ServiceNow-side denial from a technical error.
  - The **closing user is included by default, and its inclusion is configurable** (product owner decision).
  - When disabled, the closing-user value is not exposed to templates at all.
- **ServiceNow Connection**: the instance URL plus the **ServiceNow application credentials** configured in the executor and used for every outbound call, whatever the creation interface.
  - Best-practice mechanism (product owner: "use best practice and suggested auth mechanism"): **OAuth 2.0 client credentials grant**.
    - ServiceNow inbound support since the Washington DC release, designed for back-end, user-less integrations.
    - The executor holds a client id and secret of a ServiceNow OAuth application bound to a dedicated integration user (*OAuth Application User*).
    - It obtains short-lived access tokens from `{instance}/oauth_token.do`, caches them until expiry, and sends them as `Authorization: Bearer`.
    - This reuses the starter's existing `AuthenticatedRestUtilsTemplate` and `Oauth2` support, which already handle token caching and refresh.
  - A **static API key** (`x-sn-apikey`, API Key and HMAC token-based auth) remains available as an alternative mode, for instances where client credentials cannot be enabled. ServiceNow positions API keys mainly for webhook-style callers, so it is not the default.
- **Agent Webhook Route**: the blindata-agent proxy route through which ServiceNow reaches the Completion Webhook. It has three parts, configured on the Blindata side, not in the executor:
  - a `servicesURLs` entry pointing to the executor
  - Blindata credentials held by ServiceNow (an application user)
  - an authorization rule allowing that user to call the executor
- **Marketplace Executor Auth Filter** (blindata-agent, **in scope**, product owner decision): a new `OdmServiceAuthFilter` for a new `OdmService` entry `marketplace-executor`. It is least-privilege:
  - it allows only `POST …/servicenow/ticket-events` for users holding MARKETPLACE_EDITOR (MARKETPLACE_ADMIN bypass, as in the marketplace filter)
  - it **denies every other executor path**, notably `POST …/requests`, which would otherwise let a caller inject access requests while bypassing the Marketplace Service.

  It is developed in the blindata-agent repository with its own canvas.
- **Agent prerequisite**: blindata-agent is a **hard prerequisite** for the webhook leg. Standalone ODM deployments must provide their own authenticating gateway in front of the executor (documented, not implemented).
- *(Nice-to-have)* **Inbound Source / Result Sink abstraction**: lets a Blindata channel be added later without touching the ticketing core.

### Key Business Rules

- **Correlation is mandatory**: every created ticket, through any interface, must carry the marketplace request UUID in a place the webhook can return verbatim. A webhook without a resolvable UUID must not produce a result. *Governs: Ticket, Correlation Reference, Completion Webhook.*
- **The UUID, not the business identifier, is the key**: results are posted to `/requests/{uuid}/results`. The business identifier may appear in the ticket for humans only. *Governs: Correlation Reference.*
- **Request filtering happens before any side effect**:
  - A request that does not match the filter is **declined**. No ticket is opened and no result is posted.
  - It is declined with a non-2xx response, so the Marketplace Service counts it as not handled and can still detect "no executor handled the request".
  - The filter is evaluated on the request **as a whole**, because partial grants are forbidden. *Governs: Request Filter.*
- **No partial grants**: a ticket covers all ports of the request, and every result reports **all requested ports** of the provider data product. *Governs: MarketplaceResult, Ticket.*
- **Two-phase result**:
  - When ticket creation succeeds, the executor reports `PENDING` with the ticket reference.
  - When the filter accepted the request but ticket creation fails, it reports `ERROR`.
  - The final status comes only from the webhook.
  - *Governs: MarketplaceResult, ProcessMarketplaceRequest.*
- **Operation-specific status taxonomy**:

  | Operation | Ticket opened | Fulfilled on ServiceNow | Refused on ServiceNow | Technical failure (executor, ServiceNow, marketplace) |
  |---|---|---|---|---|
  | `MARKETPLACE_SUBSCRIBE` | `PENDING` | `GRANTED` | `DENIED` | `ERROR` |
  | `MARKETPLACE_UNSUBSCRIBE` | `PENDING` | `REVOKED` | `ERROR` (message must state that the revoke was refused on ServiceNow, with reason, ticket and who) | `ERROR` (message must state the technical cause) |

  `DENIED` is valid only for SUBSCRIBE, `REVOKED` only for UNSUBSCRIBE, and `GRANTED` only for SUBSCRIBE. A configuration that maps an operation to a status outside its allowed set is invalid. *Governs: Outcome Mapping Rules.*
- **Descriptive messages**:
  - Every result message describes the grant process: outcome, ticket number and link, resolution and close notes, and the ServiceNow user who closed it (see the privacy risk).
  - `ERROR` messages must let a reader tell a ServiceNow-side refusal from a platform failure.
  - *Governs: Result Message Composition.*
- **First final status wins**: once a final status (`GRANTED`, `DENIED`, `REVOKED`, `ERROR`) has been reported for a request, later webhooks for the same request, such as a reopened and re-closed ticket or a repeated Business Rule, are acknowledged and ignored. *Governs: Completion Webhook.*
- **Non-terminal transitions are not results**: ticket updates that match no outcome rule are acknowledged and ignored. *Governs: Outcome Mapping Rules.*
- **Provider consistency** (enforced by the Marketplace Service): the result's provider FQN must equal the request's and its ports must be a subset. The executor obtains them authoritatively from the Marketplace Service, not from the webhook. *Governs: MarketplaceResult.*
- **Only authenticated callers may complete requests**: the webhook endpoint changes access-grant state.
  - Authentication and authorization are **delegated to the blindata-agent proxy**.
  - The executor's webhook must therefore be reachable **only through the agent**, by network isolation. It must never be exposed directly to ServiceNow or the internet.
  - *Governs: Completion Webhook, Agent Webhook Route.*
- **Every outbound ServiceNow call uses the configured application credentials** (OAuth 2.0 client credentials by default, API key alternatively): no per-user or delegated credentials.
  - Tickets are created by the integration user, and that user must be authorized for every creation interface used by the configured profiles, plus `sys_user` read for requester resolution.
  - Catalog orders are placed **on behalf of the requester** (`requested_for`).
  - *Governs: ServiceNow Connection, Requester Resolution.*
- **Request invariants stay as they are**: required fields and `startDate <= endDate` remain enforced before any ticket is opened.

---

## Strategic Approach

### Solution Direction

Turn the starter's **"execute and report"** use case into a **"filter, open ticket, await callback"** flow. Keep the hexagonal and use-case conventions in `spdd/norms/` (use case + command + ports + factory; composed-method structure).

1. **Outbound leg**:
   - The Marketplace Service sends `POST /requests` to the executor, which validates the request and evaluates the request filter.
   - A non-matching request is declined synchronously with a non-2xx response.
   - A matching request gets `202 Accepted`. Asynchronously, the executor selects the operation's profile, renders the content and calls the profile's creation interface (Table, Catalog, Import Set or Scripted REST).
   - The executor extracts the ticket reference from the response and reports `PENDING` with a descriptive message and link, or `ERROR` on failure.
2. **Inbound leg**:
   - On ticket state change, a ServiceNow Business Rule or Flow calls the **blindata-agent proxy** with Blindata credentials.
   - The agent authenticates and authorizes the call and forwards it to the executor's webhook endpoint.
   - The executor extracts the correlation UUID and outcome fields, and loads the access request from the Marketplace Service (authoritative provider, ports and operation).
   - It enforces "first final status wins", evaluates the outcome rules within the operation's allowed statuses, composes the descriptive message and posts the final result.
3. **Configuration**:
   - Typed Quarkus YAML config for the connection (instance URL + OAuth2 client credentials or API key), the filter, per-operation profiles, outcome rules and message templates. There is no webhook security config in the executor.
   - External user-editable templates, following the email-sender precedent.
   - Everything can be overridden through environment variables and mounted files.
   - The configuration is validated at startup (fail fast), including the per-operation status restrictions.
4. **Documentation**:
   - The README is rewritten from "starter" to "ServiceNow executor".
   - It covers executor configuration and the **ServiceNow-side setup for each creation interface**: OAuth application (client credentials) or API key, integration user roles and access policies, the correlation field, variable or transform mapping, and the Business Rule or Flow calling the agent proxy, with sample scripts and payloads.
   - It covers the **blindata-agent proxy setup** for the webhook route, and request filtering for multi-executor deployments, with sequence diagrams for both legs.
   - It includes **one basic scenario per creation interface** (Table API, Service Catalog, Import Set, Scripted REST). Each is a self-contained, copy-paste walkthrough with:
     - the **base executor configuration** (connection, a SUBSCRIBE and an UNSUBSCRIBE profile, default outcome rules and messages)
     - the minimal ServiceNow-side objects to create
     - the agent route
     - an example request → ticket → webhook → result run

The executor stays **stateless**: the ticket carries the correlation, and the Marketplace Service is the source of truth for request details and prior results.

### Key Design Decisions

- **Source of the final status: ServiceNow push (webhook) vs. polling** → **Webhook push only**, as the requirement asks. Polling is noted as possible future work.
- **How the webhook identifies the request: echo vs. lookup** → **Lookup in the Marketplace Service** by UUID. The webhook payload carries only the correlation UUID, the outcome fields and the descriptive fields. This keeps ServiceNow scripts trivial and makes it easy to report all ports, as required by the no-partial-grants rule.
- **Supporting all creation interfaces from day one**: interfaces differ in payload, correlation placement and response shape. Options:
  - (a) Hard-code each interface.
  - (b) Treat every interface as a *configurable HTTP call* (path, method, body or field map, response-extraction paths).
  - (c) Typed interface "kinds" with sensible built-in defaults, each still overridable.

  → **(c)**: Table, Catalog, Import Set and Scripted REST kinds share one profile model. Each kind supplies defaults for the endpoint, the payload envelope (for example, Catalog `variables`) and response extraction. The Scripted REST kind is fully user-defined. This gives "all interfaces" without a combinatorial matrix of code paths.
- **Where to store the correlation UUID**: the **default depends on the interface, and the target is configurable**:
  - Table API: the standard `correlation_id` field of task-derived tables.
  - Catalog: a catalog item variable, which the webhook reads from the RITM or the task.
  - Import Set: a staging column that the customer's transform map copies to the target record.
  - Scripted REST: wherever the customer's endpoint expects it.

  The UUID is also always included in the human-readable text.
- **Which record fires the completion webhook**: for Catalog and Import Set, the record the executor creates (REQ or staging row) is not the record that is fulfilled (RITM, task, or the transformed target). → The executor does not care which record calls it, as long as the correlation UUID is in the payload. The README documents, per interface, where to attach the Business Rule.
- **Content templating approach** → **Field map with per-field template expressions**, serialized by the executor, which guarantees valid JSON. Long texts can reference external template files.
- **Template engine** → **Quarkus Qute** (product owner decision: "best fit for Quarkus").
  - **Why:** it is part of the Quarkus platform (`quarkus-qute`, version managed by the Quarkus BOM), so it adds no foreign dependency. It is designed for native images, which keeps the `-Pnative` build working without reflection workarounds that a Mustache library would need. It is also the engine the Quarkus ecosystem maintains.
  - **Consequences to carry into the Canvas phase:**
    - **Runtime parsing**: user templates come from configuration and external files, so they are parsed at runtime through the Qute `Engine`. Qute's build-time type-safe validation therefore does not apply. Template errors must instead be caught by **startup validation** (parse every configured template when the service boots and fail fast).
    - **Data model exposure**: template data should be exposed as maps or Qute-resolvable types (for example records annotated for template data), so that property access works in native mode without reflection gaps.
    - **Syntax differs from Mustache**:
      - Qute uses single braces (`{request.provider.dataProductFqn}`, `{#for}`, `{#if}`), not `{{…}}`.
      - Users coming from the email sender or Blindata webhooks need README examples.
      - Literal braces in values or templates (for example JSON fragments in Scripted REST bodies) need escaping or unparsed sections.
      - The field-map approach keeps most templates brace-free.
    - **Escaping**: no HTML escaping should apply to rendered field values, because these are plain strings later serialized to JSON by Jackson. The engine must be configured accordingly.
    - **Missing values**: Qute's behaviour on unresolved expressions (it fails or prints a "not found" marker, depending on configuration) must be configured to render an empty value or a sensible default, so incomplete requests do not break ticket creation.
- **Outcome determination** → **Configurable, ordered rules per operation**. Each rule is a condition on webhook fields that maps to a status plus a message template; the first match wins, and no match means acknowledge and ignore. Each rule also carries an *outcome cause* (fulfilled, refused or failed), so the UNSUBSCRIBE "refused → `ERROR`" case keeps a distinct, descriptive message. Defaults ship for incident, sc_task and RITM.
- **Result message composition** → **template-driven per outcome** with sensible defaults:
  - The `PENDING` message gives the ticket number, the link and "awaiting fulfilment".
  - The final message gives the outcome, ticket number and link, resolution and close notes, and the closing user.
  - The `ERROR` message names the cause category (refused on ServiceNow vs. technical failure) and the detail.

  The closing user is included in the default template but can be removed (see the privacy risk). The link is built from the instance URL and the ticket table and sys_id.
- **Request filtering behaviour**:
  - *Accept with `202` and silently ignore* hides the fact that nobody handled the request.
  - *Decline with non-2xx* uses the Marketplace Service's existing "not handled" semantics, so a request that no executor wants fails fast at submission instead of staying `PENDING` forever.

  → **Decline with a dedicated non-2xx status and a clear error body.** Filter semantics: all configured criteria must match (AND); within a list criterion any value may match (OR); for port criteria **every** requested port must match, because a request that only partly belongs to this executor cannot be partially granted. An empty filter means "handle everything", which keeps single-executor deployments simple.
- **Enforcing "first final status wins" without state**:
  - (a) Check the Marketplace Service response history for a final status on the UUID. It is simple, but ExecutorResponse has **no executor attribution**, so when several executors co-handle the same request, another executor's final result would wrongly suppress ServiceNow's.
  - (b) Write a "result reported" marker back on the ServiceNow ticket. It is exact per ticket, but needs write access and per-interface knowledge of the fulfilled record.
  - (c) Rely on the Business Rule condition firing once. This is weak.

  → **(a) as the default guard**, plus a documented Business Rule condition (fire on transition into a closed state). Requests must be handled **exclusively** by one executor, through request filters; the product owner accepted this as the operating rule, so executor attribution in the marketplace server is not required. Writing a confirmation work note back to the ticket is not in scope.
- **Initial `PENDING` result** → **Send it**, with the ticket reference. The observer ignores `PENDING`, and the marketplace history gains traceability.
- **Webhook authentication** → **delegated to the blindata-agent proxy** (product owner decision). The executor has no inbound auth.
  - Agent authorization: a **dedicated executor auth filter in blindata-agent, developed as part of BDMD-5340** (product owner decision).
  - `DATAOPS_ADMIN` is not to be used for the ServiceNow identity.
- **Outbound ServiceNow authentication** → **OAuth 2.0 client credentials** as the default, with **API key** as an alternative mode (product owner: "best practice").
  - Client credentials gives short-lived bearer tokens, revocable OAuth application registration, and an explicit integration user, and it is reused from the starter's `AuthenticatedRestUtilsTemplate`.
  - The API key mode exists for instances or releases where client credentials is unavailable or disabled (it is off by default on ServiceNow and enabled through a system property).
  - Secrets come from environment variables or mounted secrets and are never committed or logged.
- **Profile granularity** → **One profile per operation**, confirmed by the product owner. Routing variations (assignment group, category) are expressed inside templates using request data and `properties`.
- **Repository identity** → **Rename the artifact and service name** to ServiceNow. Renaming the Java package is deferred to the Canvas phase, because of the trade-off with template-sync tooling (`template.yaml`).

### Nice-to-have: Blindata-direct mode (blindata-api webhooks ↔ executor ↔ ServiceNow)

**Findings:**

- **The ODM path already reaches Blindata.** A result posted to the ODM marketplace flows through the notification service and the Blindata observer to `PATCH /update/ports`. In ODM deployments, the Blindata-direct mode adds nothing.
- **Its value is for Blindata-only deployments** (Blindata marketplace without the ODM platform).

**Direct `blindata-api ↔ ServiceNow` with no executor** is technically possible but brittle:

- Blindata webhooks flatten the payload (ports cannot be iterated), support only static auth headers, have no retries, and `OVERWRITTEN` fires on every lifecycle save.
- The return path would put status mapping, per-port payload construction and a Blindata API key inside every customer's ServiceNow.
- None of the new rules would apply: request filtering, the operation-specific taxonomy, descriptive messages and first-final-wins.

**Through the executor**, the same flows gain:

- centralized profiles, rules and messages
- proper ServiceNow auth
- a **thin-event + fetch** pattern: the Blindata webhook carries only the request UUID, and the executor reads the full request through `GET /api/v1/marketplace/requests/{uuid}` with an application API key
- reporting all ports through `/update/ports`

**Recommendation:**

- It is **genuinely useful only for Blindata-without-ODM customers**. **Defer it**, and keep the core channel-agnostic: ticketing core plus an inbound source port and a result sink port, with ODM as the first adapter.
- Prerequisites for the follow-up story:
  - verifying the blindata-api webhook payload serialization for `MARKETPLACE_ACCESS_REQUEST` (back-reference recursion is suspected)
  - defining the trigger transitions: `APPROVED` → subscribe ticket, and `REVOKED` or `UNSUBSCRIBED` → unsubscribe ticket
  - provisioning a Blindata application user with MARKETPLACE_EDITOR

### Out of Scope: Automatic revoke on access expiry (`endDate`)

Confirmed out of scope for BDMD-5340. Below is how it could be managed later.

**Principle**: expiry should create a **normal `MARKETPLACE_UNSUBSCRIBE` access request in the system that owns the request lifecycle**. The ServiceNow executor then handles it like any other unsubscribe: filter, revoke ticket, `REVOKED` or `ERROR`. The executor stays stateless and needs no new trust relationships. What matters is *who* detects expiry and *which identity* submits the unsubscribe.

**Authentication layers involved:**

| # | Hop | Current authentication | Implication for automatic revoke |
|---|-----|------------------------|----------------------------------|
| L1 | Marketplace Service → executor | none | unchanged |
| L2 | Executor → ServiceNow | OAuth 2.0 client credentials (or API key) of a ServiceNow integration user, configured in the executor | unchanged. Revoke tickets are created by the same integration user. |
| L3 | ServiceNow → blindata-agent proxy → executor (webhook) | Blindata credentials checked by the agent, plus a per-service authorization filter | unchanged |
| L4 | Executor → Marketplace Service | none in standalone ODM | an executor-initiated submission would be unauthenticated and unaudited |
| L5 | Blindata UI/user → blindata-agent → ODM marketplace | **user-bound**: user roles + marketplace role responsibility on the provider (`MarketplaceAuthHandlerRequestSubmission`) | a scheduled, unattended submission has **no user session**. It needs a service identity granted marketplace responsibility on **every** provider. |
| L6 | Client → blindata-api | API key (application user only), JWT, or tenant OAuth2; roles MARKETPLACE_* | system-initiated revokes need an application user with MARKETPLACE_EDITOR, audited as "system" |

**Options:**

- **(A) Lifecycle owner schedules expiry (recommended).**
  - The system where the access request lives runs a scheduled job that finds approved requests past `endDate` and issues the unsubscribe. In Blindata deployments that is blindata-api; in standalone ODM it is the Marketplace Service.
  - In Blindata, the job runs server-side under a technical identity. It moves the request to `REVOKED`, and the unsubscribe must reach ODM, which today is triggered by the UI through the agent (L5). This requires either a server-side submission path from blindata-api or the agent to ODM with a **service identity exempt from user-bound responsibility checks**, or a system role that holds them.
  - Only long-lived, non-user credentials can be used (client credentials or an API key). The requester's tokens will have expired months before `endDate`.
  - The executor needs no change.
- **(B) The ServiceNow executor schedules expiry.**
  - The executor would need state, or a marketplace query by `endDate`, which **does not exist** (search supports only identifier and operation).
  - It would also have to *submit* an unsubscribe rather than post a result, because results can only be attached to an existing request UUID. In Blindata deployments that submission must pass through L5 with a service identity; in standalone ODM it relies on the unauthenticated L4.
  - It breaks statelessness and puts lifecycle ownership in an adapter. **Rejected.**
- **(C) ServiceNow schedules expiry.**
  - `endDate` is written into the subscribe ticket, and a ServiceNow scheduled job or Flow opens a revoke task when it expires, then notifies the executor.
  - The marketplace would still need a new unsubscribe request created by someone, and ServiceNow would need marketplace credentials (L4, L5 or L6). That pushes lifecycle logic and secrets into each customer's ServiceNow. **Rejected as default.** It could become an optional pattern for customers whose access policy mandates ServiceNow-side certification campaigns.

**What BDMD-5340 should do now to keep (A) possible:**
- Include `startDate` and `endDate` in the default ticket templates, for visibility to fulfillers.
- Make sure unsubscribe handling does not depend on the original subscribe ticket: no ticket linkage is required.

---

### Alternatives Considered

- **Keep the starter's synchronous model** (report `GRANTED` immediately): rejected. It reports access as granted before anyone fulfils it.
- **A stateful executor** (database mapping ticket ↔ UUID, executor attribution, expiry scheduling): rejected for the MVP. Correlation on the ticket plus a Marketplace Service lookup are sufficient. It would be reconsidered only if exact first-final-wins across co-handling executors becomes mandatory and the marketplace cannot add executor attribution.
- **Accept-and-ignore for filtered-out requests** (`202` with no result): rejected. It defeats the Marketplace Service's "no executor handled the request" safeguard and leaves requests `PENDING` forever.
- **ServiceNow posting directly to the Marketplace Service results endpoint**: rejected. Mapping, message composition and first-final-wins would live in ServiceNow scripts, and the unauthenticated marketplace endpoint would be exposed to ServiceNow.
- **Supporting only Table and Catalog APIs**: rejected by the product owner, who asked for all interfaces from day one. Import Set and Scripted REST are included through the typed-interface-kinds model.
- **One interface implementation per ServiceNow API with no shared model**: rejected. It would duplicate correlation, response-extraction and templating logic four times.
- **ServiceNow IntegrationHub spokes or scoped apps shipped by us**: rejected. They require licensing and a ServiceNow release cycle. The README documents script-based Business Rules, and Flow REST steps for customers who have IntegrationHub.

---

## Risk & Gap Analysis

### Requirement Ambiguities

All questions from rounds one to three are resolved (see "Requirement Clarifications"). Resolution summary:

| Topic | Resolution |
|---|---|
| All ServiceNow interfaces | Confirmed: Table, Service Catalog, Import Set and Scripted REST. |
| Catalog ordering | `requested_for` resolves to the marketplace requester. The requester email is looked up in `sys_user` (match field configurable, default `email`). An unresolvable requester → `ERROR` with a descriptive message. Two-step checkout is handled by submitting the cart. |
| Filter criteria | Proposed list confirmed. **Regex** (full match) patterns. All ports must match. |
| Closing user | Included by default; inclusion is configurable. |
| Co-handling executors | Exclusive filters are the accepted operating rule. Executor attribution in the marketplace server is not required. |
| Agent authorization | A new executor auth filter in blindata-agent is **in scope** (separate canvas in that repository). |
| Standalone deployments | blindata-agent is a hard prerequisite; standalone deployments bring their own gateway (README note). |
| ServiceNow credentials | OAuth 2.0 client credentials (default) + API key (alternative). |

Residual items (not blocking, handled in design):
- **Minimum ServiceNow release**: client credentials and API key inbound auth are documented from the Washington DC release. Older instances are not supported for outbound auth. → To be stated in the README.
- **The requester `sys_user` lookup needs read access** to `sys_user` for the integration user (an ACL plus an API access policy for the Table API GET). → To be stated in the Catalog scenario.

### Edge Cases

- **Webhook for an unknown or foreign UUID**, or a missing correlation: acknowledge and ignore with a log entry. Never post a result.
- **Webhook for a request this executor declined** (filter mismatch; the ticket was created by another process): it must be ignored. The executor re-evaluates the filter, or checks for its own `PENDING`, before posting.
- **Duplicate or out-of-order webhooks, and reopen/re-close**: first final status wins. Concurrent duplicate webhooks can both pass the history check (no locking); the window is small but real.
- **Webhook before the `PENDING` result is stored or the marketplace commit is visible**: ordering in the append-only history matters, and the first-final-wins check may race.
- **Marketplace transaction not yet committed**: the executor is called inside the Marketplace Service's transaction, which is why the starter sleeps 2 seconds. The `PENDING` post may get 404 and be silently dropped.
- **The filter matches some ports but not others**: declined as a whole, because partial grants are forbidden. Operators must be told this in the README.
- **All active executors decline**: the marketplace rolls back the submission with "No executor was able to handle the request". This is correct, and it should be documented.
- **Catalog order succeeds but the RITM is created asynchronously or with a different number**: the ticket reference reported in `PENDING` may be the REQ, not the record actually fulfilled.
- **Import Set transform fails or skips the row**: the API may return success while no target record exists. The response-extraction rules must detect transform errors and report `ERROR`.
- **Scripted REST endpoint returns a non-standard shape**: extraction paths are user-defined. A misconfiguration must result in `ERROR`, not in an unreferenced `PENDING`.
- **ServiceNow unreachable, 4xx or 5xx on creation**: `ERROR` with a technical-cause message.
- **Marketplace unreachable during the webhook**: return a non-2xx to ServiceNow so it can retry. Today's result port swallows exceptions, which would lose the final result.
- **Template references missing data, or values with quotes, newlines or excessive length**: rendering must not fail, and field limits such as `short_description` must be respected.
- **Same business identifier with both operations**: correlation uses the UUID only.
- **Unsubscribe while the subscribe ticket is still open**: two concurrent tickets exist. This should be documented, not resolved.
- **An unrecognised ticket state** (custom states): acknowledge with no result, and the request stays `PENDING`.

### Technical Risks

- **Synchronous-success assumption baked into the starter** (use case, tests, README). *Mitigation:* a distinct use case for the callback leg, plus a reworked outbound use case. Keep the `spdd/norms/` conventions.
- **Scope growth from "all interfaces"**: four creation interfaces, each with different correlation placement, response shapes and webhook sources, multiply the configuration and README surface. *Mitigation:* the typed-kind model with shared profile semantics, built-in defaults per kind, startup validation, and one end-to-end README recipe per interface.
- **ServiceNow product variability**: API paths, Catalog behaviour (two-step checkout), Import Set transform responses, and inbound OAuth client credentials or API key support (Washington DC+). *Mitigation:* overridable response pointers, an alternative auth mode, and the minimum release documented. These facts were checked against ServiceNow documentation and community sources during the Canvas phase.
- **No executor attribution on ExecutorResponse**: first-final-wins is only exact under exclusive filtering, which is the accepted operating rule (README).
- **Swallowed result-post failures and no retry**: these lose final outcomes on the inbound leg. *Mitigation:* propagate the failure to the webhook caller (non-2xx) so ServiceNow can retry; keep best-effort only for the `PENDING` post.
- **Hard-coded 2-second commit wait**: a timing assumption under load. *Mitigation direction:* bounded retry on 404 instead of a fixed sleep.
- **Declining with non-2xx may be logged as a warning by the Marketplace Service for every non-matching executor**: this is operational noise. It should be verified that the marketplace's `RestUtils` raises `ClientException` on the chosen status code, so the "not handled" path is taken.
- **Executor webhook has no auth of its own**: any caller that bypasses the agent can forge final results. *Mitigation:* deploy the executor on a private network reachable only by the agent and the Marketplace Service, and state this as a hard deployment requirement in the README.
- **ServiceNow must reach the blindata-agent**: the agent is often deployed inside the customer network, while ServiceNow is SaaS. *Mitigation:* README guidance on exposing the agent proxy route (TLS, IP allow-listing of ServiceNow egress) or using a ServiceNow MID Server.
- **Agent authorization gap**: no `OdmServiceAuthFilter` exists for executors today. *Resolution:* a dedicated `marketplace-executor` filter in blindata-agent is part of BDMD-5340 (separate canvas in that repository). It must be released together with the executor.
- **Agent forwarding (verified)**: the webhook path after `{service}` reaches the executor unchanged, and query parameters are forwarded.
  - **Caller credentials (`X-BD-User`, `X-BD-ApiKey`) are forwarded to the executor.** *Mitigation:* the executor never logs request headers. Stripping credential headers in the agent is general hardening, recommended as a follow-up and out of BDMD-5340.
- **Agent filter must deny by default**: `FilterValidationHandler` chains throw `IllegalStateException` (HTTP 500) when no handler matches. *Mitigation:* the executor filter ends with an explicit deny-all handler (`ForbiddenException`).
- **One credential for all interfaces**: the integration user behind the OAuth application or API key must be authorized (roles, ACLs, API access policies) for every API the profiles use (Table, Catalog, Import Set, Scripted REST), plus `sys_user` read for requester resolution. A missing permission surfaces only at runtime as `ERROR` results. *Mitigation:* per-interface permission instructions in the README scenarios, and descriptive 401/403 messages.
- **Client credentials disabled by default on ServiceNow**: the instance property must be enabled and the OAuth Application User set. *Mitigation:* README setup steps, plus the API key fallback mode.
- **Credential lifecycle**: client secret or API key rotation breaks ticket creation until reconfigured. Access tokens are refreshed automatically. *Mitigation:* clear `ERROR` messages identifying an authentication failure, and documented rotation.
- **Personal data in result messages**: ServiceNow user names and free-text notes propagate to the marketplace history and to Blindata. *Mitigation:* a configurable message template; document what is included.
- **Native-image compatibility** of rule evaluation and of runtime-parsed Qute templates (`-Pnative` build). Qute itself is native-friendly, but property access on non-annotated types in runtime templates can fail only in native mode. *Mitigation:* map- or annotated-record data model, and native-mode tests of the default templates.
- **Template syntax mismatch with sibling tools**: Qute's single-brace syntax differs from the Mustache `{{…}}` that users know from the email sender and Blindata webhooks. *Mitigation:* README syntax primer and complete default templates in each basic scenario.
- **Testing without a ServiceNow instance**: the test dependencies are Quarkus JUnit, Mockito and RestAssured only. Per-interface behaviour must be testable through port mocks; an HTTP-level stub may be worth adding.
- **Silent `PENDING` forever** if the webhook is misconfigured. *Mitigation:* clear logs with the ticket number and UUID. Reconciliation or polling is future work.
- **(Nice-to-have) blindata-api webhook payload serialization**: suspected recursion and the flattened payload must be verified before the Blindata-direct mode is committed to.

### Acceptance Criteria Coverage

The requirement has no numbered ACs, so these are derived one-to-one from its statements and from the clarifications.

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | The user can configure the **resource type** created on ServiceNow, through **all ServiceNow interfaces** from day one | Yes | Table, Service Catalog, Import Set and Scripted REST (confirmed), as typed interface kinds with overridable response pointers. |
| 2 | The user can configure the **content structure** of the created resource | Yes | Field or variable map with Quarkus Qute expressions and external template files, parsed at runtime and validated at startup. |
| 3 | Configurable to adapt to **different ServiceNow processes/practices** (per-operation configuration) | Yes | One profile per operation (confirmed), plus interface choice, correlation target, outcome rules and message templates. |
| 4 | The created resource **contains the marketplace request identifier** | Yes | The marketplace **UUID**, with a correlation target per interface that is configurable. |
| 5 | **Task Opened** flow: access request → executor → task opened on ServiceNow | Yes | `PENDING` with the ticket reference and link, or `ERROR`. Subject to the 2-second commit race risk. |
| 6 | **Task Completed** flow: ServiceNow webhook → executor → marketplace updated with the operation result | Yes | Authenticated webhook, outcome rules, marketplace lookup, all ports reported. |
| 7 | Both flows are **valid for every operation**, with the operation-specific status taxonomy | Yes | SUBSCRIBE → `GRANTED`, `DENIED` or `ERROR`; UNSUBSCRIBE → `REVOKED` or `ERROR` (refusal reported as a descriptive `ERROR`). Enforced by configuration validation. |
| 8 | **No partial grants**: a ticket handles all ports of the request at once | Yes | Results always carry all requested ports, and the filter is evaluated on the whole request. |
| 9 | **Descriptive result messages** (resolution and close notes, ticket number, link, user) | Yes | Template-driven per outcome with defaults. Inclusion of the user name needs confirmation (privacy). |
| 10 | **First final status wins** | Yes | Enforced through the marketplace response history. Exact under the accepted operating rule of exclusive filters. |
| 11 | **Request filtering** so that only certain requests are handled (multi-executor deployments) | Yes | Declined with non-2xx so the marketplace's "not handled" semantics apply. The criteria list needs confirmation. |
| 12 | **README** has a detailed, user-friendly section on configuring the service **and** ServiceNow | Yes | Must cover the webhook Business Rule or Flow, the agent proxy route, filtering, the status taxonomy, message templates and the deployment network requirement. |
| 12a | **README** has a **basic scenario with base configuration for each creation interface** (Table API, Service Catalog, Import Set, Scripted REST) | Yes | Four self-contained walkthroughs: base executor config, minimal ServiceNow objects, token access policy, agent route and an example run. Product facts per interface to be verified against the target release. |
| 15 | **Webhook auth delegated to the blindata-agent proxy, with a new executor auth filter** | Yes | The new `marketplace-executor` filter in blindata-agent (separate canvas) allows only ticket events for MARKETPLACE_EDITOR and denies everything else. Network isolation of the executor is still required. Standalone deployments bring their own gateway. |
| 16 | **Executor → ServiceNow authenticated with the best-practice mechanism** | Yes | OAuth 2.0 client credentials (default, Washington DC+) with token caching; API key as the alternative mode. |
| 17 | **Catalog `requested_for` resolves to the marketplace requester** | Yes | `sys_user` lookup by email (configurable match field). An unresolvable requester → `ERROR`. |
| 18 | **Regex-based request filter** with the confirmed criteria | Yes | Full-match regex; all criteria AND; any pattern in a list OR; all ports must match. |
| 19 | **Configurable inclusion of the closing user** in messages | Yes | Included by default; when disabled, it is not exposed to templates. |
| 13 | *(Out of scope)* Automatic revoke on `endDate` | N/A | The design direction and authentication-layer analysis are in "Out of Scope". BDMD-5340 only keeps that direction possible. |
| 14 | *(Nice-to-have)* Blindata-direct mode | Partial (deferred) | Useful only without ODM. Channel-agnostic core now; Blindata adapters as a follow-up after the blindata-api webhook payload is verified. |
