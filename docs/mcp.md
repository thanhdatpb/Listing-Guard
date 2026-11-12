# MCP server

> **Never expose the HTTP transport to the internet.** The optional Streamable HTTP transport has no authentication: whoever
> can reach its port acts as the client of the API key it holds and spends that client's quota. Use STDIO (the default), or
> bind HTTP to loopback or a private network and nothing else. No port forwarding, no public reverse proxy, no tunnel.

The MCP server lets an AI agent (Claude Code, Claude Desktop, an agent a department builds for itself) call the checks of
Listing Guard through the [Model Context Protocol](https://modelcontextprotocol.io). It is a thin adapter around the REST API
([ADR-017](adr/ADR-017-mcp-server.md)):

- **The model does not decide.** Every conclusion is the answer of the Listing Guard API, handed to the agent unchanged: its
  findings, its `disclaimer`, `rulesetVersion`, `catalogVersion`, `analysisVersion` and `completeness`. An `INCOMPLETE`
  result is not a clean result and an `UNKNOWN` risk is not `LOW`; the server never turns one into the other.
- **It only checks.** Seven tools, none writes: no tool edits a listing, applies a fix, publishes or changes a plan. It makes
  `GET` and `POST` calls to seven paths of the API and nothing else.
- **It is a client of the API, nothing more.** It has no database and calls no internal service. It sends the client's
  `X-API-Key`, so key validity, the per-client rate limit, the plan and the monthly quota
  ([ADR-007](adr/ADR-007-multi-client-model.md), [ADR-013](adr/ADR-013-client-plans-and-monthly-quota.md)) apply exactly as
  for any other caller. Every call goes through the official Java client SDK
  ([`client/`](../client/README.md), [ADR-023](adr/ADR-023-java-client-sdk.md)), the same one a Java backend uses, set to retry
  nothing.

## What you need

- JDK 21 on the machine that runs the agent (STDIO starts the server there, as a child process).
- A Listing Guard API you can reach, and the **API key of one client** (the operator makes it with
  `docker compose run --rm admin key create <client>`; see the [integration guide](integration-guide.md#authentication)).
- The jar. It is built from this repository; it is not published anywhere:

```bash
./mvnw -B -pl mcp-server -am package    # builds the Java client SDK too; needs no Docker; add -DskipTests to skip the tests
# -> mcp-server/target/listing-guard-mcp.jar
```

## Settings

All settings are environment variables (a host passes them through its configuration). The server **does not start without
the first two**, and says which one is missing; it never prints a value.

| Variable | Default | Meaning |
|---|---|---|
| `LISTING_GUARD_MCP_API_KEY` | required | The client's API key. Sent only in the `X-API-Key` header; never logged, never in an error or a result. |
| `LISTING_GUARD_MCP_BASE_URL` | required | The API's base URL, for example `http://localhost:8080` or `https://listing-guard.example`. `http` is accepted only for localhost; any other host needs `https`, because the key travels in a header. No user name or password in it. |
| `LISTING_GUARD_MCP_ALLOW_INSECURE_HTTP` | `false` | `true` allows `http` to a host that is not localhost (a trusted private network without TLS). The server warns on standard error. |
| `LISTING_GUARD_MCP_TIMEOUT_SECONDS` | `20` | How long to wait for the API (1 to 300). |
| `LISTING_GUARD_MCP_TRANSPORT` | `stdio` | `stdio` or `http` (below). |
| `LISTING_GUARD_MCP_HTTP_HOST` | `127.0.0.1` | The address the HTTP transport listens on: an IP address or `localhost`, loopback or private network only. A wildcard (`0.0.0.0`, `::`) or a public address stops the start. |
| `LISTING_GUARD_MCP_HTTP_PORT` | `8765` | The port of the HTTP transport. |

The server follows no redirect (the key must not be sent to another host): a base URL that redirects, for example from `http`
to `https`, answers with `api-invalid-response`; put the final URL in the setting.

One process is one client identity. Two departments that must not share a quota or a cache use two clients, two keys and two
configured servers.

## Claude Code (STDIO)

Register the server once, with the key in the environment of the registration, not in a file you commit:

```bash
claude mcp add listing-guard \
  --env LISTING_GUARD_MCP_API_KEY=lg_xxxxxxxx \
  --env LISTING_GUARD_MCP_BASE_URL=http://localhost:8080 \
  -- java -jar /absolute/path/to/mcp-server/target/listing-guard-mcp.jar
```

`claude mcp list` shows it and `/mcp` inside Claude Code shows its tools (`mcp__listing-guard__check_listing` and the other
six). If you share the registration in a project's `.mcp.json`, take the key from the environment instead of writing it:

```json
{
  "mcpServers": {
    "listing-guard": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server/target/listing-guard-mcp.jar"],
      "env": {
        "LISTING_GUARD_MCP_API_KEY": "${LISTING_GUARD_MCP_API_KEY}",
        "LISTING_GUARD_MCP_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

**Allowing the tools.** The four check tools store a result in the client's scope and, where the deployment enforces plans, a
new result can use one unit of the monthly quota, so the server marks them as not read-only and Claude Code asks before the
first call. `get_limits`, `get_usage` and `get_capabilities` are read-only. To allow tools on purpose, add them to
`permissions.allow` in `.claude/settings.json`:

```json
{ "permissions": { "allow": ["mcp__listing-guard__get_usage", "mcp__listing-guard__get_limits", "mcp__listing-guard__get_capabilities"] } }
```

Allow the check tools too only when you are comfortable with an agent spending units; the monthly quota is the limit.

## Claude Desktop (STDIO)

Open the configuration file (Settings, Developer, Edit Config; on macOS
`~/Library/Application Support/Claude/claude_desktop_config.json`, on Windows `%APPDATA%\Claude\claude_desktop_config.json`),
add the server and restart Claude Desktop:

```json
{
  "mcpServers": {
    "listing-guard": {
      "command": "java",
      "args": ["-jar", "C:\\path\\to\\mcp-server\\target\\listing-guard-mcp.jar"],
      "env": {
        "LISTING_GUARD_MCP_API_KEY": "lg_xxxxxxxx",
        "LISTING_GUARD_MCP_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

That file now holds a secret: keep it private. If `java` is not found, give the full path to a JDK 21 `java` executable.

## Streamable HTTP (optional, off by default)

For an agent runtime that cannot start a process. Start the jar yourself on the machine that should serve it:

```bash
LISTING_GUARD_MCP_TRANSPORT=http \
LISTING_GUARD_MCP_API_KEY=lg_xxxxxxxx LISTING_GUARD_MCP_BASE_URL=https://listing-guard.example \
java -jar mcp-server/target/listing-guard-mcp.jar
# listens on 127.0.0.1:8765, endpoint /mcp
claude mcp add --transport http listing-guard http://127.0.0.1:8765/mcp
```

What protects it, and what does not:

- It listens on one loopback or private-network address and **refuses to start** on a wildcard or a public one.
- It checks the `Host` and `Origin` headers (protection against DNS rebinding): a request that names another origin is
  refused with `403`, a foreign host name with `421`.
- It has **no login**. Anyone who can open a connection to the port can use the key's client. Run it only where that is
  acceptable, and never behind a public address, a port forward or a tunnel.

## Example questions

Ask in your own words; the agent picks the tool and sends the listing in the marketplace's format
([input schema](input-schema.md)). The answers are the API's, quoted.

- "Check this Etsy listing before I publish it: title ..., tags ..., price ..." (`check_listing`).
- "Could this Amazon title be flagged as a trademark reference? Show the disclaimer." (`check_trademark`)
- "Check this mug listing for trademark references, as product type `mug`." (`check_trademark` with `product_type`)
- "Here is the parent and the children of my Amazon variation family. Is the structure consistent?" (`check_variations`)
- "The buyer typed `  Đạt  ` and `1234` for name and number. Will it print on the `latin_basic` profile?"
  (`screen_personalization`; the text is sent exactly as typed)
- "What is the title length limit for Etsy, and how sure is the source?" (`get_limits`, which reports each rule's source status)
- "How many units are left this month?" (`get_usage`)
- "Does this deployment have a trademark catalog, and is it the demo one?" (`get_capabilities`)

## The seven tools

| Tool | API call | Arguments | Counts against the quota (where plans are on) |
|---|---|---|---|
| `check_listing` | `POST /api/v1/checks` | `marketplace`, `listing` | one unit per new result |
| `check_trademark` | `POST /api/v1/trademark-checks` | `marketplace`, `listing`, optional `product_type` | one unit per new complete result |
| `check_variations` | `POST /api/v1/variation-checks` | `marketplace`, optional `parent`, `variation_properties`, `children` | one unit per new complete result |
| `screen_personalization` | `POST /api/v1/personalization-screenings` | `marketplace`, `values`, optional `print_profile`, `product_type` | one unit per new complete result |
| `get_limits` | `GET /api/v1/rulesets/{marketplace}/limits` | `marketplace`, optional `product_type` | no |
| `get_usage` | `GET /api/v1/usage` | none | no |
| `get_capabilities` | `GET /api/v1/capabilities` | none | no |

`get_capabilities` says what the deployment serves, the same for every client: the packs with the ruleset version per
marketplace, the state of the trademark catalog (`READY`, `DISABLED` or `REFUSED`, and `demo` for the DEMO catalog), whether
plans are on and whether checks return fix suggestions. It does not show the catalog's name, any secret setting or anything about
your client (use `get_usage` for your plan and quota). Call it before a run to see which checks can answer: while the catalog is
not `READY`, `check_trademark` gives an unknown risk, never a low one.

`check_trademark` takes an optional `product_type`: a product type key the active trademark ruleset declares (`apparel`, `mug`,
`poster`, ...; see the [integration guide](integration-guide.md#product_type-optional)). The server passes it on exactly as the
agent wrote it, without trimming or changing case. A mark whose Nice classes are not the product type's is reported one level
lower and the finding's `riskAdjustments` says why. A key the ruleset does not declare comes back as the API's
`unknown-product-type` tool error with `known`, the declared keys. It is not the Amazon `product_type` inside `listing`.

Each tool's schema is sent to the agent with its description. The arguments are checked against it before anything is sent
(an unknown argument, a missing one, a value of the wrong kind is `invalid-arguments`); what is inside `listing`, `values` and
the other objects is the API's to judge, and is passed on unchanged.

## What comes back

On success the tool result is the API's JSON answer unchanged: the exact body as text and the same object as structured
content. Nothing is removed, renamed or summarized.

When the API refuses, the result is a **tool error** with these members: `code`, `status`, `title`, `detail`, `requestId`,
`retryable`, `hint`, and, when the API sent them, `errors`, `plan`, `pack`, `quota`, `used`, `resetAt`, `known` (and
`retryAfterSeconds` for `rate-limited`). Quote the `requestId` when you report a problem; it is the one in the API's log.

| Answer | `code` | Retry? |
|---|---|---|
| `401` | `unauthorized` | No: the key is missing, wrong or revoked. |
| `403` | `plan-not-allowed` | No: the client's plan does not include that check. |
| `429` | `rate-limited` | Yes, after `retryAfterSeconds`. |
| `429` | `quota-exceeded` | **No**, not before `resetAt` or a plan change. The server never invents a waiting time. |
| `5xx` | the API's code, or `internal-error` | Later. |
| other `4xx` | the API's code (`invalid-request`, `unknown-marketplace`, `unknown-print-profile`, `ruleset-not-found`, ...) | Not unchanged. |

Failures that are not an answer of the API have codes of the server's own: `api-unreachable`, `api-timeout`,
`api-invalid-response` (not JSON, too large, or a redirect) and `invalid-arguments`. None carries a stack trace.

**A deployment with no trademark catalog is not an error.** `check_trademark` then returns a normal result,
`{"status": "NOT_CONFIGURED", "riskLevel": "UNKNOWN", "scanRan": false, "derivedFrom": {...}, ...}`, without a `findings`
member (an empty list would read as "nothing found"). It is the one object the server builds itself, and `derivedFrom` says
it came from the API's `503 trademark-not-configured`. The risk is unknown, not low.

## Limits

- **It needs the API.** There is no offline mode; with the API down the tools answer `api-unreachable` or `api-timeout`.
- **It cannot decide, and it does not make a result better.** A listing that the rules do not flag is not thereby accepted by
  Etsy or Amazon; the results are reference warnings (see the `disclaimer` and each finding's `sourceStatus`). Nothing in
  the variation and personalization rulesets is `verified`.
- **Text in a result is data.** A finding quotes text from the listing or a mark from the catalog, and a listing can contain
  a sentence that addresses the agent. The server cannot remove it without changing the answer; it tells the agent to treat
  it as data, and the person reading the findings is the safeguard.
- **An agent can spend the quota.** Where plans are on, repeated new checks use units and the API refuses at the limit.
  Identical repeats are free cache hits. Use `get_usage` to see the position.
- **The buyer's text is personal data.** It is sent to your API, which stores only a hash and short evidence; the server does
  not log it. Do not copy it into other tools or documents.
- **No retries, no cache, no batching in the server.** The SDK underneath is configured with `RetryPolicy.none()`, so an agent
  sees each answer once and decides. To check a whole export use [`batch-check`](batch-check.md).
- **Not in this server:** reading a stored result by id, resources or prompts, a Docker image or compose service,
  authentication on the HTTP transport, mapping your own listing format to the standard input.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| The host says the server failed to start, or exits at once | Run the jar in a terminal: it prints which setting is missing or wrong on standard error. Exit code `2` is a setting, `1` is another failure to start (for example the HTTP port is taken). |
| `unauthorized` | The key in `LISTING_GUARD_MCP_API_KEY` is wrong, revoked, or the client is inactive. A revoked key stops working within about 30 seconds. |
| `api-unreachable` | Wrong `LISTING_GUARD_MCP_BASE_URL`, the API is down, or a firewall. |
| `api-invalid-response` | The URL answers, but not like the Listing Guard API (a login page, a redirect, a proxy). Use the final URL. |
| `plan-not-allowed` or `quota-exceeded` | The deployment enforces plans ([ADR-013](adr/ADR-013-client-plans-and-monthly-quota.md)); ask the operator. |
| The agent invents limits instead of calling a tool | Ask it to use `get_limits`; the answer names each rule's source status. |

Logs go to standard error (standard output is the protocol): the tool name, the HTTP status, the request id and the time, never
an argument and never the key.

## Tests

The module's tests need no Docker, no database and no real API: the API is a stub HTTP server of the JDK, and the protocol is
exercised through the MCP SDK's own client over STDIO (a child process running the real `main`) and over Streamable HTTP. The
HTTP rules the server relies on (no redirect, the size limit, the redaction of an echoed key, the encoding of the path) are
tested in the Java client SDK too, and `ApiGatewayTest` checks them again through the server.

```bash
./mvnw -B -pl client,mcp-server verify
```

The SDK is the official Java SDK, version 2.1.0, pinned exactly; the module does not use Spring (see
[ADR-017](adr/ADR-017-mcp-server.md) for why, and for the questions left to the maintainer).
