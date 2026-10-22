package fixgw;

/** What the gateway publishes on commands.&lt;symbol&gt;. Public fields, no builders: this is the old service. */
public class OrderCommand {
    public static final String NEW = "NEW";
    public static final String CANCEL = "CANCEL";
    public static final String REPLACE = "REPLACE";

    public String type;
    public long participantId;
    public String clOrdId;
    public String origClOrdId;
    public long orderId;      // engine order id, for CANCEL/REPLACE
    public String symbol;
    public String side;       // BUY / SELL
    public String ordType;    // LIMIT / MARKET
    public long price;        // ticks; 0 for market
    public long qty;
    public String tif;        // GTC / IOC / FOK

    public String toString() {
        return type + " " + symbol + " " + side + " " + ordType + " " + price + "x" + qty + " p=" + participantId
                + " cl=" + clOrdId + " id=" + orderId;
    }
}
