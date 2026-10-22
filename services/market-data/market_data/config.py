from __future__ import annotations

import json
import os
import urllib.request
from dataclasses import dataclass, field

DEFAULT_SYMBOLS = ("ACME", "GLOBX", "INITD", "NVTA")


@dataclass
class Config:
    brokers: str | None = None
    reference_data_url: str | None = None
    symbols: list[tuple[str, str]] = field(default_factory=lambda: [(s, "0.01") for s in DEFAULT_SYMBOLS])
    max_backlog: int = 1024
    host: str = "0.0.0.0"
    port: int = 8090


def parse_symbols(raw: str) -> list[tuple[str, str]]:
    """`ACME,GLOBX:0.05` -> [("ACME", "0.01"), ("GLOBX", "0.05")]."""
    out = []
    for part in raw.split(","):
        part = part.strip()
        if part:
            symbol, _, tick = part.partition(":")
            out.append((symbol.upper(), tick or "0.01"))
    return out


def fetch_instruments(url: str) -> list[tuple[str, str]]:
    with urllib.request.urlopen(url, timeout=5) as res:  # noqa: S310 - internal URL from config
        body = json.load(res)
    items = body if isinstance(body, list) else body.get("symbols", [])
    return [(str(i["symbol"]).upper(), str(i.get("tickSize", i.get("tick_size", "0.01")))) for i in items]


def load() -> Config:
    cfg = Config(
        brokers=os.environ.get("KAFKA_BROKERS") or None,
        reference_data_url=os.environ.get("REFERENCE_DATA_URL") or None,
        max_backlog=int(os.environ.get("MAX_BACKLOG", "1024")),
        host=os.environ.get("HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8090")),
    )
    if os.environ.get("SYMBOLS"):
        cfg.symbols = parse_symbols(os.environ["SYMBOLS"])
    elif cfg.reference_data_url:
        cfg.symbols = fetch_instruments(cfg.reference_data_url.rstrip("/") + "/symbols")
    return cfg
