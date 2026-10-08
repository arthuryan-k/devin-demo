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
        Path file = Files.createTempFile("journal", ".jsonl");
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
            Files.deleteIfExists(file);
        }
    }

    @Provide
    Arbitrary<List<Command>> commands() {
        return CommandArbitraries.allCommands(200);
    }
}
