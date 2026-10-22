import { AdmissionControl } from "./admission.js";
import { buildApp } from "./app.js";
import { HttpAuthenticator } from "./auth.js";
import { loadConfig } from "./config.js";
import { commandsTopic, eventsTopic } from "./contracts.js";
import { Correlator } from "./correlator.js";
import { OrderIdAllocator } from "./ids.js";
import { KafkaBus } from "./kafkaBus.js";
import { OrderIndex } from "./orderIndex.js";
import { OrderService } from "./orders.js";
import { HttpRiskClient } from "./risk.js";
import { DEFAULT_INSTRUMENTS, fetchInstruments, SymbolDirectory, type Instrument } from "./symbols.js";

async function instruments(url: string | undefined, only: string[] | undefined): Promise<Instrument[]> {
  let list = DEFAULT_INSTRUMENTS;
  if (url) {
    for (let attempt = 1; ; attempt++) {
      try {
        list = await fetchInstruments(`${url.replace(/\/$/, "")}/symbols`);
        break;
      } catch (e) {
        if (attempt >= 30) {
          throw e;
        }
        await new Promise((r) => setTimeout(r, 1_000));
      }
    }
  }
  return only ? list.filter((i) => only.includes(i.symbol)) : list;
}

async function main(): Promise<void> {
  const config = loadConfig();
  const symbols = new SymbolDirectory(await instruments(config.referenceDataUrl, config.symbols));
  const bus = new KafkaBus(config.brokers);
  await bus.connect(symbols.symbols().flatMap((s) => [commandsTopic(s), eventsTopic(s)]));
  const orders = new OrderService({
    bus,
    risk: new HttpRiskClient(config.riskUrl),
    symbols,
    admission: new AdmissionControl(config.limits),
    index: new OrderIndex(),
    correlator: new Correlator(config.responseTimeoutMs),
    ids: new OrderIdAllocator(config.instance),
  });
  await orders.start();
  const app = await buildApp({
    orders,
    auth: new HttpAuthenticator({
      baseUrl: config.authUrl,
      jwtSecret: config.jwtSecret,
      jwksUrl: config.jwksUrl,
      issuer: config.jwtIssuer,
      audience: config.jwtAudience,
      internalToken: config.internalToken,
    }),
    sessionCookie: config.sessionCookie,
    symbols,
    authServiceUrl: config.authUrl,
    marketDataUrl: config.marketDataUrl,
    portfolioUrl: config.portfolioUrl,
    upstreams: config.upstreams,
    logger: true,
  });
  const shutdown = async () => {
    await app.close();
    await bus.close();
    process.exit(0);
  };
  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);
  await app.listen({ port: config.port, host: config.host });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
