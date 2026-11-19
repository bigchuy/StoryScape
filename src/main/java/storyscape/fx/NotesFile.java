package storyscape.fx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The notes-and-map file kept beside a story ("story.storyscape" next to "story.z5"), separate from
 * the saved games. It is a small IFF file, FORM type "SSNM", holding the same chunks the side panel
 * once wrote into saved games (notes, room notes and maps), so it loads whether or not a game is
 * restored.
 */
final class NotesFile {
    static final String EXTENSION = ".storyscape";
    private static final String FORM_TYPE = "SSNM";

    private NotesFile() {}

    /** The notes-and-map file for {@code story}: the story's name with {@link #EXTENSION}. */
    static Path forStory(Path story) {
        Path abs = story.toAbsolutePath();
        String name = abs.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return abs.resolveSibling(name + EXTENSION);
    }

    static byte[] encode(Map<String, byte[]> chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : chunks.entrySet()) {
            String id = e.getKey();
            byte[] data = e.getValue();
            if (id.length() != 4 || data == null) continue;
            body.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
            writeInt(body, data.length);
            body.writeBytes(data);
            if ((data.length & 1) != 0) body.write(0);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes("FORM".getBytes(StandardCharsets.US_ASCII));
        writeInt(file, body.size() + 4);
        file.writeBytes(FORM_TYPE.getBytes(StandardCharsets.US_ASCII));
        file.writeBytes(body.toByteArray());
        return file.toByteArray();
    }

    static Map<String, byte[]> decode(byte[] d) throws IOException {
        if (d.length < 12 || !id(d, 0).equals("FORM") || !id(d, 8).equals(FORM_TYPE)) {
            throw new IOException("Not a StoryScape notes file");
        }
        Map<String, byte[]> chunks = new LinkedHashMap<>();
        int pos = 12;
        while (pos + 8 <= d.length) {
            String cid = id(d, pos);
            int len = int32(d, pos + 4);
            int start = pos + 8;
            if (len < 0 || start + len > d.length) throw new IOException("Damaged notes file");
            chunks.put(cid, Arrays.copyOfRange(d, start, start + len));
            pos = start + len + (len & 1);
        }
        return chunks;
    }

    /** Reads the file; null if there is none. */
    static Map<String, byte[]> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        return decode(Files.readAllBytes(file));
    }

    /** Writes {@code data} through a temporary file, so a crash mid-write can't leave half a file. */
    static void write(Path file, byte[] data) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >> 24);
        out.write(v >> 16);
        out.write(v >> 8);
        out.write(v);
    }

    private static String id(byte[] d, int p) {
        return new String(d, p, 4, StandardCharsets.US_ASCII);
    }

    private static int int32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }
}
