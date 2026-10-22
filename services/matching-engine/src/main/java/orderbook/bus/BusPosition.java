package orderbook.bus;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads where an {@code events.<symbol>} topic ends, for recovery. Each seq has exactly one record there. */
final class BusPosition {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private BusPosition() {
    }

    /** The last events record: its seq, the command offset it came from, and its own topic offset. */
    record Last(long seq, long commandOffset, long topicOffset, long firstTopicOffset) {
    }

    /** The newest record of {@code topic}, or null if the topic is empty or missing. */
    static Last lastEvents(String bootstrap, String topic) {
        try (KafkaConsumer<String, String> c = reader(bootstrap)) {
            TopicPartition tp = new TopicPartition(topic, 0);
            if (c.partitionsFor(topic, TIMEOUT).isEmpty()) {
                return null;
            }
            long end = c.endOffsets(List.of(tp), TIMEOUT).get(tp);
            long begin = c.beginningOffsets(List.of(tp), TIMEOUT).get(tp);
            if (end <= begin) {
                return null;
            }
            BusCodec.EventsRecord r = read(c, tp, end - 1);
            return new Last(r.seq(), r.commandOffset(), end - 1, begin);
        }
    }

    /** {@code commandOffset} of the events record for {@code seq} (at or before {@code last}). */
    static long commandOffsetAt(String bootstrap, String topic, Last last, long seq) {
        if (seq == last.seq()) {
            return last.commandOffset();
        }
        long offset = last.topicOffset() - (last.seq() - seq);
        if (offset < last.firstTopicOffset()) {
            throw new IllegalStateException(topic + ": events record for seq " + seq + " is no longer retained");
        }
        try (KafkaConsumer<String, String> c = reader(bootstrap)) {
            BusCodec.EventsRecord r = read(c, new TopicPartition(topic, 0), offset);
            if (r.seq() != seq) {
                throw new IllegalStateException(topic + ": offset " + offset + " holds seq " + r.seq() + ", not " + seq);
            }
            return r.commandOffset();
        }
    }

    private static BusCodec.EventsRecord read(KafkaConsumer<String, String> c, TopicPartition tp, long offset) {
        c.assign(List.of(tp));
        c.seek(tp, offset);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> rec : c.poll(Duration.ofMillis(200))) {
                if (rec.offset() == offset) {
                    return BusCodec.decodeEvents(rec.value());
                }
            }
        }
        throw new IllegalStateException("timed out reading " + tp + " at offset " + offset);
    }

    private static KafkaConsumer<String, String> reader(String bootstrap) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(p);
    }
}
