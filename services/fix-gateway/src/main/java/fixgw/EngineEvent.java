package fixgw;

/** An engine outcome for one participant's order, as read back off events.&lt;symbol&gt;. */
public class EngineEvent {
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String FILL = "FILL";
    public static final String CANCELED = "CANCELED";
    public static final String REPLACED = "REPLACED";
    public static final String EXPIRED = "EXPIRED";

    public String type;
    public long participantId;
    public String clOrdId;
    public long orderId;
    public String symbol;
    public String side;
    public long price;     // order price, or the fill price for FILL
    public long qty;       // fill qty for FILL, order qty otherwise
    public long leavesQty;
    public String text;
}
