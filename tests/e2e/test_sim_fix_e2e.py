"""End-to-end checks against a running `docker compose up` stack (gateway :8080, auth :8081, portfolio :8091,
simulator admin :8095, FIX :9878). Run: python3 tests/e2e/test_sim_fix_e2e.py"""
import json, os, socket, time, urllib.request

GW = os.environ.get("GATEWAY_URL", "http://localhost:8080")
AUTH = os.environ.get("AUTH_URL", "http://localhost:8081")
PF = os.environ.get("PORTFOLIO_URL", "http://localhost:8091")
SIM = os.environ.get("SIM_ADMIN_URL", "http://localhost:8095")
FIX_HOST, FIX_PORT = os.environ.get("FIX_HOST", "localhost"), int(os.environ.get("FIX_PORT", "9878"))
SYMBOLS = ["ACME", "GLOBX", "INITD", "NVTA"]
SOH = "\x01"


def call(url, body=None, headers=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data, {"content-type": "application/json", **(headers or {})} if data else (headers or {}),
                                 method=method or ("POST" if data is not None else "GET"))
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read() or b"{}")


def wait_for(pred, what, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = pred()
            if v:
                return v
        except Exception:
            pass
        time.sleep(1)
    raise AssertionError("timed out waiting for " + what)


def sim_participants():
    return [p for s in call(f"{SIM}/admin/status")["symbols"] for p in s["participants"]]


def test_simulator_trades_every_symbol_with_its_own_accounts():
    call(f"{SIM}/admin/start", {})
    wait_for(lambda: all(len(call(f"{GW}/marketdata/{s}/snapshot")["trades"]) > 0 for s in SYMBOLS), "trades on every symbol")
    status = call(f"{SIM}/admin/status")
    assert status["replies"].get("200", 0) > 0, status["replies"]
    assert not any(code.startswith("4") and code not in ("404", "422", "429") for code in status["replies"]), status["replies"]
    ps = [p for p in sim_participants() if p["participantId"] > 0]  # -1 = sign-up still in flight
    assert ps, "no signed-up participants"
    ids = [p["participantId"] for p in ps]
    assert len(ids) == len(set(ids)), "participants share an account"
    for p in ps:
        principal = call(f"{AUTH}/internal/participants/{p['participantId']}")
        assert principal["kind"] == "simulated" and principal["label"].startswith(p["label"]), principal
    print(f"ok: {len(ps)} simulated participants, each its own account; trades on {SYMBOLS}")


def test_admin_controls_and_stop_cancels_every_resting_order():
    s = call(f"{SIM}/admin/rate?value=40", {})
    assert s["rate"] == 40.0
    s = call(f"{SIM}/admin/participants?value=5", {})
    assert s["targetParticipants"] == 5
    ps = wait_for(lambda: [p for p in sim_participants() if p["restingOrders"] > 0], "resting simulated orders")
    call(f"{SIM}/admin/stop", {})
    for p in ps:
        acct = wait_for(lambda: (lambda a: a if not a.get("openOrders") else None)(call(f"{PF}/v1/accounts/{p['participantId']}")),
                        f"{p['label']} to have no open orders")
        assert not acct.get("openOrders")
    for sym in SYMBOLS:
        snap = wait_for(lambda: (lambda d: d if not d["bids"] and not d["asks"] else None)(call(f"{GW}/marketdata/{sym}/snapshot")),
                        f"{sym} book to empty after stop")
    print(f"ok: rate/participant controls; stop cancelled the resting orders of {len(ps)} participants; books empty")


def fix_msg(fields):
    body = "".join(f"{t}={v}{SOH}" for t, v in fields)
    head = f"8=FIX.4.4{SOH}9={len(body)}{SOH}" + body
    return (head + f"10={sum(head.encode()) % 256:03d}{SOH}").encode()


def test_fix_new_order_single_trades_against_a_rest_order():
    call(f"{SIM}/admin/stop", {})  # empty books, so the two orders can only meet each other
    seller = call(f"{GW}/participants/register", {"name": "fix_seller"})
    buyer = call(f"{GW}/participants/register", {"name": "rest_buyer"})
    call(f"{PF}/v1/accounts", {"accountId": seller["participantId"], "positions": [{"symbol": "INITD", "shares": 50, "avgCost": 2500}]})
    sock, seq, buf = socket.create_connection((FIX_HOST, FIX_PORT), timeout=1), [1], [""]

    def send(t, fields):
        sock.sendall(fix_msg([(35, t), (49, "E2E"), (56, "FIXGW"), (34, seq[0]), (52, "20261022-00:00:00.000")] + fields))
        seq[0] += 1

    def until(pred, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            while f"{SOH}10=" in buf[0]:
                i = buf[0].index(f"{SOH}10=") + 8
                msg = dict(f.split("=", 1) for f in buf[0][:i].strip(SOH).split(SOH))
                buf[0] = buf[0][i:]
                if pred(msg):
                    return msg
            try:
                buf[0] += sock.recv(4096).decode()
            except socket.timeout:
                pass
        raise AssertionError("no matching FIX message")

    send("A", [(98, 0), (108, 30), (553, seller["label"]), (554, seller["apiKey"])])
    assert until(lambda m: m["35"] in ("A", "5"))["35"] == "A"
    send("D", [(11, "e2e-1"), (55, "INITD"), (54, 2), (40, 2), (44, "25.37"), (38, 7), (59, 1), (60, "20261022-00:00:00.000")])
    new = until(lambda m: m.get("35") == "8" and m.get("11") == "e2e-1")
    assert new["150"] == "0", new
    rest = call(f"{GW}/api/orders", {"symbol": "INITD", "side": "BUY", "type": "LIMIT", "price": "25.37", "qty": 7,
                                       "timeInForce": "IOC"}, {"x-api-key": buyer["apiKey"]})
    fill = until(lambda m: m.get("35") == "8" and m.get("150") == "F")
    assert (fill["39"], fill["31"], fill["32"], fill["151"]) == ("2", "25.37", "7", "0"), fill
    trade = [e for e in rest["events"] if e["type"] == "TradeExecuted"]
    assert trade and trade[0]["makerOrderId"] == int(fill["37"]) and trade[0]["takerOrderId"] == rest["orderId"], rest
    sock.close()
    call(f"{SIM}/admin/start", {})
    print(f"ok: FIX NewOrderSingle {fill['37']} -> ExecutionReport Filled 7 @ 25.37 against REST order {rest['orderId']}")


if __name__ == "__main__":
    for t in (test_simulator_trades_every_symbol_with_its_own_accounts, test_admin_controls_and_stop_cancels_every_resting_order,
              test_fix_new_order_single_trades_against_a_rest_order):
        t()
    print("all e2e checks passed")
