package io.loglens.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safely hydrates a typed JSONL row from a validated byte pointer. */
final class SegmentReader {
    record Location(String segment, long offset, int length) {}
    private record Entry(int resultIndex, Location location) {}
    private SegmentReader() {}
    static <T> T read(Path directory, String segment, long offset, int length, Class<T> type) throws IOException {
        Path path = validate(directory, new Location(segment, offset, length));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return read(channel, offset, length, type);
        }
    }
    static <T> List<T> readMany(Path directory, List<Location> locations, Class<T> type) throws IOException {
        List<T> result = new ArrayList<>(java.util.Collections.nCopies(locations.size(), null));
        Map<String, List<Entry>> bySegment = new LinkedHashMap<>();
        for (int i = 0; i < locations.size(); i++) {
            Location location = locations.get(i);
            validate(directory, location);
            bySegment.computeIfAbsent(location.segment(), ignored -> new ArrayList<>()).add(new Entry(i, location));
        }
        for (var segment : bySegment.entrySet()) {
            Path path = validate(directory, segment.getValue().get(0).location());
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                for (Entry entry : segment.getValue()) {
                    Location location = entry.location();
                    result.set(entry.resultIndex(), read(channel, location.offset(), location.length(), type));
                }
            }
        }
        return List.copyOf(result);
    }
    private static Path validate(Path directory, Location location) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Path path = root.resolve(location.segment()).normalize();
        if (!path.getParent().equals(root) || !location.segment().matches("[a-z]+-[0-9]+\\.jsonl") || location.offset() < 0 || location.length() < 0 || location.length() > JsonCodec.MAX_ROW_BYTES)
            throw new IOException("unsafe index location");
        return path;
    }
    private static <T> T read(FileChannel channel, long offset, int length, Class<T> type) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) { int n = channel.read(buffer, offset + buffer.position()); if (n < 0) throw new IOException("incomplete indexed row"); }
        return JsonCodec.read(buffer.array(), type);
    }
}
