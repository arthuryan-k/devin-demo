package orderbook.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import orderbook.BookState;
import orderbook.Command;
import orderbook.CommandLog;
import orderbook.EngineState;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.Side;
import orderbook.TimeInForce;

class SnapshotJournalTest {

    private static final CommandLog NO_LOG = c -> { };

    @TempDir
    Path dir;

    private static List<Command> book(int n, long firstId) {
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long id = firstId + i;
            Side side = i % 2 == 0 ? Side.BUY : Side.SELL;
            long price = side == Side.BUY ? 95 + i % 5 : 101 + i % 5;
            commands.add(new Command.Place(Order.limit(id, 1 + i % 3, side, price, 5 + i % 4, TimeInForce.GTC)));
            if (i % 5 == 4) {
                commands.add(new Command.Cancel(id - 2));
            }
            if (i % 7 == 6) {
                commands.add(new Command.Place(Order.limit(id + 10_000, 9, Side.BUY, 102, 3, TimeInForce.IOC)));
            }
        }
        return commands;
    }

    private static MatchingEngine replayAll(List<Command> commands) {
        return MatchingEngine.replay(commands, NO_LOG);
    }

    private static void assertSameState(MatchingEngine expected, MatchingEngine actual) {
        assertEquals(BookState.of(expected.book()), BookState.of(actual.book()));
        assertEquals(expected.exportState(), actual.exportState());
        assertEquals(expected.nextSeqNum(), actual.nextSeqNum());
        assertEquals(expected.nextOrderId(), actual.nextOrderId());
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void snapshotCodecRoundTripsFullEngineState() {
        MatchingEngine engine = replayAll(book(40, 1));
        EngineState state = engine.exportState();
        assertTrue(!state.bids().isEmpty() && !state.asks().isEmpty());
        Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(new Snapshot(41, state)));
        assertEquals(41, decoded.seq());
        assertEquals(state, decoded.state());
        assertSameState(engine, decoded.restore(NO_LOG));
    }

    @Test
    void snapshotThenTailRecoversTheSameStateAsFullReplay() throws IOException {
        List<Command> before = book(30, 1);
        List<Command> after = book(25, 500);
        List<Command> all = new ArrayList<>(before);
        all.addAll(after);
        try (Journal journal = new Journal(dir)) {
            MatchingEngine engine = new MatchingEngine(journal);
            before.forEach(engine::process);
            journal.snapshot(engine);
            after.forEach(engine::process);
        }

        Journal.Recovery recovery = new Journal(dir).load();
        assertNotNull(recovery.snapshot());
        assertEquals(before.size() - 1, recovery.snapshot().seq());
        assertEquals(after.stream().map(CommandCodec::encode).toList(),
                recovery.tailCommands().stream().map(CommandCodec::encode).toList(),
                "only post-snapshot commands are replayed");
        assertEquals(before.size(), recovery.tail().get(0).seq());
        assertSameState(replayAll(all), recovery.toEngine(NO_LOG));
    }

    @Test
    void recoveredEngineKeepsJournalingAfterTheTail() throws IOException {
        try (Journal journal = new Journal(dir)) {
            MatchingEngine engine = new MatchingEngine(journal);
            book(10, 1).forEach(engine::process);
            journal.snapshot(engine);
            book(5, 100).forEach(engine::process);
        }
        try (Journal journal = new Journal(dir)) {
            MatchingEngine recovered = journal.recover();
            recovered.process(new Command.Cancel(100));
        }
        List<Command> expected = new ArrayList<>(book(10, 1));
        expected.addAll(book(5, 100));
        expected.add(new Command.Cancel(100));
        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(book(5, 100).size() + 1, recovery.tail().size());
        assertSameState(replayAll(expected), recovery.toEngine(NO_LOG));
    }

    @Test
    void noSnapshotFallsBackToFullReplay() throws IOException {
        List<Command> commands = book(20, 1);
        try (Journal journal = new Journal(dir)) {
            commands.forEach(journal::append);
        }
        Journal.Recovery recovery = new Journal(dir).load();
        assertNull(recovery.snapshot());
        assertEquals(commands.size(), recovery.tail().size());
        assertSameState(replayAll(commands), new Journal(dir).recover());
    }

    @Test
    void snapshotRotatesToANewSegmentAndRecoverySpansSegments() throws IOException {
        List<Command> a = book(8, 1);
        List<Command> b = book(6, 100);
        List<Command> c = book(4, 200);
        try (Journal journal = new Journal(dir)) {
            MatchingEngine engine = new MatchingEngine(journal);
            a.forEach(engine::process);
            journal.snapshot(engine);
            b.forEach(engine::process);
            journal.snapshot(engine);
            c.forEach(engine::process);
        }
        long s1 = a.size() - 1;
        long s2 = s1 + b.size();
        assertEquals(List.of("journal-000001.log", "journal-000002.log", "journal-000003.log",
                String.format("snapshot-%020d.json", s1), String.format("snapshot-%020d.json", s2)), files());
        assertEquals("#orderbook-journal v2 base=" + s1,
                Files.readAllLines(dir.resolve("journal-000002.log")).get(0));
        assertEquals(b.size() + 1, Files.readAllLines(dir.resolve("journal-000002.log")).size(),
                "post-snapshot commands land in the new segment");
        assertEquals(c.size() + 1, Files.readAllLines(dir.resolve("journal-000003.log")).size());

        List<Command> all = new ArrayList<>(a);
        all.addAll(b);
        all.addAll(c);
        assertSameState(replayAll(all), new Journal(dir).recover());

        // Without the newest snapshot, recovery uses the older one and spans two segments.
        Files.delete(dir.resolve(String.format("snapshot-%020d.json", s2)));
        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(s1, recovery.snapshot().seq());
        assertEquals(b.size() + c.size(), recovery.tail().size());
        assertSameState(replayAll(all), recovery.toEngine(NO_LOG));

        // Without any snapshot, every segment is replayed.
        Files.delete(dir.resolve(String.format("snapshot-%020d.json", s1)));
        assertSameState(replayAll(all), new Journal(dir).recover());
    }

    @Test
    void pruneDeletesOnlyFullyCoveredSegmentsAndOlderSnapshots() throws IOException {
        List<Command> all = new ArrayList<>();
        try (Journal journal = new Journal(dir)) {
            MatchingEngine engine = new MatchingEngine(journal);
            for (int round = 0; round < 3; round++) {
                List<Command> cmds = book(5, 1 + round * 100L);
                cmds.forEach(engine::process);
                all.addAll(cmds);
                journal.snapshot(engine);
            }
            List<Command> tail = book(3, 1000);
            tail.forEach(engine::process);
            all.addAll(tail);
            assertEquals(3 + 2, journal.pruneCoveredSegments(), "three covered segments, two older snapshots");
        }
        assertEquals(1, files().stream().filter(f -> f.startsWith("journal-")).count());
        assertEquals(1, files().stream().filter(f -> f.startsWith("snapshot-")).count());
        assertSameState(replayAll(all), new Journal(dir).recover());
    }

    @Test
    void crcMismatchInTheMiddleOfASegmentIsCorruption() throws IOException {
        try (Journal journal = new Journal(dir)) {
            book(5, 1).forEach(journal::append);
        }
        Path segment = dir.resolve("journal-000001.log");
        byte[] bytes = Files.readAllBytes(segment);
        int line3 = nthLineStart(bytes, 3);
        int target = indexOf(bytes, "\"price\":", line3) + 8;
        bytes[target] = (byte) (bytes[target] == '9' ? '8' : '9');
        Files.write(segment, bytes);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new Journal(dir).load());
        assertTrue(e.getMessage().contains("CRC"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> new Journal(dir).append(new Command.Cancel(1)),
                "a corrupt journal is not appended to");
    }

    @Test
    void corruptOrTornTailLineIsTruncatedAndStillRecoverable() throws IOException {
        List<Command> commands = book(6, 1);
        try (Journal journal = new Journal(dir)) {
            commands.forEach(journal::append);
        }
        Path segment = dir.resolve("journal-000001.log");
        byte[] bytes = Files.readAllBytes(segment);
        int last = nthLineStart(bytes, countLines(bytes) - 1);
        bytes[last + 12] ^= 0x01;
        Files.write(segment, bytes);

        List<Command> survivors = commands.subList(0, commands.size() - 1);
        assertEquals(survivors.size(), new Journal(dir).load().tail().size());
        try (Journal journal = new Journal(dir)) {
            assertEquals(survivors.size() - 1, journal.lastSeq());
            journal.append(new Command.Cancel(1));
        }
        assertEquals(1 + survivors.size() + 1, Files.readAllLines(segment).size(), "bad tail line was truncated");

        Files.writeString(segment, "deadbe", StandardOpenOption.APPEND);
        List<Command> expected = new ArrayList<>(survivors);
        expected.add(new Command.Cancel(1));
        assertSameState(replayAll(expected), new Journal(dir).recover());
    }

    @Test
    void crcIsOverTheJsonPayload() throws IOException {
        try (Journal journal = new Journal(dir)) {
            journal.append(new Command.Cancel(7));
        }
        String line = Files.readAllLines(dir.resolve("journal-000001.log"), StandardCharsets.UTF_8).get(1);
        String json = "{\"seq\":0,\"cmd\":\"cancel\",\"id\":7}";
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(String.format("%08x", crc.getValue()) + ":" + json, line);
    }

    @Test
    void fsyncModeSurvivesAWriterThatIsNeverClosed() throws IOException {
        List<Command> commands = book(50, 1);
        Journal crashed = new Journal(dir, Journal.Options.fsync(8, 60_000));
        MatchingEngine engine = new MatchingEngine(crashed);
        commands.forEach(engine::process);
        long forcesBeforeSnapshot = crashed.fsyncCount();
        assertTrue(forcesBeforeSnapshot >= commands.size() / 8, "batched every 8 commands: " + forcesBeforeSnapshot);
        assertTrue(forcesBeforeSnapshot <= commands.size() / 8 + 1, "batched, not per line: " + forcesBeforeSnapshot);
        // "Crash": the writer is abandoned without close() or a final force.

        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(commands.size(), recovery.tail().size());
        assertSameState(engine, recovery.toEngine(NO_LOG));
    }

    @Test
    void fsyncTimerForcesAQuietJournal() throws Exception {
        Journal journal = new Journal(dir, Journal.Options.fsync(1_000, 20));
        journal.append(new Command.Cancel(1));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (journal.fsyncCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, journal.fsyncCount(), "a lone command is forced within ~T ms");
        journal.close();
    }

    @Test
    void flushModeNeverForcesSegmentsButSurvivesAnAbandonedWriter() {
        Journal abandoned = new Journal(dir);
        List<Command> commands = book(10, 1);
        commands.forEach(abandoned::append);
        assertEquals(0, abandoned.fsyncCount());
        assertEquals(commands.size(), new Journal(dir).load().tail().size());
    }

    @Test
    void appendsMustBeSequencedAfterTheLastEntryAndSnapshot() {
        try (Journal journal = new Journal(dir)) {
            journal.append(5, new Command.Cancel(1));
            assertThrows(IllegalArgumentException.class, () -> journal.append(5, new Command.Cancel(2)));
            journal.writeSnapshot(9, Journal.emptyState());
            assertThrows(IllegalArgumentException.class, () -> journal.append(9, new Command.Cancel(2)));
            assertThrows(IllegalArgumentException.class, () -> journal.writeSnapshot(8, Journal.emptyState()));
            journal.append(10, new Command.Cancel(3));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        Journal.Recovery recovery = new Journal(dir).load();
        assertEquals(9, recovery.snapshot().seq());
        assertEquals(List.of(new CommandCodec.Sequenced(10, new Command.Cancel(3))), recovery.tail());
    }

    private static int countLines(byte[] bytes) {
        int n = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                n++;
            }
        }
        return n;
    }

    /** Offset of the start of 0-based line {@code n}. */
    private static int nthLineStart(byte[] bytes, int n) {
        int pos = 0;
        for (int i = 0; i < n; i++) {
            while (bytes[pos] != '\n') {
                pos++;
            }
            pos++;
        }
        return pos;
    }

    private static int indexOf(byte[] bytes, String s, int from) {
        return new String(bytes, StandardCharsets.UTF_8).indexOf(s, from);
    }
}
