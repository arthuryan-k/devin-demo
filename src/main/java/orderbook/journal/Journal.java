package orderbook.journal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import orderbook.Command;
import orderbook.CommandLog;
import orderbook.EngineState;
import orderbook.MatchingEngine;
import orderbook.journal.CommandCodec.Sequenced;

/**
 * Append-only, checksummed, segmented command journal with snapshots (event sourcing input log), format v2.
 *
 * <p>A journal is a directory:
 * <pre>
 * journal-000001.log              #orderbook-journal v2 base=-1        (header: every entry has seq &gt; base)
 *                                 1a2b3c4d:{"seq":0,"cmd":"place",...}  (one CRC32-prefixed entry per line)
 * snapshot-00000000000000000041.json   9f8e7d6c:{"format":2,"seq":41,"state":{...}}
 * journal-000002.log              #orderbook-journal v2 base=41        (opened when the snapshot was taken)
 * </pre>
 *
 * Each entry is {@code <crc32 of the JSON, 8 hex digits>:<CommandCodec JSON with a "seq" field>}. Sequence numbers
 * strictly increase but may have gaps (pipeline markers consume sequences). Used directly as a {@link CommandLog}
 * (see {@link MatchingEngine#MatchingEngine(CommandLog)}) the journal numbers entries itself; behind the pipeline,
 * {@code JournalHandler} passes the global sequence via {@link #append(long, Command)}.
 *
 * <p><b>Snapshots and rotation.</b> {@link #writeSnapshot} stores the engine state covering every entry with
 * {@code seq <= S} (written to a temp file, fsynced, then atomically renamed), then rotates to a new segment with
 * {@code base=S}. {@link #recover()} loads the newest snapshot and replays only entries with {@code seq > S},
 * reading from the newest segment whose base is {@code <= S}; without a snapshot it replays everything. Old
 * segments and snapshots are kept; {@link #pruneCoveredSegments()} deletes the fully covered ones on request.
 *
 * <p><b>Durability.</b> Every append is written to the OS ({@code FileChannel.write}, no user-space buffer) before
 * it returns, so in both modes a process crash loses nothing that was appended.
 * <ul>
 *   <li>{@link Durability#FLUSH}: no fsync. An OS crash or power loss can lose whatever the kernel had not yet
 *       written back (on Linux typically up to ~30 s of entries).</li>
 *   <li>{@link Durability#FSYNC}: {@code FileChannel.force} after every {@code fsyncEveryCommands} entries or
 *       {@code fsyncEveryMillis} ms, whichever comes first. An OS crash or power loss loses at most the last
 *       {@code fsyncEveryCommands - 1} entries or {@code fsyncEveryMillis} ms of entries.</li>
 * </ul>
 *
 * <p><b>Integrity.</b> A final line without a newline (torn write), or a final complete line that fails its CRC or
 * does not parse, is ignored by replay and truncated away when the journal is next opened for writing. A bad line
 * anywhere else, a bad snapshot, a missing segment, or non-increasing sequence numbers is corruption and fails
 * with {@link IllegalStateException}. v1 journals (a single file of unchecksummed JSON lines) are rejected.
 *
 * <p>Methods are synchronized; the FSYNC timer runs on its own daemon thread.
 */
public final class Journal implements CommandLog, Closeable {

    public static final int FORMAT_VERSION = 2;
    static final String HEADER_PREFIX = "#orderbook-journal v" + FORMAT_VERSION + " base=";

    private static final Pattern SEGMENT = Pattern.compile("journal-(\\d{6,})\\.log");
    private static final Pattern SNAPSHOT = Pattern.compile("snapshot-(\\d{20})\\.json");
    private static final Pattern HEADER = Pattern.compile("#orderbook-journal v(\\d+) base=(-?\\d+)");

    public enum Durability {
        /** Write every entry to the OS; never fsync (survives process crashes, not power loss). */
        FLUSH,
        /** Additionally fsync in batches (bounded loss window on power loss). */
        FSYNC
    }

    /** @param fsyncEveryCommands FSYNC only: force after this many entries (>= 1)
     *  @param fsyncEveryMillis FSYNC only: force pending entries after this many ms (0 = no timer) */
    public record Options(Durability durability, int fsyncEveryCommands, long fsyncEveryMillis) {

        public static final Options FLUSH = new Options(Durability.FLUSH, 0, 0);

        public Options {
            Objects.requireNonNull(durability, "durability");
            if (durability == Durability.FSYNC && (fsyncEveryCommands < 1 || fsyncEveryMillis < 0)) {
                throw new IllegalArgumentException("FSYNC needs fsyncEveryCommands >= 1 and fsyncEveryMillis >= 0");
            }
        }

        public static Options fsync(int everyCommands, long everyMillis) {
            return new Options(Durability.FSYNC, everyCommands, everyMillis);
        }
    }

    /** What recovery found: the newest snapshot (null if none) and the journal entries after it, in order. */
    public record Recovery(Snapshot snapshot, List<Sequenced> tail) {

        public Recovery {
            tail = List.copyOf(tail);
        }

        /** Sequence number of the last command or snapshot recovered, or -1 if the journal is empty. */
        public long lastSeq() {
            return !tail.isEmpty() ? tail.get(tail.size() - 1).seq() : snapshot != null ? snapshot.seq() : -1;
        }

        public List<Command> tailCommands() {
            return tail.stream().map(Sequenced::command).toList();
        }

        /** Snapshot state (or an empty engine) plus the tail, then journaling to {@code commandLog}. */
        public MatchingEngine toEngine(CommandLog commandLog) {
            return snapshot == null ? MatchingEngine.replay(tailCommands(), commandLog)
                    : MatchingEngine.restore(snapshot.state(), tailCommands(), commandLog);
        }
    }

    private record SegmentFile(int index, Path path, long base, boolean tornHeader) {
    }

    private record SegmentData(List<Sequenced> entries, long validEnd, long size) {
    }

    private final Path dir;
    private final Options options;
    private FileChannel channel;
    private int segmentIndex;
    private long segmentEntries;
    private long lastSeq = -1;
    private int unsynced;
    private long lastForceNanos;
    private long forces;
    private ScheduledExecutorService fsyncTimer;

    public Journal(Path dir) {
        this(dir, Options.FLUSH);
    }

    public Journal(Path dir, Options options) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.options = Objects.requireNonNull(options, "options");
    }

    public Path directory() {
        return dir;
    }

    public Options options() {
        return options;
    }

    /** Appends {@code command} at the next sequence number ({@link #lastSeq()} + 1). */
    @Override
    public synchronized void append(Command command) {
        open();
        append(lastSeq + 1, command);
    }

    /** Appends {@code command} at {@code seq}, which must be greater than every sequence already journaled. */
    public synchronized void append(long seq, Command command) {
        Objects.requireNonNull(command, "command");
        open();
        if (seq <= lastSeq) {
            throw new IllegalArgumentException("seq " + seq + " is not after the last journaled seq " + lastSeq);
        }
        try {
            write(channel, line(CommandCodec.encode(seq, command).getBytes(StandardCharsets.UTF_8)));
            lastSeq = seq;
            segmentEntries++;
            if (options.durability() == Durability.FSYNC) {
                unsynced++;
                if (unsynced >= options.fsyncEveryCommands() || (options.fsyncEveryMillis() > 0
                        && System.nanoTime() - lastForceNanos >= TimeUnit.MILLISECONDS.toNanos(options.fsyncEveryMillis()))) {
                    force();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to journal " + command, e);
        }
    }

    /** Highest sequence number journaled or covered by a snapshot, or -1. Opens the journal for writing. */
    public synchronized long lastSeq() {
        open();
        return lastSeq;
    }

    /** Snapshots {@code engine} as covering everything journaled so far (it must be in exactly that state). */
    public synchronized void snapshot(MatchingEngine engine) {
        open();
        if (lastSeq < 0) {
            throw new IllegalStateException("nothing journaled to snapshot");
        }
        writeSnapshot(Snapshot.capture(lastSeq, engine));
    }

    public synchronized void writeSnapshot(Snapshot snapshot) {
        writeSnapshot(snapshot.seq(), SnapshotCodec.encodeState(snapshot.state()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Durably stores a snapshot whose state ({@link SnapshotCodec#encodeState}, UTF-8) covers every entry with
     * sequence {@code <= seq}, then rotates to a new segment if the current one has entries. {@code seq} must be
     * at least {@link #lastSeq()}.
     */
    public synchronized void writeSnapshot(long seq, byte[] encodedState) {
        open();
        if (seq < lastSeq || seq < 0) {
            throw new IllegalArgumentException("snapshot seq " + seq + " is before the last journaled seq " + lastSeq);
        }
        ByteArrayOutputStream json = new ByteArrayOutputStream(encodedState.length + 48);
        json.writeBytes(("{\"format\":" + SnapshotCodec.FORMAT + ",\"seq\":" + seq + ",\"state\":")
                .getBytes(StandardCharsets.UTF_8));
        json.writeBytes(encodedState);
        json.write('}');
        Path target = dir.resolve(snapshotName(seq));
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        try {
            if (options.durability() == Durability.FSYNC && unsynced > 0) {
                force();
            }
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                write(out, line(json.toByteArray()));
                out.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
            lastSeq = seq;
            if (segmentEntries > 0) {
                channel.close();
                channel = null;
                createSegment(segmentIndex + 1, seq);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write snapshot at seq " + seq, e);
        }
    }

    /** The newest snapshot plus every entry after it. Read-only; an absent directory recovers as empty. */
    public synchronized Recovery load() {
        if (!Files.exists(dir)) {
            return new Recovery(null, List.of());
        }
        try {
            checkDirectory();
            Snapshot snapshot = newestSnapshot();
            long covered = snapshot != null ? snapshot.seq() : -1;
            List<SegmentFile> segments = segments();
            if (!segments.isEmpty() && segments.get(segments.size() - 1).tornHeader()) {
                segments.remove(segments.size() - 1);
            }
            List<Sequenced> tail = new ArrayList<>();
            long prev = Long.MIN_VALUE;
            for (int i = firstNeeded(segments, covered); i < segments.size(); i++) {
                SegmentFile seg = segments.get(i);
                for (Sequenced e : read(seg, i == segments.size() - 1).entries()) {
                    if (e.seq() <= prev || e.seq() <= seg.base()) {
                        throw new IllegalStateException("corrupt journal " + seg.path() + ": seq " + e.seq()
                                + " out of order");
                    }
                    prev = e.seq();
                    if (e.seq() > covered) {
                        tail.add(e);
                    }
                }
            }
            return new Recovery(snapshot, tail);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read journal " + dir, e);
        }
    }

    /** Commands after the newest snapshot (every command if there is none), in append order. */
    public List<Command> replay() {
        return load().tailCommands();
    }

    /** A fresh engine rebuilt from the newest snapshot plus the journal tail, which keeps journaling here. */
    public MatchingEngine recover() {
        return load().toEngine(this);
    }

    /**
     * Deletes segments whose entries are all covered by the newest snapshot, and every older snapshot. Never
     * called automatically. Returns the number of files deleted.
     */
    public synchronized int pruneCoveredSegments() {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try {
            List<Long> snapshots = snapshotSeqs();
            if (snapshots.isEmpty()) {
                return 0;
            }
            long covered = snapshots.get(snapshots.size() - 1);
            int deleted = 0;
            List<SegmentFile> segments = segments();
            for (int i = 0; i + 1 < segments.size(); i++) {
                SegmentFile next = segments.get(i + 1);
                if (!next.tornHeader() && next.base() <= covered && Files.deleteIfExists(segments.get(i).path())) {
                    deleted++;
                }
            }
            for (long seq : snapshots.subList(0, snapshots.size() - 1)) {
                if (Files.deleteIfExists(dir.resolve(snapshotName(seq)))) {
                    deleted++;
                }
            }
            syncDirectory();
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to prune journal " + dir, e);
        }
    }

    /** Number of {@code FileChannel.force} calls on segments so far (for tests and monitoring). */
    public synchronized long fsyncCount() {
        return forces;
    }

    @Override
    public synchronized void close() throws IOException {
        if (fsyncTimer != null) {
            fsyncTimer.shutdownNow();
            fsyncTimer = null;
        }
        if (channel != null) {
            if (unsynced > 0) {
                force();
            }
            channel.close();
            channel = null;
        }
    }

    // ---- writing --------------------------------------------------------------------------------------------------

    private void open() {
        if (channel != null) {
            return;
        }
        try {
            if (Files.exists(dir)) {
                checkDirectory();
            }
            Files.createDirectories(dir);
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList()) {
                    Files.delete(p);
                }
            }
            List<Long> snapshots = snapshotSeqs();
            long last = snapshots.isEmpty() ? -1 : snapshots.get(snapshots.size() - 1);
            List<SegmentFile> segments = segments();
            if (!segments.isEmpty() && segments.get(segments.size() - 1).tornHeader()) {
                Files.delete(segments.remove(segments.size() - 1).path());
            }
            if (segments.isEmpty()) {
                createSegment(1, last);
            } else {
                SegmentFile seg = segments.get(segments.size() - 1);
                SegmentData data = read(seg, true);
                channel = FileChannel.open(seg.path(), StandardOpenOption.WRITE);
                if (data.validEnd() < data.size()) {
                    channel.truncate(data.validEnd());
                }
                channel.position(data.validEnd());
                segmentIndex = seg.index();
                segmentEntries = data.entries().size();
                last = Math.max(last, seg.base());
                if (!data.entries().isEmpty()) {
                    last = Math.max(last, data.entries().get(data.entries().size() - 1).seq());
                }
            }
            lastSeq = last;
            unsynced = 0;
            lastForceNanos = System.nanoTime();
            if (options.durability() == Durability.FSYNC && options.fsyncEveryMillis() > 0 && fsyncTimer == null) {
                startFsyncTimer();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open journal " + dir, e);
        }
    }

    private void createSegment(int index, long base) throws IOException {
        Path path = dir.resolve(String.format("journal-%06d.log", index));
        channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        write(channel, (HEADER_PREFIX + base + "\n").getBytes(StandardCharsets.UTF_8));
        if (options.durability() == Durability.FSYNC) {
            channel.force(true);
            syncDirectory();
        }
        segmentIndex = index;
        segmentEntries = 0;
        unsynced = 0;
    }

    private void force() throws IOException {
        channel.force(false);
        unsynced = 0;
        lastForceNanos = System.nanoTime();
        forces++;
    }

    private void startFsyncTimer() {
        fsyncTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "journal-fsync");
            t.setDaemon(true);
            return t;
        });
        long ms = options.fsyncEveryMillis();
        fsyncTimer.scheduleWithFixedDelay(() -> {
            synchronized (this) {
                if (channel != null && unsynced > 0
                        && System.nanoTime() - lastForceNanos >= TimeUnit.MILLISECONDS.toNanos(ms)) {
                    try {
                        force();
                    } catch (IOException e) {
                        // Retried on the next tick or append; close() reports a persistent failure.
                    }
                }
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    private static void write(FileChannel ch, byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    /** {@code <crc32 hex>:<json>\n}. */
    static byte[] line(byte[] json) {
        CRC32 crc = new CRC32();
        crc.update(json);
        byte[] out = new byte[json.length + 10];
        String hex = Long.toHexString(crc.getValue());
        for (int i = 0; i < 8; i++) {
            int k = i - (8 - hex.length());
            out[i] = (byte) (k < 0 ? '0' : hex.charAt(k));
        }
        out[8] = ':';
        System.arraycopy(json, 0, out, 9, json.length);
        out[out.length - 1] = '\n';
        return out;
    }

    /** The JSON of a checksummed line (without its newline), or null if the checksum is missing or wrong. */
    static String verify(byte[] buf, int start, int end) {
        if (end - start < 10 || buf[start + 8] != ':') {
            return null;
        }
        long expected = 0;
        for (int i = start; i < start + 8; i++) {
            int d = Character.digit(buf[i], 16);
            if (d < 0 || Character.isUpperCase(buf[i])) {
                return null;
            }
            expected = expected << 4 | d;
        }
        CRC32 crc = new CRC32();
        crc.update(buf, start + 9, end - start - 9);
        return crc.getValue() == expected ? new String(buf, start + 9, end - start - 9, StandardCharsets.UTF_8) : null;
    }

    private void syncDirectory() {
        try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Not every platform can fsync a directory; the rename is still atomic.
        }
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    private void checkDirectory() {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(dir + " is not a journal directory: single-file v1 journals are not"
                    + " supported (format v" + FORMAT_VERSION + " is a directory of checksummed segments)");
        }
    }

    private List<SegmentFile> segments() throws IOException {
        List<SegmentFile> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Matcher m = SEGMENT.matcher(p.getFileName().toString());
                if (m.matches()) {
                    result.add(header(Integer.parseInt(m.group(1)), p));
                }
            }
        }
        result.sort(Comparator.comparingInt(SegmentFile::index));
        for (int i = 0; i + 1 < result.size(); i++) {
            if (result.get(i).tornHeader()) {
                throw new IllegalStateException("corrupt journal " + result.get(i).path() + ": torn header");
            }
        }
        return result;
    }

    private static SegmentFile header(int index, Path path) throws IOException {
        byte[] head;
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(ch.size(), 128));
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // fill
            }
            head = buf.array();
        }
        int nl = indexOf(head, 0, head.length);
        if (nl < 0) {
            if (head.length < 128) {
                return new SegmentFile(index, path, Long.MIN_VALUE, true);
            }
            throw new IllegalStateException("corrupt journal " + path + ": no header");
        }
        String line = new String(head, 0, nl, StandardCharsets.UTF_8);
        Matcher m = HEADER.matcher(line);
        if (!m.matches()) {
            throw new IllegalStateException("unsupported journal " + path + ": expected a '" + HEADER_PREFIX
                    + "<seq>' header, got '" + line + "' (unchecksummed v1 journals are not supported)");
        }
        if (Integer.parseInt(m.group(1)) != FORMAT_VERSION) {
            throw new IllegalStateException("unsupported journal format v" + m.group(1) + " in " + path);
        }
        return new SegmentFile(index, path, Long.parseLong(m.group(2)), false);
    }

    /** Index into {@code segments} of the newest segment with base {@code <= covered}. */
    private static int firstNeeded(List<SegmentFile> segments, long covered) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).base() <= covered) {
                return i;
            }
        }
        if (segments.isEmpty()) {
            return 0;
        }
        throw new IllegalStateException("journal gap: entries up to seq " + covered + " are covered, but the oldest"
                + " segment " + segments.get(0).path() + " starts after seq " + segments.get(0).base());
    }

    /**
     * Verified entries of a segment and the byte offset just past the last good line. A bad final line (torn, CRC
     * mismatch or unparseable) of the last segment ends the segment; any other bad line is corruption.
     */
    private static SegmentData read(SegmentFile seg, boolean last) throws IOException {
        byte[] buf = Files.readAllBytes(seg.path());
        List<Sequenced> entries = new ArrayList<>();
        int start = indexOf(buf, 0, buf.length) + 1;
        int lineNo = 2;
        while (start < buf.length) {
            int nl = indexOf(buf, start, buf.length);
            int end = nl < 0 ? buf.length : nl;
            boolean finalBytes = nl < 0 || nl == buf.length - 1;
            String json = nl < 0 ? null : verify(buf, start, end);
            Sequenced entry = null;
            String problem = nl < 0 ? "torn line" : json == null ? "CRC mismatch" : null;
            if (json != null) {
                try {
                    entry = CommandCodec.decodeSequenced(json);
                } catch (IllegalArgumentException e) {
                    problem = "unparseable entry (" + e.getMessage() + ")";
                }
            }
            if (entry == null) {
                if (last && finalBytes) {
                    return new SegmentData(entries, start, buf.length);
                }
                throw new IllegalStateException("corrupt journal " + seg.path() + " at line " + lineNo + ": " + problem);
            }
            entries.add(entry);
            start = nl + 1;
            lineNo++;
        }
        return new SegmentData(entries, buf.length, buf.length);
    }

    private static int indexOf(byte[] buf, int from, int to) {
        for (int i = from; i < to; i++) {
            if (buf[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private List<Long> snapshotSeqs() throws IOException {
        List<Long> seqs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Matcher m = SNAPSHOT.matcher(p.getFileName().toString());
                if (m.matches()) {
                    seqs.add(Long.parseLong(m.group(1)));
                }
            }
        }
        seqs.sort(null);
        return seqs;
    }

    private Snapshot newestSnapshot() throws IOException {
        List<Long> seqs = snapshotSeqs();
        if (seqs.isEmpty()) {
            return null;
        }
        long seq = seqs.get(seqs.size() - 1);
        Path path = dir.resolve(snapshotName(seq));
        byte[] buf = Files.readAllBytes(path);
        int nl = indexOf(buf, 0, buf.length);
        String json = nl == buf.length - 1 ? verify(buf, 0, nl) : null;
        if (json == null) {
            throw new IllegalStateException("corrupt snapshot " + path + ": CRC mismatch or truncated");
        }
        Snapshot snapshot;
        try {
            snapshot = SnapshotCodec.decode(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("corrupt snapshot " + path, e);
        }
        if (snapshot.seq() != seq) {
            throw new IllegalStateException("snapshot " + path + " claims seq " + snapshot.seq());
        }
        return snapshot;
    }

    static String snapshotName(long seq) {
        return String.format("snapshot-%020d.json", seq);
    }

    /** Engine state of an empty engine, as {@link #writeSnapshot(long, byte[])} expects it. */
    public static byte[] emptyState() {
        return SnapshotCodec.encodeState(EngineState.EMPTY).getBytes(StandardCharsets.UTF_8);
    }
}
