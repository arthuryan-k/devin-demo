package fixgw;

/** Pre-trade risk, same service the REST gateway calls. Fail closed. */
public interface RiskClient {

    class Decision {
        public boolean approved;
        public String reason;
        public long limitPrice; // protection price for MARKET orders, 0 if none

        public static Decision ok() {
            Decision d = new Decision();
            d.approved = true;
            return d;
        }

        public static Decision no(String reason) {
            Decision d = new Decision();
            d.reason = reason;
            return d;
        }
    }

    Decision check(OrderCommand cmd);

    RiskClient ALLOW_ALL = new RiskClient() {
        public Decision check(OrderCommand cmd) {
            return Decision.ok();
        }
    };
}
