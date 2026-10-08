package orderbook.journal;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import orderbook.Command;
import orderbook.CommandLog;
import orderbook.MatchingEngine;

/**
 * Append-only command journal (event sourcing input log), stored as JSON Lines: one {@link CommandCodec}-encoded
 * command per line. Pass it to {@link MatchingEngine#MatchingEngine(CommandLog)} so every command is written
 * before it is processed, and rebuild state with {@link #recover()}.
 *
 * <p>Each append is flushed to the OS but not fsynced, so it survives a process crash but not necessarily a power
 * loss. A final line without a trailing newline is treated as a torn write: {@link #replay()} ignores it and the
 * first {@link #append} truncates it away. Not thread-safe.
 */
public final class Journal implements CommandLog, Closeable {

    private final Path path;
    private BufferedWriter writer;

    public Journal(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() {
        return path;
    }

    @Override
    public void append(Command command) {
        String line = CommandCodec.encode(command);
        try {
            if (writer == null) {
                truncateTornTail();
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            }
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to journal " + command, e);
        }
    }

    /** All complete journaled commands, in append order. Empty if the file does not exist. */
    public List<Command> replay() {
        if (!Files.exists(path)) {
            return List.of();
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read journal " + path, e);
        }
        List<Command> commands = new ArrayList<>();
        int start = 0;
        int lineNo = 1;
        for (int nl = content.indexOf('\n'); nl >= 0; nl = content.indexOf('\n', start), lineNo++) {
            String line = content.substring(start, nl);
            start = nl + 1;
            if (line.isBlank()) {
                continue;
            }
            try {
                commands.add(CommandCodec.decode(line));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("corrupt journal " + path + " at line " + lineNo, e);
            }
        }
        return List.copyOf(commands);
    }

    /** A fresh engine rebuilt from this journal, which keeps journaling to it. */
    public MatchingEngine recover() {
        return MatchingEngine.replay(replay(), this);
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private void truncateTornTail() throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long end = ch.size();
            ByteBuffer one = ByteBuffer.allocate(1);
            long keep = end;
            while (keep > 0) {
                one.clear();
                ch.read(one, keep - 1);
                if (one.get(0) == '\n') {
                    break;
                }
                keep--;
            }
            if (keep < end) {
                ch.truncate(keep);
            }
        }
    }
}
