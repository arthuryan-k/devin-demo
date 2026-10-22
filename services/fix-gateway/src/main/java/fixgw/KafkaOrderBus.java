package fixgw;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The real bus: writes commands.&lt;symbol&gt; records (schemas/bus/commands.schema.json) with the FIX ClOrdID as
 * correlationId, and tails events.&lt;symbol&gt; from the end, turning each envelope into per-participant
 * EngineEvents. The same topics the REST gateway uses, so FIX and REST orders meet in the same book.
 */
public class KafkaOrderBus implements OrderBus {

    private static final Logger LOG = Logger.getLogger(KafkaOrderBus.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KafkaProducer producer;
    private final KafkaConsumer consumer;
    private final Map listeners = new HashMap(); // Long participantId -> List<Listener>
    private final Thread poller;
    private volatile boolean running = true;

    public KafkaOrderBus(String bootstrap, List symbols) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        producer = new KafkaProducer(p);

        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumer = new KafkaConsumer(c);
        List parts = new ArrayList();
        for (Iterator it = symbols.iterator(); it.hasNext();) {
            parts.add(new TopicPartition("events." + it.next(), 0));
        }
        consumer.assign(parts);
        consumer.seekToEnd(parts);
        for (Iterator it = parts.iterator(); it.hasNext();) {
            consumer.position((TopicPartition) it.next()); // resolve "end" now, before any order goes out
        }
        poller = new Thread(new Runnable() {
            public void run() {
                poll();
            }
        }, "fix-events");
        poller.setDaemon(true);
        poller.start();
    }

    public void publish(OrderCommand cmd) {
        ObjectNode n = JSON.createObjectNode();
        n.put("v", 1);
        n.put("participantId", cmd.participantId);
        n.put("correlationId", cmd.clOrdId);
        n.put("symbol", cmd.symbol);
        if (OrderCommand.NEW.equals(cmd.type)) {
            n.put("type", "Place");
            if (cmd.orderId > 0) n.put("orderId", cmd.orderId);
            n.put("side", cmd.side);
            n.put("orderType", cmd.ordType);
            n.put("timeInForce", cmd.tif);
            if ("LIMIT".equals(cmd.ordType)) n.put("price", cmd.price);
            n.put("qty", cmd.qty);
        } else if (OrderCommand.CANCEL.equals(cmd.type)) {
            n.put("type", "Cancel");
            n.put("orderId", cmd.orderId);
        } else {
            n.put("type", "Amend");
            n.put("orderId", cmd.orderId);
            n.put("newPrice", cmd.price);
            n.put("newQty", cmd.qty);
        }
        producer.send(new ProducerRecord("commands." + cmd.symbol, cmd.symbol, n.toString()));
        producer.flush();
    }

    public void subscribe(long participantId, Listener l) {
        synchronized (listeners) {
            List ls = (List) listeners.get(Long.valueOf(participantId));
            if (ls == null) {
                ls = new ArrayList();
                listeners.put(Long.valueOf(participantId), ls);
            }
            ls.add(l);
        }
    }

    public void unsubscribe(long participantId, Listener l) {
        synchronized (listeners) {
            List ls = (List) listeners.get(Long.valueOf(participantId));
            if (ls != null) ls.remove(l);
        }
    }

    private void poll() {
        while (running) {
            try {
                ConsumerRecords recs = consumer.poll(Duration.ofMillis(200));
                for (Iterator it = recs.iterator(); it.hasNext();) {
                    ConsumerRecord r = (ConsumerRecord) it.next();
                    try {
                        List out = translate(JSON.readTree((String) r.value()));
                        for (int i = 0; i < out.size(); i++) deliver((EngineEvent) out.get(i));
                    } catch (Exception e) {
                        LOG.warning("bad events record at " + r.topic() + "@" + r.offset() + ": " + e);
                    }
                }
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                break;
            } catch (RuntimeException e) {
                LOG.warning("events poll: " + e);
            }
        }
        consumer.close();
    }

    private void deliver(EngineEvent e) {
        List ls;
        synchronized (listeners) {
            List found = (List) listeners.get(Long.valueOf(e.participantId));
            ls = found == null ? null : new ArrayList(found);
        }
        if (ls == null) return;
        for (int i = 0; i < ls.size(); i++) ((Listener) ls.get(i)).onEvent(e);
    }

    /** One events.&lt;symbol&gt; envelope -> EngineEvents for whoever they concern. Public for tests. */
    public static List translate(JsonNode env) {
        List out = new ArrayList();
        String symbol = env.path("symbol").asText();
        long owner = env.path("participantId").asLong();
        String corr = env.path("correlationId").isNull() ? null : env.path("correlationId").asText(null);
        JsonNode cmd = env.path("command");
        JsonNode events = env.path("events");
        for (int i = 0; i < events.size(); i++) {
            JsonNode ev = events.get(i);
            String type = ev.path("type").asText();
            EngineEvent e = new EngineEvent();
            e.symbol = symbol;
            e.participantId = owner;
            e.clOrdId = corr;
            e.orderId = ev.path("orderId").asLong(cmd.path("orderId").asLong(0));
            if ("OrderPlaced".equals(type)) {
                e.type = EngineEvent.ACCEPTED;
                e.side = ev.path("side").asText();
                e.price = ev.path("price").asLong(0);
                e.qty = ev.path("qty").asLong();
                e.leavesQty = e.qty;
            } else if ("OrderRejected".equals(type)) {
                e.type = EngineEvent.REJECTED;
                e.text = ev.path("reason").asText(null);
            } else if ("OrderCancelled".equals(type)) {
                String reason = ev.path("reason").asText("");
                e.type = "Cancel".equals(cmd.path("type").asText()) ? EngineEvent.CANCELED : EngineEvent.EXPIRED;
                e.text = reason;
            } else if ("OrderAmended".equals(type)) {
                e.type = EngineEvent.REPLACED;
                e.price = ev.path("newPrice").asLong();
                e.leavesQty = ev.path("newQty").asLong();
            } else if ("TradeExecuted".equals(type)) {
                long px = ev.path("price").asLong();
                long q = ev.path("qty").asLong();
                EngineEvent maker = new EngineEvent();
                maker.type = EngineEvent.FILL;
                maker.symbol = symbol;
                maker.participantId = ev.path("makerParticipantId").asLong();
                maker.orderId = ev.path("makerOrderId").asLong();
                maker.price = px;
                maker.qty = q;
                out.add(maker);
                e.type = EngineEvent.FILL;
                e.participantId = ev.path("takerParticipantId").asLong();
                e.orderId = ev.path("takerOrderId").asLong();
                e.clOrdId = e.participantId == owner ? corr : null;
                e.price = px;
                e.qty = q;
            } else {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    public void close() {
        running = false;
        consumer.wakeup();
        producer.close();
    }
}
