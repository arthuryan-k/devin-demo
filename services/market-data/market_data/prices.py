from decimal import Decimal

PRICE_FIELDS = ("price", "oldPrice", "newPrice", "limitPrice")


class PriceFormat:
    """Renders engine integer ticks as exact decimal strings (tick 0.01: 10050 -> "100.50")."""

    def __init__(self, tick_size: str = "0.01", decimal: bool = True):
        self.tick = Decimal(tick_size)
        self.places = max(0, -self.tick.as_tuple().exponent)
        self.decimal = decimal

    def price(self, ticks):
        if not self.decimal or ticks is None or isinstance(ticks, bool) or not isinstance(ticks, int):
            return ticks
        return f"{(self.tick * ticks):.{self.places}f}"

    def event(self, event: dict) -> dict:
        if not self.decimal:
            return event
        return {k: (self.price(v) if k in PRICE_FIELDS else v) for k, v in event.items()}
