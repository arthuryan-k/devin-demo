package orderbook;

/**
 * Write-ahead sink for commands. {@link MatchingEngine#process(Command)} appends every command here before
 * processing it; if {@code append} throws, the command is not processed.
 */
@FunctionalInterface
public interface CommandLog {

    CommandLog NONE = command -> { };

    void append(Command command);
}
