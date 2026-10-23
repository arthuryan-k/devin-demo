import pytest
from portfolio.ledger import protection_price
from pydantic import ValidationError

from risk.api import AmendCheckIn, OrderCheckIn, ReleaseIn, Target


def test_participant_id_wins_over_account_id():
    assert Target(participantId=1001, accountId=7, symbol="ACME", orderId=1).owner == 1001
    assert Target(accountId=7, symbol="ACME", orderId=1).owner == 7


def test_an_owner_is_required():
    with pytest.raises(ValidationError, match="participantId is required"):
        Target(symbol="ACME", orderId=1)


def test_order_defaults_to_a_gtc_limit():
    order = OrderCheckIn(participantId=1, symbol="ACME", orderId=1, side="BUY", qty=1, price=100)
    assert (order.type, order.tif, order.referencePrice) == ("LIMIT", "GTC", None)


def test_order_accepts_gateway_field_names():
    order = OrderCheckIn.model_validate(
        {
            "participantId": 1,
            "symbol": "ACME",
            "orderId": 1,
            "side": "SELL",
            "qty": 1,
            "orderType": "MARKET",
            "timeInForce": "FOK",
        }
    )
    assert (order.type, order.tif) == ("MARKET", "FOK")


@pytest.mark.parametrize(
    "field",
    [{"side": "SHORT"}, {"type": "STOP"}, {"tif": "DAY"}, {"qty": "lots"}],
)
def test_order_enums_are_closed(field):
    body = {"participantId": 1, "symbol": "ACME", "orderId": 1, "side": "BUY", "qty": 1, "price": 1, **field}
    with pytest.raises(ValidationError):
        OrderCheckIn.model_validate(body)


def test_amend_and_release_defaults():
    amend = AmendCheckIn(participantId=1, symbol="ACME", orderId=1)
    assert (amend.newPrice, amend.newQty) == (None, None)
    assert ReleaseIn(participantId=1, symbol="ACME", orderId=1).kind == "place"


@pytest.mark.parametrize(
    ("anchor", "collar", "expected"),
    [(1_000, 5, 1_050), (999, 5, 1_049), (1, 5, 2), (100, 0, 100), (12_345, 10, 13_580)],
)
def test_protection_price_rounds_up(anchor, collar, expected):
    assert protection_price(anchor, collar) == expected
