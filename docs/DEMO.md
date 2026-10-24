# Devin demo guide

> **Presenter material, not product documentation.** This page is a pitch aid for showing Devin to executives and
> CTOs. The product docs start at the [README](../README.md).

## The setting

`devin-demo` is a working brokerage and exchange platform: a limit order book matching engine, REST and FIX
gateways, risk, a cash/position ledger, market data, a simulated market and a React trading workstation. It is a
dozen services in Java, TypeScript, Python and Perl on a Kafka-compatible event bus, with Postgres, Docker Compose
and gated CI.

Like any real codebase, it carries the debt teams live with every day. Specific targets were planted across the
software development lifecycle so Devin can be shown doing real engineering work end to end, on code it has not
seen before.

## At a glance

| SDLC stage | Target | Where | Devin story |
|---|---|---|---|
| Secure | Static-analysis security findings | `history-writer`, `auth-service`, `eod-recon` | Scan → find → fix |
| Maintain | Outdated, CVE-bearing dependencies and an open update backlog | Dependency pins, Dependabot PRs | Triage the backlog |
| Plan | Scattered `TODO` / `FIXME` / `HACK` markers | `portfolio`, `eod-recon`, `fix-gateway` | Triage and scope the backlog |
| Modernize | Legacy Java gateway and a Perl batch job | `fix-gateway`, `eod-recon` | Legacy modernization |
| Fix | Reporter-style bug tickets | GitHub Issues | Scope, reproduce and fix |

## 1. Secure: scan → find → fix

- **Targets:** a SQL injection in `services/history-writer`; a timing-unsafe credential comparison in
  `services/auth-service`; an OS command injection in the legacy Perl/shell batch `services/eod-recon`.
- **How they surface:** CodeQL ([`codeql.yml`](../.github/workflows/codeql.yml)) and the report-only scanners in
  [`security.yml`](../.github/workflows/security.yml) (npm audit, pip-audit, Semgrep), including the repository
  rule set for the Perl batch in [`.semgrep/legacy-batch.yml`](../.semgrep/legacy-batch.yml).
- **Showcases:** Devin reads scanner output, confirms each finding is real, and lands a tested fix PR per finding
  across three languages.

## 2. Maintain: triage the dependency backlog

- **Targets:** deliberately downgraded dependency pins with known CVEs (for example
  `services/market-data/requirements.txt`), plus an open backlog of Dependabot update PRs across npm, pip, Maven,
  Docker and GitHub Actions ([`dependabot.yml`](../.github/dependabot.yml)).
- **Showcases:** Devin groups and prioritizes the updates (security vs. routine vs. breaking major versions), merges
  the safe ones with CI green, and writes up the risky ones for a human decision.

## 3. Plan: triage and scope the tech-debt backlog

- **Targets:** `TODO`, `FIXME` and `HACK` markers left across services, for example
  `services/portfolio/portfolio/consumer.py`, `services/eod-recon/bin/eod_recon.pl` and `services/fix-gateway`.
- **Showcases:** Devin inventories the debt, assesses impact and effort for each item, and turns it into a scoped,
  prioritized set of tickets, before any code is written.

## 4. Modernize: legacy migration (headline)

- **Target:** `services/fix-gateway`, a FIX 4.4 gateway written to Java 5 idioms and pinned to Java 8 in CI: thread
  per socket, raw collections, a hand-rolled tag=value codec, boxed-number constructors and per-message date
  formatters. Modernizing it to Java 17 (or porting it to QuickFIX/J) is a medium-large change spanning about 16
  source files, its build and its CI job.
- **Smaller alternative:** port the legacy Perl/shell `services/eod-recon` batch to a modern language, keeping its
  report and clearing-file formats.
- **Showcases:** a multi-file, multi-step migration that Devin plans, executes and verifies against the existing
  tests and the running stack, delivered as a reviewable PR.

## 5. Fix: scope and fix bug tickets

- **Target:** a set of open bug tickets in [GitHub Issues](https://github.com/arthuryan-k/devin-demo/issues),
  written from the reporter's side: symptoms, not causes.
- **Showcases:** Devin takes a ticket as a teammate would, reproduces it, finds the root cause, and ships a fix with
  a regression test.
