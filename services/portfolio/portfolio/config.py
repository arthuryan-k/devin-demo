"""Environment configuration shared by the portfolio and risk services."""

import os


def database_url() -> str:
    return os.environ.get("DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/brokerage")


def auto_open_accounts() -> bool:
    """Open a ledger account on first use for any participant that has a row in ``public.accounts``."""
    return os.environ.get("AUTO_OPEN_ACCOUNTS", "true").lower() in ("1", "true", "yes")


def market_collar_percent() -> int:
    return int(os.environ.get("MARKET_COLLAR_PERCENT", "5"))


def kafka_bootstrap() -> str | None:
    return os.environ.get("KAFKA_BOOTSTRAP_SERVERS") or None
