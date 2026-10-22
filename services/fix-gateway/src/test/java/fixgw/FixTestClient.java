package fixgw;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A bare-bones FIX initiator for tests. */
class FixTestClient implements AutoCloseable {
    final Socket socket;
    final OutputStream out;
    final BlockingQueue<FixMessage> inbox = new LinkedBlockingQueue<>();
    final String compId;
    int seq = 1;

    FixTestClient(int port, String compId) throws Exception {
        this.compId = compId;
        socket = new Socket("127.0.0.1", port);
        out = socket.getOutputStream();
        Thread t = new Thread(() -> {
            try {
                InputStream in = socket.getInputStream();
                StringBuilder buf = new StringBuilder();
                int b;
                while ((b = in.read()) >= 0) {
                    buf.append((char) b);
                    int n = buf.length();
                    if (b == 1 && n >= 8 && buf.charAt(n - 8) == 1 && buf.charAt(n - 7) == '1'
                            && buf.charAt(n - 6) == '0' && buf.charAt(n - 5) == '=') {
                        inbox.add(FixMessage.parse(buf.toString()));
                        buf.setLength(0);
                    }
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
    }

    void send(FixMessage m) throws Exception {
        m.set(Tags.SenderCompID, compId).set(Tags.TargetCompID, "FIXGW").set(Tags.MsgSeqNum, seq++)
                .set(Tags.SendingTime, "20261022-00:00:00.000");
        out.write(m.encode().getBytes("US-ASCII"));
        out.flush();
    }

    void sendRaw(String raw) throws Exception {
        out.write(raw.getBytes("US-ASCII"));
        out.flush();
    }

    FixMessage logon(String user, String key) throws Exception {
        send(new FixMessage("A").set(Tags.EncryptMethod, "0").set(Tags.HeartBtInt, 30).set(Tags.Username, user)
                .set(Tags.Password, key));
        return await(m -> "A".equals(m.getMsgType()) || "5".equals(m.getMsgType()));
    }

    FixMessage await(Predicate<FixMessage> p) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            long left = deadline - System.nanoTime();
            FixMessage m = inbox.poll(Math.max(0, left), TimeUnit.NANOSECONDS);
            if (m == null) throw new AssertionError(compId + ": timed out waiting");
            if (p.test(m)) return m;
        }
    }

    FixMessage awaitExec(String clOrdId, String execType) throws Exception {
        return await(m -> "8".equals(m.getMsgType()) && clOrdId.equals(m.get(Tags.ClOrdID))
                && execType.equals(m.get(Tags.ExecType)));
    }

    static FixMessage limit(String clOrdId, String symbol, String side, String px, long qty) {
        return new FixMessage("D").set(Tags.ClOrdID, clOrdId).set(Tags.Symbol, symbol).set(Tags.Side, side)
                .set(Tags.OrdType, "2").set(Tags.Price, px).set(Tags.OrderQty, qty).set(Tags.TimeInForce, "1")
                .set(Tags.TransactTime, "20261022-00:00:00.000");
    }

    public void close() throws Exception {
        socket.close();
    }
}
