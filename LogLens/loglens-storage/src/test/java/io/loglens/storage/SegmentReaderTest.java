package io.loglens.storage;

import io.loglens.api.CapturedLog;
import io.loglens.api.LogLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SegmentReaderTest {
    @TempDir Path directory;

    @Test void readsPageAcrossSegmentsWithOneLocationPerResultAndPreservesHitOrder() throws Exception {
        CapturedLog first=event("first",1), second=event("second",2);
        byte[] firstBytes=JsonCodec.bytes(first), secondBytes=JsonCodec.bytes(second);
        Files.write(directory.resolve("captured-0001.jsonl"),firstBytes);
        Files.write(directory.resolve("captured-0002.jsonl"),secondBytes);

        var rows=SegmentReader.readMany(directory,List.of(
                new SegmentReader.Location("captured-0002.jsonl",0,secondBytes.length),
                new SegmentReader.Location("captured-0001.jsonl",0,firstBytes.length),
                new SegmentReader.Location("captured-0002.jsonl",0,secondBytes.length)),CapturedLog.class);

        assertEquals(List.of("second","first","second"),rows.stream().map(CapturedLog::eventId).toList());
        assertThrows(java.io.IOException.class,()->SegmentReader.readMany(directory,
                List.of(new SegmentReader.Location("..\\outside.jsonl",0,1)),CapturedLog.class));
    }

    private CapturedLog event(String id,long sequence){
        return new CapturedLog(id,"session",sequence,100,100,100,LogLevel.INFO,"test.Logger","main","message","message",List.of(),Map.of(),null,List.of(),false);
    }
}
