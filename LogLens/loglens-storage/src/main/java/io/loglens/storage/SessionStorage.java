package io.loglens.storage;

import io.loglens.api.*;
import io.loglens.config.ConfigSummary;
import io.loglens.config.LogLensConfig;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One consumer owns all appends. HTTP uses separate SQLite read-only connections. */
public final class SessionStorage implements RecordRepository, DiagnosticSink, SourceIndex {
    private final LogLensConfig config;
    private final LogClock clock;
    private final String sessionId;
    private final Path directory;
    private final long startedAt;
    private final PrintWriter diagnostics;
    private final SegmentWriter capturedWriter;
    private final SegmentWriter processedWriter;
    private final SegmentWriter groupsWriter;
    private final SegmentWriter telemetryWriter;
    private final SegmentWriter combinedWriter;
    private final Map<LogLevel, SegmentWriter> levelWriters = new EnumMap<>(LogLevel.class);
    private final List<SegmentWriter> writers = new ArrayList<>();
    private final SearchIndex index;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong capturedCount = new AtomicLong();
    private final AtomicLong telemetryCount = new AtomicLong();
    private volatile boolean healthy = true;
    private volatile boolean complete;
    private volatile boolean closed;
    private volatile Long endedAt;
    private volatile Map<String, Long> counters = Map.of();
    private volatile Consumer<ProcessedRecord> broadcast = record -> {};
    private long lastCleanup;
    public SessionStorage(LogLensConfig config, LogClock clock) throws IOException, SQLException {
        this.config = config; this.clock = clock; startedAt = clock.wallTimeMillis();
        sessionId = startedAt + "-" + UUID.randomUUID();
        directory = config.output().directory().resolve(config.application()).resolve(sessionId);
        Files.createDirectories(directory);
        diagnostics = new PrintWriter(Files.newBufferedWriter(directory.resolve("diagnostics.log"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW), true);
        long structuredSize = config.output().structured().rotateSizeMiB() * 1024L * 1024;
        long textSize = config.output().text().rotateSizeMiB() * 1024L * 1024;
        if (config.output().structured().enabled()) {
            capturedWriter = writer("captured", "jsonl", structuredSize);
            processedWriter = writer("processed", "jsonl", structuredSize);
            groupsWriter = writer("groups", "jsonl", structuredSize);
            telemetryWriter = writer("telemetry", "jsonl", structuredSize);
            NativeLibraries.configure(config.output().directory().resolve(".native"));
            try { index = SearchIndexFactory.open(config.output().search(), directory, config.application(), sessionId); }
            catch (IOException | SQLException | RuntimeException failure) {
                for (SegmentWriter writer : writers) try { writer.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                diagnostics.close(); throw failure;
            }
        } else { capturedWriter = processedWriter = groupsWriter = telemetryWriter = null; index = null; }
        combinedWriter = config.output().text().combined() ? writer("app", "log", textSize) : null;
        if (config.output().text().splitByLevel()) for (LogLevel level : LogLevel.values()) levelWriters.put(level, writer(level.name().toLowerCase(java.util.Locale.ROOT), "log", textSize));
        try { saveMetadata(); }
        catch (IOException failure) { try { closeResources(); } catch (Exception close) { failure.addSuppressed(close); } throw failure; }
        report("SESSION_STARTED", "Session " + sessionId + " output=" + directory + " config=" + config.configPath());
    }
    private SegmentWriter writer(String prefix, String extension, long maximum) {
        SegmentWriter writer = new SegmentWriter(directory, prefix, extension, maximum, clock); writers.add(writer); return writer;
    }
    public String sessionId() { return sessionId; }
    public Path directory() { return directory; }
    public boolean healthy() { return healthy && !closed; }
    public void broadcastTo(Consumer<ProcessedRecord> listener) { broadcast = listener; }
    public void captured(CapturedLog event) {
        if (!healthy()) throw new StorageException("storage unavailable", null);
        try {
            if (capturedWriter != null) { SegmentWriter.Pointer pointer = capturedWriter.append(JsonCodec.bytes(event)); index.captured(event, pointer); }
            capturedCount.incrementAndGet(); maybeCleanup();
        } catch (IOException | SQLException failure) { fail("CAPTURED_WRITE_FAILED", failure); throw new StorageException("captured write failed", failure); }
    }
    public void telemetry(TelemetryEvent event) {
        if (!healthy()) throw new StorageException("storage unavailable", null);
        if (telemetryWriter == null || index == null) return;
        try {
            SegmentWriter.Pointer pointer=telemetryWriter.append(JsonCodec.bytes(event));
            index.telemetry(event,pointer); telemetryCount.incrementAndGet(); maybeCleanup();
        } catch (IOException | SQLException failure) { fail("TELEMETRY_WRITE_FAILED",failure); throw new StorageException("telemetry write failed",failure); }
    }
    @Override public void associate(long sequence, List<String> by, List<String> values) {
        if (index == null) return;
        try {
            SegmentWriter.Pointer pointer = groupsWriter.append(JsonCodec.bytes(new GroupAssociation(sequence, by, values)));
            index.associate(sequence, by, values, pointer.segment());
        } catch (IOException | SQLException failure) { fail("GROUP_INDEX_FAILED", failure); throw new StorageException("group association failed", failure); }
    }
    public void processed(ProcessedRecord record) {
        if (!healthy()) throw new StorageException("storage unavailable", null);
        try {
            if (processedWriter != null) { SegmentWriter.Pointer pointer = processedWriter.append(JsonCodec.bytes(record)); index.processed(record, pointer); }
            String text = renderText(record);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (combinedWriter != null) combinedWriter.append(bytes);
            SegmentWriter level = levelWriters.get(record.level());
            if (level != null) level.append(bytes);
            if (config.output().console().processed()) System.out.println(text);
            written.set(record.recordSequence());
            maybeCleanup();
            broadcast.accept(record);
        } catch (IOException | SQLException failure) { fail("OUTPUT_WRITE_FAILED", failure); throw new StorageException("processed write failed", failure); }
    }
    public static String renderText(ProcessedRecord record) {
        StringBuilder text = new StringBuilder().append(java.time.Instant.ofEpochMilli(record.firstSeen())).append(' ')
                .append(record.level()).append(" [").append(record.thread()).append("] ").append(record.logger()).append(" - ")
                .append(record.message());
        if (record.repeatCount() > 1) text.append(" [repeat=").append(record.repeatCount()).append(", duration=").append(Math.max(0, record.lastSeen() - record.firstSeen())).append("ms]");
        if (record.processingFailed()) text.append(" [PROCESSING_FAILED]");
        appendException(text, record.exception(), "");
        return text.toString();
    }
    private static void appendException(StringBuilder result, ExceptionSnapshot exception, String prefix) {
        if (exception == null) return;
        result.append('\n').append(prefix).append(exception.type()).append(": ").append(exception.message());
        for (String frame : exception.frames()) result.append("\n\tat ").append(frame);
        for (ExceptionSnapshot suppressed : exception.suppressed()) appendException(result, suppressed, "Suppressed: ");
        appendException(result, exception.cause(), "Caused by: ");
    }
    @Override public synchronized void report(String code, String message) {
        String safe = message.replace('\n', ' ').replace('\r', ' ');
        diagnostics.println(clock.wallTimeMillis() + " " + code + " " + safe);
        if (diagnostics.checkError()) healthy = false;
        if (code.contains("FAILED") || code.contains("TIMEOUT")) System.err.println("[LogLens] " + code + " " + safe);
    }
    public void fail(String code, Throwable failure) { healthy = false; report(code, failure.getClass().getName() + "; reverting console takeover"); }
    private void maybeCleanup() throws IOException, SQLException {
        long now = clock.wallTimeMillis();
        if (now - lastCleanup >= 1000) { cleanup(); lastCleanup = now; }
    }
    public void cleanup() throws IOException, SQLException {
        List<SegmentWriter.ClosedSegment> segments = writers.stream().flatMap(w -> w.closedSegments().stream()).sorted(Comparator.comparingLong(SegmentWriter.ClosedSegment::closedAt)).toList();
        long total;
        try (var files = Files.list(directory)) { total = files.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { throw new StorageException("disk usage query failed", e); } }).sum(); }
        long maxDisk = config.output().structured().maxDiskMiB() * 1024L * 1024;
        long cutoff = clock.wallTimeMillis() - TimeUnit.DAYS.toMillis(config.output().text().retentionDays());
        for (SegmentWriter.ClosedSegment segment : segments) {
            if (segment.closedAt() < cutoff || total > maxDisk) {
                if (index != null) index.removeSegment(segment.path().getFileName().toString());
                Files.deleteIfExists(segment.path()); total -= segment.bytes();
                writers.forEach(writer -> writer.forget(segment.path()));
            }
        }
    }
    public void flush() {
        try { for (SegmentWriter writer : writers) writer.flush(); if (index != null) index.flush(); diagnostics.flush(); }
        catch (IOException | SQLException failure) { fail("STORAGE_FLUSH_FAILED", failure); throw new StorageException("flush failed", failure); }
    }
    public synchronized void finish(boolean complete, Map<String, Long> counters) {
        this.complete = complete && healthy; this.counters = Map.copyOf(counters); endedAt = clock.wallTimeMillis();
        try { saveMetadata(); } catch (IOException failure) { fail("METADATA_FAILED", failure); }
    }
    private void saveMetadata() throws IOException {
        Path temporary = directory.resolve("session.json.tmp");
        Files.write(temporary, JsonCodec.bytes(session()), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(temporary, directory.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, directory.resolve("session.json"), StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public Map<String, Object> session() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", sessionId); metadata.put("application", config.application()); metadata.put("version", "0.1.0");
        metadata.put("startedAt", startedAt); metadata.put("endedAt", endedAt); metadata.put("complete", complete);
        metadata.put("status", endedAt == null ? "RUNNING" : complete ? "COMPLETE" : "INCOMPLETE");
        metadata.put("configHash", config.configHash()); metadata.put("config", configSummary());
        metadata.put("capturedCount", capturedCount.get()); metadata.put("recordSequence", written.get()); metadata.put("telemetryCount",telemetryCount.get());
        metadata.put("outputHealthy", healthy); metadata.put("counters", counters);
        try (var files = Files.list(directory)) {
            List<Map<String, Object>> segments = new ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches("[a-z]+-[0-9]+\\.(jsonl|log)")).sorted().toList()) {
                Map<String, Object> segment = new LinkedHashMap<>();
                segment.put("file", file.getFileName().toString());
                segment.put("bytes", Files.size(file));
                segment.put("active", writers.stream().anyMatch(writer -> file.equals(writer.currentPath())) && !closed && endedAt == null);
                segments.add(java.util.Collections.unmodifiableMap(segment));
            }
            metadata.put("segments", List.copyOf(segments));
        } catch (IOException failure) {
            throw new StorageException("session segment listing failed", failure);
        }
        return java.util.Collections.unmodifiableMap(metadata);
    }
    @Override public Map<String, Object> configSummary() { return ConfigSummary.from(config); }
    @Override public Page<ProcessedRecord> records(RecordQuery query) { return index == null ? new Page<>(List.of(), query.after(), false) : index.records(query); }
    @Override public ProcessedRecord record(String id) { return index == null ? null : index.record(id); }
    @Override public Sources sources(String id, long after, int limit) {
        ProcessedRecord record = record(id);
        if (record == null) return null;
        return index.sources(record, after, limit);
    }
    @Override public Page<CapturedLog> captured(long after, int limit) { return index == null ? new Page<>(List.of(), after, false) : index.captured(after, limit); }
    @Override public long watermark() { return written.get(); }
    @Override public long telemetryWatermark() { return index == null ? 0 : index.telemetryWatermark(); }
    @Override public Page<TelemetryEvent> telemetry(TelemetryQuery query) { return index == null ? new Page<>(List.of(),query.after(),false) : index.telemetry(query); }
    private void closeResources() throws Exception {
        Exception first = null;
        for (SegmentWriter writer : writers) { try { writer.close(); } catch (Exception e) { if (first == null) first = e; else first.addSuppressed(e); } }
        if (index != null) try { index.close(); } catch (Exception e) { if (first == null) first = e; else first.addSuppressed(e); }
        diagnostics.close();
        if (first != null) throw first;
    }
    @Override public void close() throws Exception { if (!closed) { closed = true; closeResources(); } }
}
