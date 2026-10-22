package orderbook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Resting orders for a single instrument. Not thread-safe: single-threaded by design.
 *
 * <p>Each side maps price to a FIFO queue of orders. Both maps are ordered so that {@code firstKey()} is the best
 * price (lowest ask, highest bid). Empty price levels are removed eagerly, so every level present is non-empty.
 */
public final class OrderBook {

    /** Aggregated view of one price level. */
    public record Level(long price, long totalQty, int orderCount) {
    }

    /** Top-of-book snapshot; each list is ordered best price first. */
    public record Depth(List<Level> bids, List<Level> asks) {
    }

    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();
    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final Map<Long, Order> orderIndex = new HashMap<>();

    public OptionalLong bestBid() {
        return bestPrice(Side.BUY);
    }

    public OptionalLong bestAsk() {
        return bestPrice(Side.SELL);
    }

    public OptionalLong bestPrice(Side side) {
        NavigableMap<Long, Deque<Order>> levels = levels(side);
        return levels.isEmpty() ? OptionalLong.empty() : OptionalLong.of(levels.firstKey());
    }

    /** Up to {@code n} best levels per side; empty lists if {@code n <= 0} or the side is empty. */
    public Depth depth(int n) {
        return new Depth(depth(Side.BUY, n), depth(Side.SELL, n));
    }

    public List<Level> depth(Side side, int n) {
        if (n <= 0) {
            return List.of();
        }
        List<Level> result = new ArrayList<>(Math.min(n, levels(side).size()));
        for (Map.Entry<Long, Deque<Order>> entry : levels(side).entrySet()) {
            if (result.size() == n) {
                break;
            }
            long total = 0;
            for (Order order : entry.getValue()) {
                total += order.qtyRemaining();
            }
            result.add(new Level(entry.getKey(), total, entry.getValue().size()));
        }
        return List.copyOf(result);
    }

    public Optional<Order> find(long orderId) {
        return Optional.ofNullable(orderIndex.get(orderId));
    }

    public boolean contains(long orderId) {
        return orderIndex.containsKey(orderId);
    }

    public int size() {
        return orderIndex.size();
    }

    public boolean isEmpty() {
        return orderIndex.isEmpty();
    }

    /** True if the best bid is at or above the best ask. Must never hold between engine commands. */
    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
    }

    /** Snapshot of resting orders on one side in priority order (best price first, then FIFO). */
    public List<Order> orders(Side side) {
        List<Order> result = new ArrayList<>();
        for (Deque<Order> queue : levels(side).values()) {
            result.addAll(queue);
        }
        return Collections.unmodifiableList(result);
    }

    void add(Order order) {
        if (orderIndex.putIfAbsent(order.id(), order) != null) {
            throw new IllegalStateException("duplicate order id " + order.id());
        }
        levels(order.side()).computeIfAbsent(order.price(), p -> new ArrayDeque<>()).addLast(order);
    }

    /** Removes a resting order by ID, dropping its price level if it becomes empty. */
    Optional<Order> remove(long orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) {
            return Optional.empty();
        }
        NavigableMap<Long, Deque<Order>> levels = levels(order.side());
        Deque<Order> queue = levels.get(order.price());
        queue.remove(order);
        if (queue.isEmpty()) {
            levels.remove(order.price());
        }
        return Optional.of(order);
    }

    /** Front of the best price level on {@code side}. Re-reads the best key on every call. */
    Optional<Order> peekBest(Side side) {
        NavigableMap<Long, Deque<Order>> levels = levels(side);
        return levels.isEmpty() ? Optional.empty() : Optional.of(levels.firstEntry().getValue().peekFirst());
    }

    /** Removes the front of the best price level on {@code side}, dropping the level if it becomes empty. */
    Order pollBest(Side side) {
        NavigableMap<Long, Deque<Order>> levels = levels(side);
        Map.Entry<Long, Deque<Order>> best = levels.firstEntry();
        if (best == null) {
            throw new IllegalStateException("no resting orders on " + side);
        }
        Order order = best.getValue().pollFirst();
        if (best.getValue().isEmpty()) {
            levels.remove(best.getKey());
        }
        orderIndex.remove(order.id());
        return order;
    }

    /** Lazily walks resting orders on {@code side} in priority order (best price first, then FIFO). */
    Iterator<Order> priorityIterator(Side side) {
        return levels(side).values().stream().flatMap(Deque::stream).iterator();
    }

    private NavigableMap<Long, Deque<Order>> levels(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
