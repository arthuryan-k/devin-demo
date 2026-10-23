package fixgw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test stand-in for commands.&lt;symbol&gt; -> matching engine -> events.&lt;symbol&gt;: one price-time book per
 * symbol, shared by every publisher (FIX sessions and a "REST" client alike).
 */
class ToyEngine implements OrderBus {

    static class Resting {
        long id;
        long participantId;
        String clOrdId;
        String symbol;
        String side;
        long price;
        long leaves;
    }

    final List<OrderCommand> published = new CopyOnWriteArrayList<>();
    final List<long[]> trades = new CopyOnWriteArrayList<>(); // {makerParticipant, takerParticipant, price, qty}
    private final Map<Long, List<Listener>> listeners = new HashMap<>();
    private final Map<String, List<Resting>> books = new HashMap<>();
    private long nextId = 1;

    public synchronized void subscribe(long participantId, Listener l) {
        listeners.computeIfAbsent(participantId, k -> new ArrayList<>()).add(l);
    }

    public synchronized void unsubscribe(long participantId, Listener l) {
        List<Listener> ls = listeners.get(participantId);
        if (ls != null) ls.remove(l);
    }

    private void emit(long participantId, EngineEvent e) {
        e.participantId = participantId;
        for (Listener l : new ArrayList<>(listeners.getOrDefault(participantId, Collections.<Listener>emptyList()))) l.onEvent(e);
    }

    private static EngineEvent ev(String type, Resting r, long price, long qty) {
        EngineEvent e = new EngineEvent();
        e.type = type;
        e.clOrdId = r.clOrdId;
        e.orderId = r.id;
        e.symbol = r.symbol;
        e.side = r.side;
        e.price = price;
        e.qty = qty;
        e.leavesQty = r.leaves;
        return e;
    }

    public synchronized void publish(OrderCommand c) {
        published.add(c);
        List<Resting> book = books.computeIfAbsent(c.symbol, k -> new ArrayList<>());
        if (OrderCommand.NEW.equals(c.type)) {
            Resting t = new Resting();
            t.id = c.orderId > 0 ? c.orderId : nextId++;
            t.participantId = c.participantId;
            t.clOrdId = c.clOrdId;
            t.symbol = c.symbol;
            t.side = c.side;
            t.price = c.price;
            t.leaves = c.qty;
            emit(t.participantId, ev(EngineEvent.ACCEPTED, t, t.price, c.qty));
            boolean buy = "BUY".equals(c.side);
            while (t.leaves > 0) {
                Resting best = null;
                for (Resting r : book) {
                    if (r.side.equals(c.side)) continue;
                    boolean crosses = "MARKET".equals(c.ordType) || (buy ? r.price <= c.price : r.price >= c.price);
                    if (!crosses) continue;
                    if (best == null || (buy ? r.price < best.price : r.price > best.price)) best = r;
                }
                if (best == null) break;
                long q = Math.min(t.leaves, best.leaves);
                t.leaves -= q;
                best.leaves -= q;
                if (best.leaves == 0) book.remove(best);
                trades.add(new long[] {best.participantId, t.participantId, best.price, q});
                emit(best.participantId, ev(EngineEvent.FILL, best, best.price, q));
                emit(t.participantId, ev(EngineEvent.FILL, t, best.price, q));
            }
            if (t.leaves > 0) {
                if ("GTC".equals(c.tif) && "LIMIT".equals(c.ordType)) book.add(t);
                else emit(t.participantId, ev(EngineEvent.EXPIRED, t, t.price, 0));
            }
            return;
        }
        Resting r = null;
        for (Iterator<Resting> it = book.iterator(); it.hasNext();) {
            Resting x = it.next();
            if (x.id == c.orderId) r = x;
        }
        if (r == null || r.participantId != c.participantId) {
            Resting ghost = new Resting();
            ghost.id = c.orderId;
            ghost.clOrdId = c.origClOrdId;
            ghost.symbol = c.symbol;
            EngineEvent e = ev(EngineEvent.REJECTED, ghost, 0, 0);
            e.text = "unknown order";
            emit(c.participantId, e);
            return;
        }
        if (OrderCommand.CANCEL.equals(c.type)) {
            book.remove(r);
            emit(r.participantId, ev(EngineEvent.CANCELED, r, r.price, 0));
        } else {
            r.price = c.price;
            r.leaves = c.qty;
            emit(r.participantId, ev(EngineEvent.REPLACED, r, r.price, 0));
        }
    }
}
