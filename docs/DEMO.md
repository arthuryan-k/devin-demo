# Devin demo guide

> **Presenter material, not product documentation.** This page is a pitch aid for showing Devin to executives and
> CTOs. The product docs start at the [README](../README.md).

## The setting

**A real platform, with real work to do.**

- A working brokerage and exchange: limit order book matching engine, REST and FIX gateways, risk, a cash/position
  ledger, market data, a simulated market and a React trading workstation.
- A dozen services in Java, TypeScript, Python and Perl on a Kafka-compatible event bus, with Postgres, Docker
  Compose and gated CI.
- The demo walks the whole software lifecycle: net-new feature work first, then targets planted at every later
  stage, so Devin can be shown doing real engineering end to end on code it has not seen before.

---

## At a glance

| | Stage | Target | Where | Devin story |
|---|---|---|---|---|
| 01 | **Plan** | Scope a net-new feature | Devin Wiki, Ask Devin | Understand → scope → plan |
| 02 | **Build** | Implement that feature | Engine, bus schemas, gateway, workstation | Plan → code → PR |
| 03 | **Secure** | Static-analysis security findings | `history-writer`, `auth-service`, `eod-recon` | Scan → find → fix |
| 04 | **Fix** | Reporter-style bug tickets | GitHub Issues | Reproduce → root-cause → fix |
| 05 | **Maintain** | Outdated dependencies and scattered tech-debt markers | Dependency pins, Dependabot PRs, `TODO` / `FIXME` / `HACK` | Triage the backlog |
| 06 | **Modernize** | Legacy Java gateway and a Perl batch job | `fix-gateway`, `eod-recon` | Legacy modernization |

---

![Stage 1 of 6: Plan](assets/stage-1-plan.svg)

## 01 · Plan: scope a new feature

> **The pitch:** an unfamiliar, polyglot codebase, understood and scoped in minutes, before anyone writes code.

| Focus | Detail |
|---|---|
| **Target** | A net-new product feature that cuts across services, for example stop and stop-limit orders (the engine today accepts only limit and market orders) |
| **How** | Devin Wiki for the architecture and service map; Ask Devin for the specifics: how an order travels from the gateway over the bus to the book, and every place that would have to change |
| **Showcases** | Devin turns a one-line product ask into a scoped plan: affected services, contract changes, risks and a test strategy |

---

![Stage 2 of 6: Build](assets/stage-2-build.svg)

## 02 · Build: code the feature

> **The pitch:** the plan from stage 1, delivered as a reviewable PR that passes CI.

| Focus | Detail |
|---|---|
| **Target** | The feature scoped in stage 1, implemented end to end: bus command schema, matching engine, gateway API and the workstation order ticket |
| **Showcases** | Devin writes the code across Java and TypeScript, adds tests, runs the stack, iterates on CI and opens a PR a human can review |

---

![Stage 3 of 6: Secure](assets/stage-3-secure.svg)

## 03 · Secure: scan → find → fix

> **The pitch:** scanner findings go from a report to merged fixes, across three languages.

| Focus | Detail |
|---|---|
| **Targets** | A SQL injection in `services/history-writer`; a timing-unsafe credential comparison in `services/auth-service`; an OS command injection in the legacy Perl/shell batch `services/eod-recon` |
| **How they surface** | CodeQL ([`codeql.yml`](../.github/workflows/codeql.yml)) and the report-only scanners in [`security.yml`](../.github/workflows/security.yml) (npm audit, pip-audit, Semgrep), including the rule set for the Perl batch in [`.semgrep/legacy-batch.yml`](../.semgrep/legacy-batch.yml) |
| **Showcases** | Devin reads scanner output, confirms each finding is real, and lands a tested fix PR per finding |

---

![Stage 4 of 6: Fix](assets/stage-4-fix.svg)

## 04 · Fix: scope and fix bug tickets

> **The pitch:** hand Devin a ticket the way you would hand it to a teammate.

| Focus | Detail |
|---|---|
| **Target** | A set of open bug tickets in [GitHub Issues](https://github.com/arthuryan-k/devin-demo/issues), written from the reporter's side: symptoms, not causes |
| **Showcases** | Devin reproduces the problem, finds the root cause, and ships a fix with a regression test |

---

![Stage 5 of 6: Maintain](assets/stage-5-maintain.svg)

## 05 · Maintain: triage the backlog

> **The pitch:** the backlog nobody wants to own, sorted, scoped and cleared.

| Focus | Detail |
|---|---|
| **Dependencies** | Deliberately downgraded dependency pins with known CVEs (for example `services/market-data/requirements.txt`), plus an open backlog of Dependabot update PRs across npm, pip, Maven, Docker and GitHub Actions ([`dependabot.yml`](../.github/dependabot.yml)) |
| **Tech debt** | `TODO`, `FIXME` and `HACK` markers left across services, for example `services/portfolio/portfolio/consumer.py`, `services/eod-recon/bin/eod_recon.pl` and `services/fix-gateway` |
| **Showcases** | Devin groups and prioritizes the updates (security vs. routine vs. breaking major versions), merges the safe ones with CI green, and turns the debt markers into scoped, prioritized tickets, leaving the risky calls to a human |

---

![Stage 6 of 6: Modernize](assets/stage-6-modernize.svg)

## 06 · Modernize: legacy migration (headline)

> **The pitch:** a multi-file migration that teams postpone for years, planned and delivered as one reviewable PR.

| Focus | Detail |
|---|---|
| **Target** | `services/fix-gateway`, a FIX 4.4 gateway written to Java 5 idioms and pinned to Java 8 in CI: thread per socket, raw collections, a hand-rolled tag=value codec, boxed-number constructors and per-message date formatters |
| **Scope** | Modernize to Java 17 (or port to QuickFIX/J): about 16 source files, plus the build and its CI job |
| **Smaller alternative** | Port the legacy Perl/shell `services/eod-recon` batch to a modern language, keeping its report and clearing-file formats |
| **Showcases** | Devin plans, executes and verifies the migration against the existing tests and the running stack |
