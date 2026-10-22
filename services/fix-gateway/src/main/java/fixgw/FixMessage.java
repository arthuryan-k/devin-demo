package fixgw;

import java.util.ArrayList;
import java.util.List;

/**
 * A FIX tag=value message. Old-school: fields kept in arrival order in two parallel lists, SOH separated,
 * BodyLength (9) and CheckSum (10) computed on the way out and verified on the way in.
 */
public class FixMessage {

    public static final char SOH = '\u0001';
    public static final String BEGIN_STRING = "FIX.4.4";

    private List tags = new ArrayList();   // Integer
    private List values = new ArrayList(); // String

    public FixMessage() {
    }

    public FixMessage(String msgType) {
        set(Tags.MsgType, msgType);
    }

    public FixMessage set(int tag, String value) {
        for (int i = 0; i < tags.size(); i++) {
            if (((Integer) tags.get(i)).intValue() == tag) {
                values.set(i, value);
                return this;
            }
        }
        tags.add(Integer.valueOf(tag));
        values.add(value);
        return this;
    }

    public FixMessage set(int tag, long value) {
        return set(tag, String.valueOf(value));
    }

    public String get(int tag) {
        for (int i = 0; i < tags.size(); i++) {
            if (((Integer) tags.get(i)).intValue() == tag) {
                return (String) values.get(i);
            }
        }
        return null;
    }

    public boolean has(int tag) {
        return get(tag) != null;
    }

    public String getMsgType() {
        return get(Tags.MsgType);
    }

    public int getInt(int tag, int dflt) {
        String v = get(tag);
        if (v == null) return dflt;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Encodes with header fields 8/9/35 first and trailer 10 last. */
    public String encode() {
        StringBuffer body = new StringBuffer();
        body.append(Tags.MsgType).append('=').append(get(Tags.MsgType)).append(SOH);
        for (int h = 0; h < HEADER.length; h++) {
            if (get(HEADER[h]) != null) body.append(HEADER[h]).append('=').append(get(HEADER[h])).append(SOH);
        }
        for (int i = 0; i < tags.size(); i++) {
            int t = ((Integer) tags.get(i)).intValue();
            if (t == Tags.BeginString || t == Tags.BodyLength || t == Tags.CheckSum || t == Tags.MsgType
                    || isHeader(t)) continue;
            body.append(t).append('=').append(values.get(i)).append(SOH);
        }
        StringBuffer out = new StringBuffer();
        out.append(Tags.BeginString).append('=').append(BEGIN_STRING).append(SOH);
        out.append(Tags.BodyLength).append('=').append(body.length()).append(SOH);
        out.append(body);
        String sum = checksum(out.toString());
        out.append(Tags.CheckSum).append('=').append(sum).append(SOH);
        return out.toString();
    }

    private static final int[] HEADER = {Tags.SenderCompID, Tags.TargetCompID, Tags.MsgSeqNum, Tags.SendingTime};

    private static boolean isHeader(int t) {
        for (int h = 0; h < HEADER.length; h++) if (HEADER[h] == t) return true;
        return false;
    }

    public static String checksum(String s) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) sum += s.charAt(i);
        String c = String.valueOf(sum % 256);
        while (c.length() < 3) c = "0" + c;
        return c;
    }

    /** Parses one complete message (including the trailing SOH after 10=). Throws FixException if malformed. */
    public static FixMessage parse(String raw) throws FixException {
        FixMessage m = new FixMessage();
        int pos = 0;
        int checksumStart = -1;
        while (pos < raw.length()) {
            int eq = raw.indexOf('=', pos);
            int soh = raw.indexOf(SOH, pos);
            if (eq < 0 || soh < 0 || eq > soh) throw new FixException("garbled field at " + pos);
            int tag;
            try {
                tag = Integer.parseInt(raw.substring(pos, eq));
            } catch (NumberFormatException e) {
                throw new FixException("bad tag at " + pos);
            }
            if (tag == Tags.CheckSum) checksumStart = pos;
            m.tags.add(Integer.valueOf(tag));
            m.values.add(raw.substring(eq + 1, soh));
            pos = soh + 1;
        }
        if (!BEGIN_STRING.equals(m.get(Tags.BeginString))) throw new FixException("bad BeginString");
        if (m.get(Tags.MsgType) == null) throw new FixException("missing MsgType");
        if (checksumStart < 0) throw new FixException("missing CheckSum");
        String expected = checksum(raw.substring(0, checksumStart));
        if (!expected.equals(m.get(Tags.CheckSum))) throw new FixException("bad CheckSum");
        return m;
    }

    public String toString() {
        return encode().replace(SOH, '|');
    }
}
