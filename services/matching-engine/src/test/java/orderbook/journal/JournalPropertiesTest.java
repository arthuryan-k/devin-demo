package orderbook.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import orderbook.BookState;
import orderbook.Command;
import orderbook.CommandArbitraries;
import orderbook.Event;
import orderbook.MatchingEngine;

class JournalPropertiesTest {

    /** Random commands → journal file → fresh engine replay: same commands, events, book and counters. */
    @Property(tries = 200)
    void replayRebuildsIdenticalState(@ForAll("commands") List<Command> commands) throws IOException {
        Path file = Files.createTempDirectory("journal");
        try {
            List<Event> originalEvents = new ArrayList<>();
            MatchingEngine original;
            try (Journal journal = new Journal(file)) {
                original = new MatchingEngine(journal);
                for (Command c : commands) {
                    originalEvents.addAll(original.process(c));
                }
            }

            List<Command> replayed = new Journal(file).replay();
            assertEquals(commands.stream().map(CommandCodec::encode).toList(),
                    replayed.stream().map(CommandCodec::encode).toList());

            MatchingEngine fresh = new MatchingEngine();
            List<Event> replayedEvents = new ArrayList<>();
            for (Command c : replayed) {
                replayedEvents.addAll(fresh.process(c));
            }
            assertEquals(originalEvents, replayedEvents);
            assertEquals(BookState.of(original.book()), BookState.of(fresh.book()));
            assertEquals(original.nextSeqNum(), fresh.nextSeqNum());
            assertEquals(original.nextOrderId(), fresh.nextOrderId());
        } finally {
            deleteRecursively(file);
        }
    }

    /** Snapshot at a random point, keep going, recover: same book and counters as replaying everything. */
    @Property(tries = 200)
    void snapshotPlusTailRecoversTheSameStateAsFullReplay(@ForAll("commands") List<Command> commands,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 200) int split) throws IOException {
        Path dir = Files.createTempDirectory("journal");
        int at = Math.min(split, commands.size());
        try {
            MatchingEngine original;
            try (Journal journal = new Journal(dir)) {
                original = new MatchingEngine(journal);
                commands.subList(0, at).forEach(original::process);
                if (at > 0) {
                    journal.snapshot(original);
                }
                commands.subList(at, commands.size()).forEach(original::process);
            }
            Journal.Recovery recovery = new Journal(dir).load();
            assertEquals(commands.size() - at, at > 0 ? recovery.tail().size() : commands.size() - at);
            assertEquals(at > 0, recovery.snapshot() != null);
            MatchingEngine recovered = recovery.toEngine(c -> { });
            MatchingEngine full = MatchingEngine.replay(commands, c -> { });
            assertEquals(BookState.of(full.book()), BookState.of(recovered.book()));
            assertEquals(BookState.of(original.book()), BookState.of(recovered.book()));
            assertEquals(full.exportState(), recovered.exportState());
            assertEquals(full.nextSeqNum(), recovered.nextSeqNum());
            assertEquals(full.nextOrderId(), recovered.nextOrderId());
        } finally {
            deleteRecursively(dir);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    @Provide
    Arbitrary<List<Command>> commands() {
        return CommandArbitraries.allCommands(200);
    }
}
