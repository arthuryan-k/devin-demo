package fixgw;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

/**
 * One FIX acceptor session on one socket: one thread reads, engine events arrive on the bus thread, and both
 * write under the session lock. Supports Logon/Logout/Heartbeat/TestRequest plus NewOrderSingle (D),
 * OrderCancelRequest (F) and OrderCancelReplaceRequest (G); everything else gets a session Reject.
 * No resend, no persistence of sequence numbers: reset on every logon (141=Y semantics), like a lot of
 * in-house gateways from the 2000s.
 */
public class FixSession implements Runnable, OrderBus.Listener {

    private static final Logger LOG = Logger.getLogger(FixSession.class.getName());

    private final Socket socket;
    private final Authenticator auth;
    private final OrderBus bus;
    private final String compId;
    private final RiskClient risk;
    private final OrderIds ids;
    private OutputStream out;

    private boolean loggedOn = false;
    private String peer;
    private long participantId = -1;
    private int inSeq = 1;
    private int outSeq = 1;
    private int heartBtInt = 30;
    private long execIdSeq = 1;

    // order state by ClOrdID (the client's latest id for the order) and by engine order id
    private final Map orders = new HashMap();        // String clOrdId -> Order
    private final Map ordersById = new HashMap();    // Long orderId -> Order

    static class Order {
        String clOrdId;
        String origClOrdId;
        long orderId = -1;
        String symbol;
        String side;
        long price;
        long qty;
        long cumQty;
        long notional; // sum(px * qty) for AvgPx
        String status = "A"; // pending new
        String pendingClOrdId; // for in-flight cancel/replace
        String pendingOrigClOrdId;
    }

    public FixSession(Socket socket, Authenticator auth, OrderBus bus, RiskClient risk, OrderIds ids, String compId) {
        this.socket = socket;
        this.auth = auth;
        this.bus = bus;
        this.risk = risk;
        this.ids = ids;
        this.compId = compId;
    }

    public void run() {
        try {
            out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            socket.setSoTimeout(1000);
            StringBuffer buf = new StringBuffer();
            long lastHeard = System.currentTimeMillis();
            while (!socket.isClosed()) {
                int b;
                try {
                    b = in.read();
                } catch (SocketTimeoutException e) {
                    if (loggedOn && System.currentTimeMillis() - lastHeard > 2500L * heartBtInt) {
                        LOG.info("session " + peer + " timed out");
                        break;
                    }
                    continue;
                }
                if (b < 0) break;
                buf.append((char) b);
                if (b == FixMessage.SOH && endsWithChecksum(buf)) {
                    String raw = buf.toString();
                    buf.setLength(0);
                    lastHeard = System.currentTimeMillis();
                    handleRaw(raw);
                }
            }
        } catch (IOException e) {
            LOG.info("session " + peer + " io: " + e.getMessage());
        } finally {
            close();
        }
    }

    private static boolean endsWithChecksum(StringBuffer buf) {
        // ... SOH 10=nnn SOH
        int n = buf.length();
        if (n < 8) return false;
        return buf.charAt(n - 8) == FixMessage.SOH && buf.charAt(n - 7) == '1' && buf.charAt(n - 6) == '0'
                && buf.charAt(n - 5) == '=';
    }

    void handleRaw(String raw) throws IOException {
        FixMessage m;
        try {
            m = FixMessage.parse(raw);
        } catch (FixException e) {
            // garbled: FIX says ignore, but tell them so they can see something in the demo
            LOG.warning("garbled message: " + e.getMessage());
            if (loggedOn) sendReject(0, e.getMessage());
            return;
        }
        int seq = m.getInt(Tags.MsgSeqNum, -1);
        String type = m.getMsgType();
        if (!loggedOn) {
            if (!"A".equals(type)) {
                LOG.warning("first message not Logon, dropping connection");
                close();
                return;
            }
            onLogon(m);
            return;
        }
        if (seq != inSeq) {
            // no resend support: too low or too high both kill the session
            logout("MsgSeqNum expected " + inSeq + " got " + seq);
            return;
        }
        inSeq++;
        if ("0".equals(type)) {
            return;
        } else if ("1".equals(type)) {
            FixMessage hb = new FixMessage("0");
            hb.set(Tags.TestReqID, m.get(Tags.TestReqID));
            send(hb);
        } else if ("5".equals(type)) {
            send(new FixMessage("5"));
            close();
        } else if ("D".equals(type)) {
            onNewOrder(m, seq);
        } else if ("F".equals(type)) {
            onCancel(m, seq);
        } else if ("G".equals(type)) {
            onReplace(m, seq);
        } else {
            sendReject(seq, "unsupported MsgType " + type);
        }
    }

    private void onLogon(FixMessage m) throws IOException {
        peer = m.get(Tags.SenderCompID);
        long id = auth.authenticate(m.get(Tags.Username), m.get(Tags.Password));
        inSeq = m.getInt(Tags.MsgSeqNum, 1) + 1;
        if (id < 0) {
            LOG.warning("logon refused for " + peer + " user=" + m.get(Tags.Username));
            FixMessage lo = new FixMessage("5");
            lo.set(Tags.Text, "logon refused");
            send(lo);
            close();
            return;
        }
        participantId = id;
        heartBtInt = m.getInt(Tags.HeartBtInt, 30);
        loggedOn = true;
        bus.subscribe(participantId, this);
        FixMessage ack = new FixMessage("A");
        ack.set(Tags.EncryptMethod, "0");
        ack.set(Tags.HeartBtInt, heartBtInt);
        send(ack);
        LOG.info("logon " + peer + " as participant " + participantId);
    }

    private String side(FixMessage m) {
        String s = m.get(Tags.Side);
        if ("1".equals(s)) return "BUY";
        if ("2".equals(s)) return "SELL";
        return null;
    }

    private void onNewOrder(FixMessage m, int seq) throws IOException {
        String clOrdId = m.get(Tags.ClOrdID);
        String symbol = m.get(Tags.Symbol);
        String side = side(m);
        String ordType = m.get(Tags.OrdType);
        long qty;
        long price = 0;
        try {
            qty = Long.parseLong(m.get(Tags.OrderQty));
            if ("2".equals(ordType)) price = Prices.toTicks(m.get(Tags.Price));
        } catch (RuntimeException e) {
            rejectOrder(clOrdId, symbol, m.get(Tags.Side), "bad qty/price");
            return;
        }
        if (clOrdId == null || symbol == null || side == null || qty <= 0
                || !("1".equals(ordType) || "2".equals(ordType)) || ("2".equals(ordType) && price <= 0)) {
            rejectOrder(clOrdId, symbol, m.get(Tags.Side), "invalid order");
            return;
        }
        String tif;
        String t = m.get(Tags.TimeInForce);
        if (t == null || "1".equals(t) || "0".equals(t)) tif = "GTC";
        else if ("3".equals(t)) tif = "IOC";
        else if ("4".equals(t)) tif = "FOK";
        else {
            rejectOrder(clOrdId, symbol, m.get(Tags.Side), "unsupported TimeInForce " + t);
            return;
        }
        synchronized (this) {
            if (orders.containsKey(clOrdId)) {
                rejectOrder(clOrdId, symbol, m.get(Tags.Side), "duplicate ClOrdID");
                return;
            }
        }
        OrderCommand c = new OrderCommand();
        c.type = OrderCommand.NEW;
        c.participantId = participantId;
        c.clOrdId = clOrdId;
        c.orderId = ids.next();
        c.symbol = symbol;
        c.side = side;
        c.ordType = "2".equals(ordType) ? "LIMIT" : "MARKET";
        c.price = price;
        c.qty = qty;
        c.tif = "1".equals(ordType) ? "IOC" : tif;
        RiskClient.Decision d = risk.check(c);
        if (!d.approved) {
            rejectOrder(clOrdId, symbol, m.get(Tags.Side), d.reason);
            return;
        }
        if ("MARKET".equals(c.ordType) && d.limitPrice > 0) {
            // risk reserved at a protection price: send it as a LIMIT IOC there, like the REST gateway
            c.ordType = "LIMIT";
            c.price = d.limitPrice;
        }
        synchronized (this) {
            Order o = new Order();
            o.clOrdId = clOrdId;
            o.orderId = c.orderId;
            o.symbol = symbol;
            o.side = side;
            o.price = price;
            o.qty = qty;
            orders.put(clOrdId, o);
            ordersById.put(Long.valueOf(c.orderId), o);
        }
        bus.publish(c);
    }

    private void onCancel(FixMessage m, int seq) throws IOException {
        String clOrdId = m.get(Tags.ClOrdID);
        String orig = m.get(Tags.OrigClOrdID);
        Order o;
        synchronized (this) {
            o = orig == null ? null : (Order) orders.get(orig);
            if (o == null || "A".equals(o.status) || isDone(o)) {
                cancelReject(clOrdId, orig, "1", "unknown order");
                return;
            }
            o.pendingClOrdId = clOrdId;
            o.pendingOrigClOrdId = orig;
        }
        OrderCommand c = new OrderCommand();
        c.type = OrderCommand.CANCEL;
        c.participantId = participantId;
        c.clOrdId = clOrdId;
        c.origClOrdId = orig;
        c.orderId = o.orderId;
        c.symbol = o.symbol;
        RiskClient.Decision d = risk.check(c);
        if (!d.approved) {
            synchronized (this) {
                o.pendingClOrdId = null;
            }
            cancelReject(clOrdId, orig, "1", d.reason);
            return;
        }
        bus.publish(c);
    }

    private void onReplace(FixMessage m, int seq) throws IOException {
        String clOrdId = m.get(Tags.ClOrdID);
        String orig = m.get(Tags.OrigClOrdID);
        Order o;
        long price;
        long qty;
        synchronized (this) {
            o = orig == null ? null : (Order) orders.get(orig);
            if (o == null || "A".equals(o.status) || isDone(o)) {
                cancelReject(clOrdId, orig, "2", "unknown order");
                return;
            }
            try {
                price = m.has(Tags.Price) ? Prices.toTicks(m.get(Tags.Price)) : o.price;
                qty = m.has(Tags.OrderQty) ? Long.parseLong(m.get(Tags.OrderQty)) : o.qty;
            } catch (RuntimeException e) {
                cancelReject(clOrdId, orig, "2", "bad qty/price");
                return;
            }
            if (price <= 0 || qty <= o.cumQty) {
                cancelReject(clOrdId, orig, "2", "invalid replace");
                return;
            }
            o.pendingClOrdId = clOrdId;
            o.pendingOrigClOrdId = orig;
        }
        OrderCommand c = new OrderCommand();
        c.type = OrderCommand.REPLACE;
        c.participantId = participantId;
        c.clOrdId = clOrdId;
        c.origClOrdId = orig;
        c.orderId = o.orderId;
        c.symbol = o.symbol;
        c.side = o.side;
        c.price = price;
        c.qty = qty - o.cumQty; // engine amends the remaining qty
        RiskClient.Decision d = risk.check(c);
        if (!d.approved) {
            synchronized (this) {
                o.pendingClOrdId = null;
            }
            cancelReject(clOrdId, orig, "2", d.reason);
            return;
        }
        bus.publish(c);
    }

    private static boolean isDone(Order o) {
        return "2".equals(o.status) || "4".equals(o.status) || "8".equals(o.status) || "C".equals(o.status);
    }

    /** Engine events for our participant, on the bus thread. */
    public void onEvent(EngineEvent e) {
        try {
            synchronized (this) {
                handleEvent(e);
            }
        } catch (IOException ex) {
            LOG.info("session " + peer + " write failed: " + ex.getMessage());
            close();
        }
    }

    private void handleEvent(EngineEvent e) throws IOException {
        Order o = null;
        if (e.orderId > 0) o = (Order) ordersById.get(Long.valueOf(e.orderId));
        if (o == null && e.clOrdId != null) o = (Order) orders.get(e.clOrdId);
        if (o == null) return; // someone else's order flow for this participant (e.g. their REST orders)
        if (EngineEvent.ACCEPTED.equals(e.type)) {
            o.orderId = e.orderId;
            ordersById.put(Long.valueOf(e.orderId), o);
            o.status = "0";
            send(execReport(o, "0", 0, 0));
        } else if (EngineEvent.REJECTED.equals(e.type)) {
            if (o.pendingClOrdId != null) {
                String which = o.pendingClOrdId;
                String orig = o.pendingOrigClOrdId;
                o.pendingClOrdId = null;
                cancelReject(which, orig, o.qty == 0 ? "1" : "2", e.text);
                return;
            }
            o.status = "8";
            FixMessage er = execReport(o, "8", 0, 0);
            er.set(Tags.Text, e.text == null ? "rejected" : e.text);
            send(er);
        } else if (EngineEvent.FILL.equals(e.type)) {
            o.cumQty += e.qty;
            o.notional += e.qty * e.price;
            o.status = o.cumQty >= o.qty ? "2" : "1";
            send(execReport(o, "F", e.qty, e.price));
        } else if (EngineEvent.CANCELED.equals(e.type) || EngineEvent.EXPIRED.equals(e.type)) {
            o.status = "4";
            if (o.pendingClOrdId != null) {
                o.origClOrdId = o.pendingOrigClOrdId;
                o.clOrdId = o.pendingClOrdId;
                orders.put(o.clOrdId, o);
                o.pendingClOrdId = null;
            }
            send(execReport(o, "4", 0, 0));
        } else if (EngineEvent.REPLACED.equals(e.type)) {
            o.origClOrdId = o.pendingOrigClOrdId;
            o.clOrdId = o.pendingClOrdId != null ? o.pendingClOrdId : o.clOrdId;
            o.pendingClOrdId = null;
            orders.put(o.clOrdId, o);
            o.price = e.price;
            o.qty = o.cumQty + e.leavesQty;
            o.status = "5";
            send(execReport(o, "5", 0, 0));
            o.status = o.cumQty > 0 ? "1" : "0";
        }
    }

    private FixMessage execReport(Order o, String execType, long lastQty, long lastPx) {
        FixMessage er = new FixMessage("8");
        er.set(Tags.OrderID, o.orderId < 0 ? "NONE" : String.valueOf(o.orderId));
        er.set(Tags.ClOrdID, o.clOrdId);
        if (o.origClOrdId != null && ("4".equals(execType) || "5".equals(execType))) {
            er.set(Tags.OrigClOrdID, o.origClOrdId);
        }
        er.set(Tags.ExecID, compId + "-" + participantId + "-" + (execIdSeq++));
        er.set(Tags.ExecType, execType);
        er.set(Tags.OrdStatus, o.status);
        er.set(Tags.Symbol, o.symbol);
        er.set(Tags.Side, "BUY".equals(o.side) ? "1" : "2");
        er.set(Tags.OrderQty, o.qty);
        if (o.price > 0) er.set(Tags.Price, Prices.toPx(o.price));
        if (lastQty > 0) {
            er.set(Tags.LastQty, lastQty);
            er.set(Tags.LastPx, Prices.toPx(lastPx));
        }
        long leaves = isDone(o) ? 0 : o.qty - o.cumQty;
        er.set(Tags.LeavesQty, leaves);
        er.set(Tags.CumQty, o.cumQty);
        er.set(Tags.AvgPx, o.cumQty == 0 ? "0" : Prices.toPx(o.notional / o.cumQty));
        er.set(Tags.TransactTime, now());
        return er;
    }

    private void rejectOrder(String clOrdId, String symbol, String fixSide, String why) throws IOException {
        FixMessage er = new FixMessage("8");
        er.set(Tags.OrderID, "NONE");
        er.set(Tags.ClOrdID, clOrdId == null ? "NONE" : clOrdId);
        er.set(Tags.ExecID, compId + "-" + participantId + "-" + (execIdSeq++));
        er.set(Tags.ExecType, "8");
        er.set(Tags.OrdStatus, "8");
        er.set(Tags.Symbol, symbol == null ? "NONE" : symbol);
        er.set(Tags.Side, fixSide == null ? "1" : fixSide);
        er.set(Tags.LeavesQty, 0);
        er.set(Tags.CumQty, 0);
        er.set(Tags.AvgPx, "0");
        er.set(Tags.Text, why);
        send(er);
    }

    private void cancelReject(String clOrdId, String orig, String responseTo, String why) throws IOException {
        FixMessage r = new FixMessage("9");
        r.set(Tags.OrderID, "NONE");
        r.set(Tags.ClOrdID, clOrdId == null ? "NONE" : clOrdId);
        r.set(Tags.OrigClOrdID, orig == null ? "NONE" : orig);
        r.set(Tags.OrdStatus, "8");
        r.set(434, responseTo); // CxlRejResponseTo
        r.set(Tags.Text, why);
        send(r);
    }

    private void sendReject(int refSeq, String why) throws IOException {
        FixMessage r = new FixMessage("3");
        r.set(Tags.RefSeqNum, refSeq);
        r.set(Tags.Text, why);
        send(r);
    }

    private void logout(String why) throws IOException {
        FixMessage lo = new FixMessage("5");
        lo.set(Tags.Text, why);
        send(lo);
        close();
    }

    synchronized void send(FixMessage m) throws IOException {
        if (socket.isClosed()) return;
        m.set(Tags.SenderCompID, compId);
        m.set(Tags.TargetCompID, peer == null ? "UNKNOWN" : peer);
        m.set(Tags.MsgSeqNum, outSeq++);
        m.set(Tags.SendingTime, now());
        String enc = m.encode();
        out.write(enc.getBytes("US-ASCII"));
        out.flush();
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HH:mm:ss.SSS");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    public void close() {
        if (participantId >= 0) bus.unsubscribe(participantId, this);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
