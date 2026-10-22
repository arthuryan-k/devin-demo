package fixgw;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;

/** Thread-per-connection acceptor. */
public class FixServer {

    private static final Logger LOG = Logger.getLogger(FixServer.class.getName());

    private final Authenticator auth;
    private final OrderBus bus;
    private final String compId;
    private final RiskClient risk;
    private final OrderIds ids;
    private ServerSocket server;
    private Thread acceptor;

    public FixServer(Authenticator auth, OrderBus bus, String compId) {
        this(auth, bus, RiskClient.ALLOW_ALL, new OrderIds(15), compId);
    }

    public FixServer(Authenticator auth, OrderBus bus, RiskClient risk, OrderIds ids, String compId) {
        this.auth = auth;
        this.bus = bus;
        this.risk = risk;
        this.ids = ids;
        this.compId = compId;
    }

    public int start(int port) throws IOException {
        server = new ServerSocket(port);
        acceptor = new Thread(new Runnable() {
            public void run() {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        s.setTcpNoDelay(true);
                        Thread t = new Thread(new FixSession(s, auth, bus, risk, ids, compId), "fix-" + s.getRemoteSocketAddress());
                        t.setDaemon(true);
                        t.start();
                    } catch (IOException e) {
                        if (!server.isClosed()) LOG.warning("accept: " + e);
                    }
                }
            }
        }, "fix-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        LOG.info("FIX acceptor " + compId + " listening on " + server.getLocalPort());
        return server.getLocalPort();
    }

    public void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }
}
