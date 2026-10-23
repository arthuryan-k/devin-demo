import pytest
from conftest import delta, snapshot
from starlette.websockets import WebSocketDisconnect


def test_snapshot_on_connect_then_ordered_deltas_in_decimals(exchange):
    exchange.publish("ACME", snapshot(10, bids=[(10050, 5, 1)], asks=[(10100, 3, 2)]))
    with exchange.client.websocket_connect("/marketdata/ACME/ws") as ws:
        first = ws.receive_json()
        assert first == {
            "type": "snapshot",
            "symbol": "ACME",
            "seq": 10,
            "reason": "subscribe",
            "bids": [["100.50", 5, 1]],
            "asks": [["101.00", 3, 2]],
            "trades": [],
            "events": [],
        }
        exchange.publish("ACME", delta(11, [("u", "B", 10050, 8, 2)]))
        trade = {"type": "TradeExecuted", "price": 10100, "qty": 3, "takerSide": "BUY"}
        exchange.publish("ACME", delta(12, [("r", "S", 10100, 0, 0), ("a", "S", 10125, 4, 1)], [trade]))
        exchange.publish("ACME", delta(13, [("a", "B", 10000, 1, 1)]))
        got = [ws.receive_json() for _ in range(3)]
        assert [m["seq"] for m in got] == [11, 12, 13]
        assert all(m["type"] == "delta" for m in got)
        assert got[1]["levels"] == [["r", "S", "101.00", 0, 0], ["a", "S", "101.25", 4, 1]]
        assert got[1]["events"] == [{**trade, "price": "101.00"}]


def test_late_subscriber_snapshot_reflects_applied_deltas(exchange):
    exchange.publish("ACME", snapshot(0))  # the engine opens each run with a snapshot
    exchange.publish("ACME", delta(1, [("a", "B", 9900, 2, 1)]))
    exchange.publish("ACME", delta(2, [("a", "S", 10000, 4, 1)]))
    exchange.publish("ACME", delta(3, [("u", "B", 9900, 7, 3)]))
    with exchange.client.websocket_connect("/marketdata/ACME/ws?prices=ticks") as ws:
        snap = ws.receive_json()
        assert (snap["seq"], snap["bids"], snap["asks"]) == (3, [[9900, 7, 3]], [[10000, 4, 1]])
        exchange.publish("ACME", delta(4, [("r", "B", 9900, 0, 0)]))
        assert ws.receive_json()["seq"] == 4


def test_upstream_gap_triggers_replay_and_resync_snapshot(exchange):
    exchange.publish("ACME", snapshot(0))  # the engine opens each run with a snapshot
    exchange.publish("ACME", delta(1, [("a", "B", 10000, 1, 1)]))
    with exchange.client.websocket_connect("/marketdata/ACME/ws") as ws:
        assert ws.receive_json()["seq"] == 1
        exchange.publish("ACME", delta(2, [("a", "B", 10010, 2, 1)]), deliver=False)  # lost in transit
        exchange.publish("ACME", delta(3, [("a", "S", 10020, 3, 1)]))
        exchange.settle()
        resync = ws.receive_json()
        assert resync["type"] == "snapshot" and resync["reason"] == "resync"
        assert resync["seq"] == 3
        assert resync["bids"] == [["100.10", 2, 1], ["100.00", 1, 1]]
        assert resync["asks"] == [["100.20", 3, 1]]
        exchange.publish("ACME", delta(4, [("r", "S", 10020, 0, 0)]))
        nxt = ws.receive_json()
        assert (nxt["type"], nxt["seq"]) == ("delta", 4)
    health = exchange.client.get("/healthz").json()["symbols"]["ACME"]
    assert health["gaps"] == 1 and health["ready"] and health["seq"] == 4


def test_gap_replays_from_latest_upstream_snapshot(exchange):
    exchange.publish("ACME", snapshot(0))  # the engine opens each run with a snapshot
    exchange.publish("ACME", delta(1, [("a", "B", 1, 1, 1)]))
    exchange.publish("ACME", snapshot(50, bids=[(10000, 9, 3)]))
    exchange.publish("ACME", delta(51, [("a", "S", 10100, 1, 1)]), deliver=False)
    exchange.publish("ACME", delta(52, [("u", "B", 10000, 4, 2)]))
    exchange.settle()
    with exchange.client.websocket_connect("/marketdata/ACME/ws") as ws:
        snap = ws.receive_json()
        assert (snap["seq"], snap["bids"], snap["asks"]) == (52, [["100.00", 4, 2]], [["101.00", 1, 1]])


def test_client_requested_resync(exchange):
    exchange.publish("ACME", snapshot(0))  # the engine opens each run with a snapshot
    exchange.publish("ACME", delta(1, [("a", "B", 10000, 1, 1)]))
    with exchange.client.websocket_connect("/marketdata/ACME/ws") as ws:
        ws.receive_json()
        ws.send_json({"type": "resync"})
        again = ws.receive_json()
        assert (again["type"], again["reason"], again["seq"]) == ("snapshot", "requested", 1)


def test_slow_consumer_backlog_collapses_to_snapshot(exchange):
    feed = exchange.client.app.state.hub.feed("ACME")
    sub = exchange.client.portal.call(feed.subscribe)
    for seq in range(0, 20):  # max_backlog is 8 in tests
        exchange.publish("ACME", delta(seq, [("a", "B", 10000 + seq, 1, 1)]))
    drained = []
    while not sub.queue.empty():
        drained.append(sub.queue.get_nowait())
    assert drained[0]["type"] == "snapshot" and drained[0]["reason"] == "slow-consumer"
    seqs = [m["seq"] for m in drained]
    assert seqs == list(range(seqs[0], seqs[0] + len(seqs))) and seqs[-1] == 19


def test_symbols_are_isolated(exchange):
    exchange.publish("ACME", snapshot(0))  # the engine opens each run with a snapshot
    exchange.publish("ACME", delta(1, [("a", "B", 10000, 1, 1)]))
    exchange.publish("GLOBX", snapshot(500, asks=[(400, 10, 1)]))
    with (
        exchange.client.websocket_connect("/marketdata/ACME/ws") as acme,
        exchange.client.websocket_connect("/marketdata/GLOBX/ws") as globx,
    ):
        a0, g0 = acme.receive_json(), globx.receive_json()
        assert (a0["symbol"], a0["seq"]) == ("ACME", 1)
        # GLOBX has its own tick size (0.05) and sequence.
        assert (g0["symbol"], g0["seq"], g0["asks"]) == ("GLOBX", 500, [["20.00", 10, 1]])

        exchange.publish("GLOBX", delta(501, [("u", "S", 400, 5, 1)]))
        exchange.publish("GLOBX", delta(505, [("a", "B", 300, 1, 1)]))  # GLOBX gap
        exchange.publish("ACME", delta(2, [("a", "S", 10100, 2, 1)]))
        exchange.settle()

        a1 = acme.receive_json()
        assert (a1["type"], a1["symbol"], a1["seq"]) == ("delta", "ACME", 2)
        g1 = globx.receive_json()
        assert (g1["type"], g1["seq"]) == ("delta", 501)
    hub = exchange.client.app.state.hub
    assert hub.feed("ACME").gaps == 0 and hub.feed("ACME").ready
    assert hub.feed("GLOBX").gaps >= 1 and not hub.feed("GLOBX").ready


def test_messages_for_another_symbol_on_a_topic_are_dropped(exchange):
    exchange.publish("ACME", {**delta(1, [("a", "B", 1, 1, 1)]), "symbol": "GLOBX"})
    assert exchange.client.app.state.hub.feed("ACME").seq is None


def test_unknown_symbol_is_closed(exchange):
    with exchange.client.websocket_connect("/marketdata/NOPE/ws") as ws:
        with pytest.raises(WebSocketDisconnect) as err:
            ws.receive_json()
        assert err.value.code == 4404
    assert exchange.client.get("/marketdata/NOPE/snapshot").status_code == 404


def test_rest_snapshot_and_symbols(exchange):
    exchange.publish("GLOBX", snapshot(7, bids=[(201, 1, 1)]))
    assert exchange.client.get("/marketdata/GLOBX/snapshot").json()["bids"] == [["10.05", 1, 1]]
    assert [s["symbol"] for s in exchange.client.get("/marketdata/symbols").json()["symbols"]] == ["ACME", "GLOBX"]


def test_web_ui_path_defaults_to_ticks(exchange):
    exchange.publish("ACME", snapshot(3, bids=[(10050, 1, 1)]))
    with exchange.client.websocket_connect("/ws/marketdata/acme") as ws:
        snap = ws.receive_json()
        assert (snap["symbol"], snap["bids"]) == ("ACME", [[10050, 1, 1]])


def test_first_engine_delta_seq_0_starts_from_an_empty_book(exchange):
    exchange.publish("ACME", delta(0, [("a", "B", 10000, 1, 1)]))
    exchange.publish("ACME", delta(1, [("a", "S", 10100, 2, 1)]))
    with exchange.client.websocket_connect("/marketdata/ACME/ws?prices=ticks") as ws:
        snap = ws.receive_json()
        assert (snap["seq"], snap["bids"], snap["asks"]) == (1, [[10000, 1, 1]], [[10100, 2, 1]])
