import json

from portfolio.events import decode
from portfolio.ledger import Amended, Cancelled, Placed, Rejected, Trade


def test_decodes_batch_envelope():
    b = decode(
        b'{"seq": 7, "symbol": "ACME", "events": ['
        b'{"type": "OrderPlaced", "orderId": 1, "side": "BUY", "price": 100, "qty": 5, "seqNum": 3},'
        b'{"type": "TradeExecuted", "trade": {"makerOrderId": 2, "takerOrderId": 1, "price": 99, "qty": 5}},'
        b'{"type": "OrderCancelled", "orderId": 3, "cancelledQty": 4, "reason": "UNFILLED_REMAINDER"},'
        b'{"type": "OrderAmended", "orderId": 4, "oldPrice": 1, "newPrice": 2, "oldQty": 3, "newQty": 6},'
        b'{"type": "OrderRejected", "orderId": 5, "reason": "BUSY"}]}'
    )
    assert (b.symbol, b.seq) == ("ACME", 7)
    assert b.events == [
        Placed(1),
        Trade(2, 1, 99, 5),
        Cancelled(3, 4, "UNFILLED_REMAINDER"),
        Amended(4, 2, 6),
        Rejected(5, "BUSY"),
    ]


def test_decodes_single_flat_event_and_symbol_from_topic():
    b = decode(
        '{"seq": 9, "type": "TradeExecuted", "makerOrderId": 1, "takerOrderId": 2, "price": 5, "qty": 1}',
        "events.GLOBX",
    )
    assert (b.symbol, b.seq, b.events) == ("GLOBX", 9, [Trade(1, 2, 5, 1)])


def test_decodes_phase2_bus_record():
    """schemas/bus/events.schema.json: flat events, cancelledQty, envelope extras (v, command, commandOffset, ts)."""
    record = {
        "v": 1,
        "symbol": "GLOBX",
        "seq": 41,
        "participantId": 1001,
        "correlationId": "c-9",
        "ts": 1792679000000,
        "commandOffset": 40,
        "command": {
            "type": "Place",
            "orderId": 77,
            "side": "BUY",
            "orderType": "LIMIT",
            "timeInForce": "GTC",
            "price": 2500,
            "qty": 10,
        },
        "events": [
            {
                "type": "OrderPlaced",
                "seq": 41,
                "orderId": 77,
                "side": "BUY",
                "orderType": "LIMIT",
                "timeInForce": "GTC",
                "price": 2500,
                "qty": 10,
                "seqNum": 3,
            },
            {
                "type": "TradeExecuted",
                "seq": 41,
                "makerOrderId": 5,
                "takerOrderId": 77,
                "makerParticipantId": 1002,
                "takerParticipantId": 1001,
                "takerSide": "BUY",
                "price": 2490,
                "qty": 4,
            },
            {"type": "OrderCancelled", "seq": 41, "orderId": 77, "cancelledQty": 6, "reason": "UNFILLED_REMAINDER"},
        ],
    }
    batch = decode(json.dumps(record), topic="events.GLOBX")
    assert (batch.symbol, batch.seq) == ("GLOBX", 41)
    assert batch.events == [Placed(77), Trade(5, 77, 2490, 4), Cancelled(77, 6, "UNFILLED_REMAINDER")]
