package orderbook.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.BatchEventProcessorBuilder;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import orderbook.Command;

/**
 * The LMAX Disruptor implementation of {@link Pipeline}.
 *
 * <pre>
 *  producers ──tryPublishEvent──▶ [input ring: CommandEvent] ──▶ engine handler ──publishEvent──▶ [output ring: ResultEvent]
 *  (HTTP, Simulator; multi-producer)  slot sequence = seqNum        (one thread, matching only)       ├─▶ journal writer
 *                                                                                                     ├─▶ market data publisher
 *                                                                                                     ├─▶ response router
 *                                                                                                     └─▶ ... (each own cursor)
 * </pre>
 *
 * Producers never block: a full input ring makes {@link #tryPublish} return {@link #BUSY}. Every output consumer is
 * a separate {@link BatchEventProcessor} with its own sequence gating the output ring, so a slow consumer delays
 * nobody until it lags by a whole output ring; then the engine waits for it and producers start seeing BUSY.
 */
public final class DisruptorPipeline implements Pipeline {

    public static final int DEFAULT_INPUT_SIZE = 1024;
    public static final int DEFAULT_OUTPUT_SIZE = 4096;

    /** Mutable per-thread translator arguments, so publishing allocates nothing. */
    private static final class Args {
        long participantId;
        boolean awaited;
        long claimed;
    }

    private final ThreadLocal<Args> args = ThreadLocal.withInitial(Args::new);
    private final EngineStage engine = new EngineStage();
    private final ResponseRouter router = new ResponseRouter();
    private final Disruptor<CommandEvent> input;
    private final RingBuffer<CommandEvent> inputRing;
    private final RingBuffer<ResultEvent> outputRing;
    private final List<BatchEventProcessor<ResultEvent>> consumers = new ArrayList<>();
    private final List<Thread> consumerThreads = new ArrayList<>();
    private final ThreadFactory threads;
    private volatile boolean closed;

    private final EventTranslatorTwoArg<CommandEvent, Command, Args> commandTranslator = (event, seq, command, a) -> {
        if (a.awaited) {
            router.expect(seq);
        }
        event.set(seq, a.participantId, command, false, a.awaited);
        a.claimed = seq;
    };
    private final EventTranslatorTwoArg<CommandEvent, Command, Args> resetTranslator =
            (event, seq, command, a) -> {
                event.set(seq, 0, null, true, false);
                a.claimed = seq;
            };
    private final EventTranslatorTwoArg<ResultEvent, CommandEvent, EngineStage> resultTranslator =
            (out, seq, in, stage) -> stage.process(in, out);

    public DisruptorPipeline() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_OUTPUT_SIZE);
    }

    /** Both sizes must be powers of two. Starts the engine thread and the response router immediately. */
    public DisruptorPipeline(int inputSize, int outputSize) {
        AtomicInteger n = new AtomicInteger();
        threads = r -> {
            Thread t = new Thread(r, "pipeline-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        outputRing = RingBuffer.createSingleProducer(ResultEvent::new, outputSize, new BlockingWaitStrategy());
        input = new Disruptor<>(CommandEvent::new, inputSize, threads, ProducerType.MULTI, new BlockingWaitStrategy());
        EventHandler<CommandEvent> matcher =
                (event, seq, endOfBatch) -> outputRing.publishEvent(resultTranslator, event, engine);
        input.handleEventsWith(matcher);
        inputRing = input.start();
        addConsumer(router);
    }

    @Override
    public long tryPublish(long participantId, Command command, boolean awaitResponse) {
        Objects.requireNonNull(command, "command");
        Args a = args.get();
        a.participantId = participantId;
        a.awaited = awaitResponse;
        return publish(commandTranslator, command, a);
    }

    @Override
    public long tryPublishReset() {
        return publish(resetTranslator, null, args.get());
    }

    /** The translator records the claimed slot sequence (the global seqNum) in the thread's {@link Args}. */
    private long publish(EventTranslatorTwoArg<CommandEvent, Command, Args> translator, Command command, Args a) {
        if (closed) {
            throw new IllegalStateException("pipeline closed");
        }
        return inputRing.tryPublishEvent(translator, command, a) ? a.claimed : BUSY;
    }

    @Override
    public Result awaitResponse(long seq, long timeoutMillis) {
        return router.await(seq, timeoutMillis);
    }

    @Override
    public synchronized void addConsumer(ResultHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (closed) {
            throw new IllegalStateException("pipeline closed");
        }
        EventHandler<ResultEvent> adapter = (event, seq, endOfBatch) -> handler.onResult(event, endOfBatch);
        BatchEventProcessor<ResultEvent> processor =
                new BatchEventProcessorBuilder().build(outputRing, outputRing.newBarrier(), adapter);
        outputRing.addGatingSequences(processor.getSequence());
        consumers.add(processor);
        Thread thread = threads.newThread(processor);
        consumerThreads.add(thread);
        thread.start();
    }

    @Override
    public long lastSequence() {
        return inputRing.getCursor();
    }

    /** Free input slots right now. */
    public long remainingInputCapacity() {
        return inputRing.remainingCapacity();
    }

    /** Sequence of the newest result the engine has published to the output ring, or -1. */
    public long engineSequence() {
        return outputRing.getCursor();
    }

    /** Drains what was already published (bounded wait), then stops every thread. */
    @Override
    public void close() {
        List<BatchEventProcessor<ResultEvent>> toHalt;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toHalt = List.copyOf(consumers);
        }
        try {
            input.shutdown(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            input.halt();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (BatchEventProcessor<ResultEvent> processor : toHalt) {
            while (processor.getSequence().get() < outputRing.getCursor() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(1_000_000);
            }
            processor.halt();
        }
        for (Thread thread : consumerThreads) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
