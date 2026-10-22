package fixgw;

/** The gateway's view of the bus: publish to commands.&lt;symbol&gt;, hear back per participant. */
public interface OrderBus {

    interface Listener {
        void onEvent(EngineEvent e);
    }

    void publish(OrderCommand cmd);

    void subscribe(long participantId, Listener l);

    void unsubscribe(long participantId, Listener l);
}
