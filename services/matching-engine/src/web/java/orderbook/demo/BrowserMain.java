package orderbook.demo;

import java.util.Random;
import java.util.function.Consumer;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

import orderbook.marketdata.Outbox;
import orderbook.pipeline.InlinePipeline;
import orderbook.sim.Simulator;

/**
 * Entry point of the static (GitHub Pages) build: runs {@link DemoApp} with the real engine and simulator in the
 * page and exposes it as {@code window.orderbook = {request, connect}} in place of the HTTP API and WebSocket.
 */
public final class BrowserMain {

    @JSFunctor
    public interface RequestFn extends JSObject {
        /** Returns {@code "<status>\n<json>"}. */
        String request(String method, String path, String query, String body);
    }

    @JSFunctor
    public interface FrameSink extends JSObject {
        void accept(String frame);
    }

    @JSFunctor
    public interface ConnectFn extends JSObject {
        void connect(FrameSink sink);
    }

    private BrowserMain() {
    }

    @JSBody(params = { "request", "connect" }, script = "window.orderbook = { request: request, connect: connect };")
    private static native void export(RequestFn request, ConnectFn connect);

    public static void main(String[] args) {
        DemoApp app = new DemoApp(new Simulator(new Random().nextLong(), Simulator.DEFAULT_START_REFERENCE_TICKS,
                new BrowserScheduler(), new InlinePipeline()), null);
        export((method, path, query, body) -> {
            DemoApp.Reply reply = app.handle(method, path, query, body, app.userKey());
            return reply.status() + "\n" + reply.json();
        }, sink -> app.connect(new Outbox.Transport() {
            @Override
            public void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure) {
                sink.accept(text);
                onSuccess.run();
            }

            @Override
            public void close() {
            }
        }));
    }
}
