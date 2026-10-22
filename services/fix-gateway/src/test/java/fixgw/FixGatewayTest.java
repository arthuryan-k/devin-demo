package fixgw;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixGatewayTest {

    static final long ALICE = 101, BOB = 202, CAROL = 303;
    static final Map<String, Long> KEYS = Map.of("key-alice", ALICE, "key-bob", BOB, "key-carol", CAROL);

    ToyEngine engine;
    FixServer server;
    int port;

    @BeforeEach
    void up() throws Exception {
        engine = new ToyEngine();
        server = new FixServer((u, p) -> KEYS.getOrDefault(p, -1L), engine, "FIXGW");
        port = server.start(0);
    }

    @AfterEach
    void down() {
        server.stop();
    }

    @Test
    void codecRoundTripsAndChecksBodyLengthAndChecksum() throws Exception {
        FixMessage m = FixTestClient.limit("c1", "ACME", "1", "100.25", 7).set(Tags.SenderCompID, "X");
        String raw = m.encode();
        assertTrue(raw.startsWith("8=FIX.4.4\u00019="));
        FixMessage back = FixMessage.parse(raw);
        assertEquals("100.25", back.get(Tags.Price));
        assertEquals("D", back.getMsgType());
        int body = raw.indexOf("35=");
        int trailer = raw.lastIndexOf("10=");
        assertEquals(String.valueOf(trailer - body), back.get(Tags.BodyLength));
        String tampered = raw.replace("100.25", "100.26");
        assertThrowsFix(tampered);
    }

    private static void assertThrowsFix(String raw) {
        try {
            FixMessage.parse(raw);
        } catch (FixException e) {
            return;
        }
        throw new AssertionError("expected FixException");
    }

    @Test
    void logonWithBadKeyIsRefused() throws Exception {
        try (FixTestClient c = new FixTestClient(port, "MALLORY")) {
            FixMessage r = c.logon("mallory", "nope");
            assertEquals("5", r.getMsgType());
        }
    }

    @Test
    void fixOrderMatchesRestOrderInTheSameBook() throws Exception {
        try (FixTestClient alice = new FixTestClient(port, "ALICE")) {
            assertEquals("A", alice.logon("alice", "key-alice").getMsgType());
            alice.send(FixTestClient.limit("a1", "ACME", "2", "100.00", 10));
            FixMessage ack = alice.awaitExec("a1", "0");
            assertEquals("0", ack.get(Tags.OrdStatus));
            assertEquals("10", ack.get(Tags.LeavesQty));

            // a REST client's order arrives on the same commands.ACME path
            OrderCommand rest = new OrderCommand();
            rest.type = OrderCommand.NEW;
            rest.participantId = BOB;
            rest.clOrdId = "rest-1";
            rest.orderId = new OrderIds(0).next();
            rest.symbol = "ACME";
            rest.side = "BUY";
            rest.ordType = "LIMIT";
            rest.price = 100_00;
            rest.qty = 4;
            rest.tif = "GTC";
            engine.publish(rest);

            FixMessage fill = alice.awaitExec("a1", "F");
            assertEquals("1", fill.get(Tags.OrdStatus));
            assertEquals("4", fill.get(Tags.LastQty));
            assertEquals("100.00", fill.get(Tags.LastPx));
            assertEquals("4", fill.get(Tags.CumQty));
            assertEquals("6", fill.get(Tags.LeavesQty));
            assertEquals(1, engine.trades.size());
            assertArrayEquals(new long[] {ALICE, BOB, 100_00, 4}, engine.trades.get(0));
        }
    }

    @Test
    void newOrderSingleRoundTripsToExecutionReportsAndATrade() throws Exception {
        try (FixTestClient alice = new FixTestClient(port, "ALICE");
                FixTestClient carol = new FixTestClient(port, "CAROL")) {
            alice.logon("alice", "key-alice");
            carol.logon("carol", "key-carol");
            alice.send(FixTestClient.limit("a1", "GLOBX", "2", "50.10", 5));
            alice.awaitExec("a1", "0");
            carol.send(FixTestClient.limit("c1", "GLOBX", "1", "50.20", 5));
            FixMessage takerFill = carol.awaitExec("c1", "F");
            assertEquals("2", takerFill.get(Tags.OrdStatus));
            assertEquals("50.10", takerFill.get(Tags.LastPx));
            assertEquals("50.10", takerFill.get(Tags.AvgPx));
            assertEquals("0", takerFill.get(Tags.LeavesQty));
            FixMessage makerFill = alice.awaitExec("a1", "F");
            assertEquals("2", makerFill.get(Tags.OrdStatus));
            assertEquals("5", makerFill.get(Tags.CumQty));
            assertEquals(1, engine.trades.size());
            assertEquals("GLOBX", engine.published.get(0).symbol);
        }
    }

    @Test
    void cancelAndReplace() throws Exception {
        try (FixTestClient alice = new FixTestClient(port, "ALICE")) {
            alice.logon("alice", "key-alice");
            alice.send(FixTestClient.limit("a1", "ACME", "1", "99.00", 10));
            alice.awaitExec("a1", "0");
            alice.send(new FixMessage("G").set(Tags.ClOrdID, "a2").set(Tags.OrigClOrdID, "a1")
                    .set(Tags.Symbol, "ACME").set(Tags.Side, "1").set(Tags.OrdType, "2").set(Tags.Price, "99.50")
                    .set(Tags.OrderQty, 6));
            FixMessage replaced = alice.awaitExec("a2", "5");
            assertEquals("a1", replaced.get(Tags.OrigClOrdID));
            assertEquals("99.50", replaced.get(Tags.Price));
            assertEquals("6", replaced.get(Tags.LeavesQty));
            assertEquals(OrderCommand.REPLACE, engine.published.get(1).type);

            alice.send(new FixMessage("F").set(Tags.ClOrdID, "a3").set(Tags.OrigClOrdID, "a2")
                    .set(Tags.Symbol, "ACME").set(Tags.Side, "1"));
            FixMessage canceled = alice.awaitExec("a3", "4");
            assertEquals("4", canceled.get(Tags.OrdStatus));
            assertEquals("0", canceled.get(Tags.LeavesQty));

            alice.send(new FixMessage("F").set(Tags.ClOrdID, "a4").set(Tags.OrigClOrdID, "a3")
                    .set(Tags.Symbol, "ACME").set(Tags.Side, "1"));
            FixMessage rej = alice.await(m -> "9".equals(m.getMsgType()));
            assertEquals("a4", rej.get(Tags.ClOrdID));
        }
    }

    @Test
    void riskRejectionsBecomeExecutionReportRejects() throws Exception {
        server.stop();
        server = new FixServer((u, p) -> KEYS.getOrDefault(p, -1L), engine,
                c -> "SELL".equals(c.side) ? RiskClient.Decision.no("NO_SHORTING") : RiskClient.Decision.ok(),
                new OrderIds(15), "FIXGW");
        port = server.start(0);
        try (FixTestClient alice = new FixTestClient(port, "ALICE")) {
            alice.logon("alice", "key-alice");
            alice.send(FixTestClient.limit("s1", "ACME", "2", "100.00", 10));
            FixMessage r = alice.awaitExec("s1", "8");
            assertEquals("NO_SHORTING", r.get(Tags.Text));
            alice.send(FixTestClient.limit("b1", "ACME", "1", "100.00", 10));
            FixMessage ok = alice.awaitExec("b1", "0");
            assertEquals(1, engine.published.size());
            assertEquals(Long.parseLong(ok.get(Tags.OrderID)), engine.published.get(0).orderId);
            assertEquals(15, (engine.published.get(0).orderId >> 8) & 15, "FIX gateway's instance bits");
        }
    }

    @Test
    void invalidOrdersAndGarbledMessagesAreRejected() throws Exception {
        try (FixTestClient alice = new FixTestClient(port, "ALICE")) {
            alice.logon("alice", "key-alice");
            alice.send(FixTestClient.limit("bad", "ACME", "1", "-1.00", 10));
            FixMessage r = alice.awaitExec("bad", "8");
            assertEquals("8", r.get(Tags.OrdStatus));
            String raw = FixTestClient.limit("x", "ACME", "1", "1.00", 1).set(Tags.MsgSeqNum, alice.seq)
                    .set(Tags.SenderCompID, "ALICE").encode();
            alice.sendRaw(raw.substring(0, raw.lastIndexOf("10=")) + "10=000\u0001");
            assertEquals("3", alice.await(m -> "3".equals(m.getMsgType()) || "8".equals(m.getMsgType()))
                    .getMsgType());
            assertEquals(0, engine.published.size());
        }
    }
}
