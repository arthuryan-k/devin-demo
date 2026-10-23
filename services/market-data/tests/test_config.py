import io
import json

import pytest

from market_data import config
from market_data.hub import FeedHub, symbol_for, topic_for


def test_parse_symbols_defaults_tick_and_uppercases():
    assert config.parse_symbols("acme, GLOBX:0.05,,") == [("ACME", "0.01"), ("GLOBX", "0.05")]
    assert config.parse_symbols("") == []


def test_load_defaults(monkeypatch):
    for name in ("KAFKA_BROKERS", "REFERENCE_DATA_URL", "SYMBOLS", "MAX_BACKLOG", "HOST", "PORT"):
        monkeypatch.delenv(name, raising=False)
    cfg = config.load()
    assert cfg.brokers is None
    assert [s for s, _ in cfg.symbols] == list(config.DEFAULT_SYMBOLS)
    assert (cfg.max_backlog, cfg.host, cfg.port) == (1024, "0.0.0.0", 8090)


def test_explicit_symbols_win_over_reference_data(monkeypatch):
    monkeypatch.setenv("SYMBOLS", "NVTA:0.005")
    monkeypatch.setenv("REFERENCE_DATA_URL", "http://unreachable.invalid")
    monkeypatch.setattr(config, "fetch_instruments", pytest.fail)
    assert config.load().symbols == [("NVTA", "0.005")]


def test_symbols_come_from_reference_data_when_not_pinned(monkeypatch):
    monkeypatch.delenv("SYMBOLS", raising=False)
    monkeypatch.setenv("REFERENCE_DATA_URL", "http://refdata:8082/")
    seen = []

    def fake_urlopen(url, timeout):
        seen.append(url)
        body = {"symbols": [{"symbol": "acme", "tickSize": "0.01"}, {"symbol": "NVTA", "tick_size": "0.005"}]}
        return io.BytesIO(json.dumps(body).encode())

    monkeypatch.setattr(config.urllib.request, "urlopen", fake_urlopen)
    assert config.load().symbols == [("ACME", "0.01"), ("NVTA", "0.005")]
    assert seen == ["http://refdata:8082/symbols"]


def test_reference_data_may_be_a_bare_list(monkeypatch):
    monkeypatch.setattr(config.urllib.request, "urlopen", lambda url, timeout: io.BytesIO(b'[{"symbol": "GLOBX"}]'))
    assert config.fetch_instruments("http://x/symbols") == [("GLOBX", "0.01")]


def test_topic_names_round_trip():
    assert topic_for("ACME") == "marketdata.ACME"
    assert symbol_for("marketdata.ACME") == "ACME"
    assert symbol_for("events.ACME") is None


def test_hub_ignores_unknown_topics_and_garbage():
    hub = FeedHub([("ACME", "0.01")])
    hub.ingest("marketdata.NOPE", b"{}")
    hub.ingest("events.ACME", b"{}")
    hub.ingest("marketdata.ACME", b"not json", offset=3)
    assert hub.feed("acme").seq is None
    assert hub.feed("NOPE") is None
