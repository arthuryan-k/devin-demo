package simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A participant's own resting orders as its client knows them: added from place replies, updated from amend/cancel
 * replies and maker-side trade prints. Thread-safe (replies arrive on the client loop, prints on the feed thread).
 */
final class OrderTracker {

    private final Map<Long, OwnOrder> resting = new LinkedHashMap<>();

    synchronized void placed(long orderId, Side side, long price, long restingQty) {
        if (orderId >= 0 && restingQty > 0) {
            resting.put(orderId, new OwnOrder(orderId, side, price, restingQty));
        }
    }

    synchronized void amended(long orderId, Long newPrice, Long newQty) {
        OwnOrder o = resting.get(orderId);
        if (o == null) {
            return;
        }
        if (newPrice != null) {
            // A re-price loses time priority: re-insert at the back.
            resting.remove(orderId);
        }
        resting.put(orderId, new OwnOrder(orderId, o.side(), newPrice != null ? newPrice : o.price(),
                newQty != null ? newQty : o.qtyRemaining()));
    }

    synchronized void removed(long orderId) {
        resting.remove(orderId);
    }

    /** A maker-side fill of {@code qty}; returns the order's side, or null if it is not one of ours. */
    synchronized Side filled(long orderId, long qty) {
        OwnOrder o = resting.get(orderId);
        if (o == null) {
            return null;
        }
        long left = o.qtyRemaining() - qty;
        if (left <= 0) {
            resting.remove(orderId);
        } else {
            resting.put(orderId, new OwnOrder(orderId, o.side(), o.price(), left));
        }
        return o.side();
    }

    synchronized boolean owns(long orderId) {
        return resting.containsKey(orderId);
    }

    /** Resting orders, bids first, each side in the order they were (re-)priced. */
    synchronized List<OwnOrder> snapshot() {
        List<OwnOrder> out = new ArrayList<>();
        for (Side side : Side.values()) {
            for (OwnOrder o : resting.values()) {
                if (o.side() == side) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
