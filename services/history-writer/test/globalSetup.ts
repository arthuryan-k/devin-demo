import path from "node:path";
import { fileURLToPath } from "node:url";

import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import { RedpandaContainer, type StartedRedpandaContainer } from "@testcontainers/redpanda";
import { GenericContainer, Network, type StartedNetwork, Wait } from "testcontainers";
import type { TestProject } from "vitest/node";

declare module "vitest" {
  export interface ProvidedContext {
    databaseUrl: string;
    kafkaBrokers: string;
  }
}

const MIGRATIONS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../db/migrations");
const REDPANDA_IMAGE = "docker.redpanda.com/redpandadata/redpanda:v24.2.4";

let network: StartedNetwork;
let postgres: StartedPostgreSqlContainer;
let redpanda: StartedRedpandaContainer;

/** One migrated Postgres (same Flyway image and SQL as docker compose) and one Redpanda broker per run. */
export async function setup(project: TestProject) {
  network = await new Network().start();
  const broker = new RedpandaContainer(REDPANDA_IMAGE).start();
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
  redpanda = await broker;
  project.provide("kafkaBrokers", redpanda.getBootstrapServers());
}

export async function teardown() {
  await redpanda?.stop();
  await postgres?.stop();
  await network?.stop();
}
