import path from "node:path";
import { fileURLToPath } from "node:url";

import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import { GenericContainer, Network, type StartedNetwork, Wait } from "testcontainers";
import type { TestProject } from "vitest/node";

declare module "vitest" {
  export interface ProvidedContext {
    databaseUrl: string;
  }
}

const MIGRATIONS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../db/migrations");

let network: StartedNetwork;
let postgres: StartedPostgreSqlContainer;

/** One Postgres per test run, migrated by the same Flyway image and SQL that docker compose uses. */
export async function setup(project: TestProject) {
  network = await new Network().start();
  postgres = await new PostgreSqlContainer("postgres:16-alpine")
    .withNetwork(network)
    .withNetworkAliases("postgres")
    .withDatabase("brokerage")
    .start();
  await new GenericContainer("flyway/flyway:10-alpine")
    .withNetwork(network)
    .withCopyDirectoriesToContainer([{ source: MIGRATIONS, target: "/flyway/sql" }])
    .withCommand([
      "-url=jdbc:postgresql://postgres:5432/brokerage",
      `-user=${postgres.getUsername()}`,
      `-password=${postgres.getPassword()}`,
      "-connectRetries=10",
      "migrate",
    ])
    .withWaitStrategy(Wait.forOneShotStartup())
    .start();
  project.provide("databaseUrl", postgres.getConnectionUri());
}

export async function teardown() {
  await postgres?.stop();
  await network?.stop();
}
