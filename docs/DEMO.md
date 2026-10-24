# Devin demo guide

> **Presenter material, not product documentation.** This page is a pitch aid for showing Devin to executives and
> CTOs. The product docs start at the [README](../README.md).

## The setting

**A real platform, with real work to do.**

- A working brokerage and exchange: limit order book matching engine, REST and FIX gateways, risk, a cash/position
  ledger, market data, a simulated market and a React trading workstation.
- A dozen services in Java, TypeScript, Python and Perl on a Kafka-compatible event bus, with Postgres, Docker
  Compose and gated CI.
- The demo walks the whole software lifecycle: net-new feature work first, then a known target at each later stage.
- Every change Devin makes arrives as a pull request; engineers review and merge.
- Each stage ends with a measurable outcome for a PoC on your code. Across all stages: share of Devin PRs merged,
  reviewer time per PR, and cost per merged PR against engineer hours.

---

## At a glance

| | Stage | Target | Where | Devin story |
|---|---|---|---|---|
| 01 | **Plan** (and Onboarding) | Onboard onto an unfamiliar codebase, then scope a net-new feature | DeepWiki, Ask Devin | Onboard → understand → scope |
| 02 | **Build** | Implement that feature | Engine, bus schemas, gateway, workstation | Plan → code → PR |
| 03 | **Secure** | Static-analysis security findings | `history-writer`, `auth-service`, `eod-recon` | Scan → find → fix |
| 04 | **Fix** | Reporter-style bug tickets | GitHub Issues | Reproduce → root-cause → fix |
| 05 | **Maintain** | Outdated dependencies and scattered tech-debt markers | Dependency pins, Dependabot PRs, `TODO` / `FIXME` / `HACK` | Triage the backlog |
| 06 | **Modernize** | Legacy Java gateway and a Perl batch job | `fix-gateway`, `eod-recon` | Legacy modernization |

---

![Stage 1 of 6: Plan](assets/sdlc-stage-1-plan.svg)

## 01 · Plan (and Onboarding): learn the codebase, scope a new feature

> **Outcome:** a written scope for a cross-service change, reviewed by an engineer before any code is written.

| Focus | Detail |
|---|---|
| **Onboarding** | Get a new engineer productive on a dozen services in four languages: DeepWiki for the architecture, service map and how the event bus ties them together |
| **Target** | A net-new product feature that cuts across services, for example stop and stop-limit orders (the engine today accepts only limit and market orders) |
| **How** | Ask Devin for the specifics: how an order travels from the gateway over the bus to the book, and every place that would have to change |
| **Showcases** | Devin turns a one-line product ask into a scoped plan: affected services, contract changes, risks and a test strategy |
| **Measurable outcome** | On your code: a scope for one real roadmap item, measured by engineer sign-off |

---

![Stage 2 of 6: Build](assets/sdlc-stage-2-build.svg)

## 02 · Build: code the feature

> **Outcome:** one PR implementing the stage 1 scope, with tests and green CI.

| Focus | Detail |
|---|---|
| **Target** | The feature scoped in stage 1, implemented end to end: bus command schema, matching engine, gateway API and the workstation order ticket |
| **Showcases** | Devin writes the code across Java and TypeScript, adds tests, runs the stack, iterates on CI and opens a PR a human can review |
| **Measurable outcome** | On your code: that roadmap item implemented, measured by PR merged, review rounds and time to PR |

---

![Stage 3 of 6: Secure](assets/sdlc-stage-3-secure.svg)

## 03 · Secure: scan → find → fix

> **Outcome:** one fix PR per confirmed finding, each with a test, across three languages.

| Focus | Detail |
|---|---|
| **Targets** | A SQL injection in `services/history-writer`; a timing-unsafe credential comparison in `services/auth-service`; an OS command injection in the legacy Perl/shell batch `services/eod-recon` |
| **How they surface** | CodeQL ([`codeql.yml`](../.github/workflows/codeql.yml)) and the report-only scanners in [`security.yml`](../.github/workflows/security.yml) (npm audit, pip-audit, Semgrep), including the rule set for the Perl batch in [`.semgrep/legacy-batch.yml`](../.semgrep/legacy-batch.yml) |
| **Showcases** | Devin reads scanner output, confirms each finding is real, and opens a tested fix PR per finding |
| **Measurable outcome** | On your code: fixes for open scanner findings, measured by findings closed and a clean re-scan |

---

![Stage 4 of 6: Fix](assets/sdlc-stage-4-fix.svg)

## 04 · Fix: scope and fix bug tickets

> **Outcome:** per ticket, a reproduction, a root cause and a fix PR with a regression test.

| Focus | Detail |
|---|---|
| **Target** | A set of open bug tickets in [GitHub Issues](https://github.com/arthuryan-k/devin-demo/issues), written from the reporter's side: symptoms, not causes |
| **Showcases** | Devin reproduces the problem, finds the root cause, and ships a fix with a regression test |
| **Measurable outcome** | On your code: a set of real backlog bugs, measured by tickets closed with regression tests |

---

![Stage 5 of 6: Maintain](assets/sdlc-stage-5-maintain.svg)

## 05 · Maintain: triage the backlog

> **Outcome:** a prioritized update plan, PRs for the low-risk updates, and scoped tickets for the debt.

| Focus | Detail |
|---|---|
| **Dependencies** | Deliberately downgraded dependency pins with known CVEs (for example `services/market-data/requirements.txt`), plus an open backlog of Dependabot update PRs across npm, pip, Maven, Docker and GitHub Actions ([`dependabot.yml`](../.github/dependabot.yml)) |
| **Tech debt** | `TODO`, `FIXME` and `HACK` markers left across services, for example `services/portfolio/portfolio/consumer.py`, `services/eod-recon/bin/eod_recon.pl` and `services/fix-gateway` |
| **Showcases** | Devin groups and prioritizes the updates (security vs. routine vs. breaking major versions), opens PRs for the low-risk ones with CI green, and turns the debt markers into scoped, prioritized tickets, leaving the risky calls to a human |
| **Measurable outcome** | On your code: dependency and debt backlog triage, measured by updates merged and CVEs cleared |

---

![Stage 6 of 6: Modernize](assets/sdlc-stage-6-modernize.svg)

## 06 · Modernize: legacy migration (headline)

> **Outcome:** a migration plan, then the change as one reviewable PR, verified against existing tests and the running stack.

| Focus | Detail |
|---|---|
| **Target** | `services/fix-gateway`, a FIX 4.4 gateway written to Java 5 idioms and pinned to Java 8 in CI: thread per socket, raw collections, a hand-rolled tag=value codec, boxed-number constructors and per-message date formatters |
| **Scope** | Modernize to Java 17 (or port to QuickFIX/J): about 16 source files, plus the build and its CI job |
| **Smaller alternative** | Port the legacy Perl/shell `services/eod-recon` batch to a modern language, keeping its report and clearing-file formats |
| **Showcases** | Devin plans, executes and verifies the migration against the existing tests and the running stack |
| **Measurable outcome** | On your code: one legacy module migrated, measured by existing tests passing with behavior unchanged |

---

## Security and deployment

| Question | Answer |
|---|---|
| Does Devin merge its own changes? | It opens pull requests and is subject to the same branch protections and SDLC policies as any engineer; your engineers review and decide whether to merge. [Source](https://docs.devin.ai/essential-guidelines/sdlc-integration#working-within-existing-engineering-processes) |
| Is our code used for training? | Not for Enterprise customers without express prior written consent. [Source](https://docs.devin.ai/admin/security) |
| Where does it run? | Enterprise Cloud (Cognition-managed), or Customer Dedicated Deployment: a single-tenant VPC connected to your network over AWS PrivateLink or an IPSec tunnel. Customer-managed keys come with Enterprise Assured. [Source](https://docs.devin.ai/enterprise/deployment/overview) |
| Certifications and encryption? | Cognition is SOC 2 Type II certified (since September 2024); data is encrypted in transit and at rest. [Source](https://docs.devin.ai/admin/security#third-party-audits-and-certification) |
| What can it access? | Only the repositories you grant it. Secrets are encrypted at rest and can be scoped to the organization or to a single repository. [Source](https://docs.devin.ai/product-guides/secrets#repo-specific-secrets) |
| Identity and audit? | Enterprise SSO over OIDC or SAML, custom roles mapped to IdP groups, and enterprise audit logs through the API. [Source](https://docs.devin.ai/enterprise/security-access/sso/guide#custom-roles) |
