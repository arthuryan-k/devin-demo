import json

from portfolio import consumer
from tests.helpers import check, open_account, view


def msg(seq, *events):
    return json.dumps({"seq": seq, "symbol": "ACME", "events": list(events)})


def test_redelivered_messages_settle_once(pool):
    open_account(pool, 1, 100_000)
    check(pool, 1, "ACME", 1, "BUY", 10, 1_000)
    m0 = msg(1, {"type": "OrderPlaced", "orderId": 1})
    m1 = msg(2, {"type": "TradeExecuted", "trade": {"makerOrderId": 9, "takerOrderId": 1, "price": 900, "qty": 5}})
    assert consumer.process(pool, "events.ACME", 0, 0, m0)
    assert consumer.process(pool, "events.ACME", 0, 1, m1)
    assert not consumer.process(pool, "events.ACME", 0, 1, m1)  # redelivery after a crash before the Kafka commit
    assert not consumer.process(pool, "events.ACME", 0, 0, m0)
    v = view(pool, 1)
    assert (v["cash"], v["reservedCash"]) == (100_000 - 4_500, 5_000)
    assert consumer.stored_offsets(pool) == {("events.ACME", 0): 2}
