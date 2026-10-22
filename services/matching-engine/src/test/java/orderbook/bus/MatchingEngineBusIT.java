package orderbook.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import orderbook.exchange.ExchangeCore;
import orderbook.journal.Journal;

/** The matching-engine service against a real Redpanda broker: commands in, sequenced events out. */
@Testcontainers
class MatchingEngineBusIT {

    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v24.2.18");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path journals;

    private final AtomicReference<Throwable> fatal = new AtomicReference<>();
    private EngineService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
        assertNull(fatal.get(), "service failed");
    }

    private EngineService start(String symbol) {
        service = EngineService.start(REDPANDA.getBootstrapServers(), List.of(symbol), journals,
                Journal.Options.FLUSH, 2, 1, fatal::set);
        return service;
    }

    private void restart(String symbol) {
        service.close();
        service = null;
        start(symbol);
    }

    @Test
    void matchesCommandsFromTheBusAndPublishesEventsInSeqOrder() throws Exception {
        start("ACME");
        send("ACME",
                "{\"type\":\"Place\",\"participantId\":11,\"correlationId\":\"s1\",\"orderId\":101,\"side\":\"SELL\","
                        + "\"price\":10100,\"qty\":5}",
                "{\"type\":\"Place\",\"participantId\":12,\"correlationId\":\"s2\",\"orderId\":102,\"side\":\"SELL\","
                        + "\"price\":10000,\"qty\":3}",
                "this is not a command",
                "{\"type\":\"Place\",\"participantId\":13,\"correlationId\":\"b1\",\"side\":\"BUY\",\"price\":10100,"
                        + "\"qty\":6}",
                "{\"type\":\"Amend\",\"participantId\":11,\"correlationId\":\"a1\",\"orderId\":101,\"newQty\":1}",
                "{\"type\":\"Cancel\",\"participantId\":11,\"correlationId\":\"c1\",\"orderId\":101}",
                "{\"type\":\"Cancel\",\"participantId\":11,\"orderId\":101}");

        List<JsonNode> records = read(Topics.events("ACME"), 6);
        assertEquals(6, records.size());
        for (int i = 0; i < records.size(); i++) {
            JsonNode r = records.get(i);
            assertEquals(i, r.get("seq").asLong(), "contiguous global seq");
            assertEquals("ACME", r.get("symbol").asText());
            for (JsonNode e : r.get("events")) {
                assertEquals(i, e.get("seq").asLong(), "every event carries its command's seq");
            }
        }
        assertEquals(List.of("s1", "s2", "b1", "a1", "c1", "null"),
                records.stream().map(r -> r.get("correlationId").asText()).toList());
        assertEquals(List.of(11L, 12L, 13L, 11L, 11L, 11L),
                records.stream().map(r -> r.get("participantId").asLong()).toList());

        JsonNode buy = records.get(2).get("events");
        assertEquals(3, buy.size());
        long buyId = buy.get(0).get("orderId").asLong();
        assertEquals("OrderPlaced", buy.get(0).get("type").asText());
        assertEquals(1, buyId % SymbolBus.ORDER_ID_STRIDE, "engine-assigned id is striped for ACME");
        assertEquals(buyId, records.get(2).get("command").get("orderId").asLong());
        assertTrade(buy.get(1), 102, buyId, 12, 13, 10000, 3);
        assertTrade(buy.get(2), 101, buyId, 11, 13, 10100, 3);

        JsonNode amended = records.get(3).get("events").get(0);
        assertEquals("OrderAmended", amended.get("type").asText());
        assertEquals(2, amended.get("oldQty").asLong());
        assertEquals(1, amended.get("newQty").asLong());
        JsonNode cancelled = records.get(4).get("events").get(0);
        assertEquals("OrderCancelled", cancelled.get("type").asText());
        assertEquals(1, cancelled.get("cancelledQty").asLong());
        JsonNode rejected = records.get(5).get("events").get(0);
        assertEquals("OrderRejected", rejected.get("type").asText());
        assertEquals("UNKNOWN_ORDER_ID", rejected.get("reason").asText());

        List<ConsumerRecord<String, String>> dlq = readRaw(Topics.deadLetter("ACME"), 1);
        assertEquals("this is not a command", dlq.get(0).value());
        assertTrue(new String(dlq.get(0).headers().lastHeader("error").value(), StandardCharsets.UTF_8)
                .startsWith("not JSON"));

        List<JsonNode> md = read(Topics.marketData("ACME"), 7);
        assertEquals("snapshot", md.get(0).get("type").asText());
        assertEquals(-1, md.get(0).get("seq").asLong());
        long expected = 0;
        for (JsonNode m : md.subList(1, md.size())) {
            if (m.get("type").asText().equals("delta")) {
                assertEquals(expected++, m.get("seq").asLong(), "one delta per seq, in order");
            }
        }
        assertEquals(6, expected);
    }

    @Test
    void restartResumesFromTheJournalWithoutRepublishing() throws Exception {
        start("GLOBX");
        send("GLOBX", place(21, "r1", "SELL", 5000, 4), place(22, "r2", "SELL", 5100, 4));
        assertEquals(2, read(Topics.events("GLOBX"), 2).size());

        restart("GLOBX");
        assertEquals(1, service.bus("GLOBX").restoredSeq());
        send("GLOBX", place(23, "r3", "BUY", 5100, 6));

        List<JsonNode> records = read(Topics.events("GLOBX"), 3);
        assertEquals(List.of(0L, 1L, 2L), records.stream().map(r -> r.get("seq").asLong()).toList());
        JsonNode events = records.get(2).get("events");
        assertEquals(3, events.size(), "the resumed book still holds both sells");
        assertEquals(21, events.get(1).get("makerParticipantId").asLong());
        assertEquals(22, events.get(2).get("makerParticipantId").asLong());
    }

    @Test
    void rebuildsALostJournalFromTheCommandsTopic() throws Exception {
        start("INITD");
        send("INITD", place(31, "l1", "BUY", 700, 2), place(32, "l2", "BUY", 710, 2), place(33, "l3", "SELL", 705, 1));
        assertEquals(3, read(Topics.events("INITD"), 3).size());
        service.close();
        service = null;
        deleteRecursively(ExchangeCore.journalDirectory(journals, "INITD"));

        start("INITD");
        assertEquals(-1, service.bus("INITD").restoredSeq());
        send("INITD", place(34, "l4", "SELL", 700, 3));

        List<JsonNode> records = read(Topics.events("INITD"), 4);
        assertEquals(List.of(0L, 1L, 2L, 3L), records.stream().map(r -> r.get("seq").asLong()).toList());
        assertEquals(List.of("l1", "l2", "l3", "l4"),
                records.stream().map(r -> r.get("correlationId").asText()).toList());
        JsonNode last = records.get(3).get("events");
        assertEquals("TradeExecuted", last.get(1).get("type").asText());
        assertEquals(710, last.get(1).get("price").asLong(), "replayed book: 710 bid had 1 left");
        try (Journal journal = new Journal(ExchangeCore.journalDirectory(journals, "INITD"))) {
            assertEquals(3, journal.load().lastSeq(), "journal rebuilt from the bus");
        }
    }

    private static void assertTrade(JsonNode t, long maker, long taker, long makerPid, long takerPid, long price,
            long qty) {
        assertEquals("TradeExecuted", t.get("type").asText());
        assertEquals(maker, t.get("makerOrderId").asLong());
        assertEquals(taker, t.get("takerOrderId").asLong());
        assertEquals(makerPid, t.get("makerParticipantId").asLong());
        assertEquals(takerPid, t.get("takerParticipantId").asLong());
        assertEquals("BUY", t.get("takerSide").asText());
        assertEquals(price, t.get("price").asLong());
        assertEquals(qty, t.get("qty").asLong());
    }

    private static String place(long participant, String correlationId, String side, long price, long qty) {
        return "{\"type\":\"Place\",\"participantId\":" + participant + ",\"correlationId\":\"" + correlationId
                + "\",\"side\":\"" + side + "\",\"price\":" + price + ",\"qty\":" + qty + "}";
    }

    private static void send(String symbol, String... values) throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            for (String v : values) {
                producer.send(new ProducerRecord<>(Topics.commands(symbol), symbol, v)).get();
            }
        }
    }

    private static List<JsonNode> read(String topic, int atLeast) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (ConsumerRecord<String, String> r : readRaw(topic, atLeast)) {
            out.add(JSON.readTree(r.value()));
        }
        return out;
    }

    /** Everything on partition 0 once at least {@code atLeast} records arrived (and a short quiet period). */
    private static List<ConsumerRecord<String, String>> readRaw(String topic, int atLeast) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            TopicPartition tp = new TopicPartition(topic, 0);
            c.assign(List.of(tp));
            c.seekToBeginning(List.of(tp));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            long quietUntil = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(deadline, quietUntil)) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                    assertEquals("GLOBX ACME INITD".contains(r.key()) ? r.key() : "?", r.key(), "keyed by symbol");
                    out.add(r);
                }
                if (out.size() >= atLeast && quietUntil == Long.MAX_VALUE) {
                    quietUntil = System.nanoTime() + Duration.ofMillis(750).toNanos();
                }
            }
        }
        return out;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
