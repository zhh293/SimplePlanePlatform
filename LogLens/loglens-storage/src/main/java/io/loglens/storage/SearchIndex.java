package io.loglens.storage;

import io.loglens.api.CapturedLog;
import io.loglens.api.ProcessedRecord;
import io.loglens.api.TelemetryEvent;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/** Online query-index strategy. JSONL segments remain the canonical record archive. */
public interface SearchIndex extends AutoCloseable {
    void captured(CapturedLog event, SegmentWriter.Pointer pointer) throws IOException, SQLException;
    void telemetry(TelemetryEvent event, SegmentWriter.Pointer pointer) throws IOException, SQLException;
    void processed(ProcessedRecord record, SegmentWriter.Pointer pointer) throws IOException, SQLException;
    void associate(long sequence, List<String> by, List<String> values, String segment) throws IOException, SQLException;
    void removeSegment(String filename) throws IOException, SQLException;
    RecordRepository.Page<ProcessedRecord> records(RecordQuery query);
    RecordRepository.Page<TelemetryEvent> telemetry(TelemetryQuery query);
    long telemetryWatermark();
    ProcessedRecord record(String id);
    RecordRepository.Page<CapturedLog> captured(long after, int limit);
    RecordRepository.Sources sources(ProcessedRecord record, long after, int limit);
    long watermark();
    void flush() throws IOException, SQLException;
    @Override void close() throws Exception;
}
