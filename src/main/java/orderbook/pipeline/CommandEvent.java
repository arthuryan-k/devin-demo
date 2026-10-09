package orderbook.pipeline;

import orderbook.Command;

/**
 * Preallocated slot of the input ring. Producers fill it inside {@code tryPublishEvent}; the engine handler reads it.
 * The slot's ring sequence is stamped into {@link #seq()} and is the command's global {@code seqNum}.
 */
public final class CommandEvent {

    long seq = -1;
    long participantId;
    Command command;
    boolean reset;
    boolean awaited;

    void set(long seq, long participantId, Command command, boolean reset, boolean awaited) {
        this.seq = seq;
        this.participantId = participantId;
        this.command = command;
        this.reset = reset;
        this.awaited = awaited;
    }

    public long seq() {
        return seq;
    }

    public long participantId() {
        return participantId;
    }

    /** The command, or null for a reset. */
    public Command command() {
        return command;
    }

    public boolean isReset() {
        return reset;
    }
}
