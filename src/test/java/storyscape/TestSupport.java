package storyscape;

import java.util.ArrayDeque;
import java.util.Deque;

/** Helpers for building tiny hand-assembled story files and capturing interpreter output. */
final class TestSupport {
    private TestSupport() {}

    /** A ZIO that records output and replays canned input lines. */
    static final class TestIO implements ZIO {
        final StringBuilder out = new StringBuilder();
        final Deque<String> input = new ArrayDeque<>();

        @Override public void init(int version) {}
        @Override public void print(String text) { out.append(text); }
        @Override public String readLine() { return input.poll(); }
        @Override public int readChar() { String s = input.poll(); return s == null ? -1 : s.isEmpty() ? 13 : s.charAt(0); }
        @Override public String promptLine(String message) { return input.poll(); }
        @Override public void showStatus(String left, String right) {}
        @Override public void splitWindow(int lines) {}
        @Override public void setWindow(int window) {}
        @Override public int currentWindow() { return 0; }
        @Override public void eraseWindow(int window) {}
        @Override public void eraseLine() {}
        @Override public void setCursor(int row, int col) {}
        @Override public int[] getCursor() { return new int[] {1, 1}; }
        @Override public void setTextStyle(int style) {}
        @Override public void setColour(int foreground, int background) {}
        @Override public void setBuffering(boolean on) {}
        @Override public void bell() {}
        @Override public int width() { return 80; }
        @Override public int height() { return 24; }
        @Override public boolean supportsStyles() { return false; }
        @Override public void flush() {}
        @Override public void close() {}
    }

    /**
     * A 1.5K story: header at 0, code at 0x40, dictionary at 0x100 (empty), object table at
     * 0x140, globals at 0x300, static memory from 0x500.
     */
    static byte[] story(int version, int... code) {
        byte[] s = new byte[0x600];
        s[0] = (byte) version;
        put16(s, 0x04, 0x500);
        put16(s, 0x06, 0x40);
        put16(s, 0x08, 0x100);
        put16(s, 0x0A, 0x140);
        put16(s, 0x0C, 0x300);
        put16(s, 0x0E, 0x500);
        put16(s, 0x1A, 0x600 / (version <= 3 ? 2 : 4));
        put(s, 0x100, 0x00, 0x07, 0x00, 0x00);          // no separators, entry length 7, 0 entries
        put(s, 0x40, code);
        return s;
    }

    static void put(byte[] s, int addr, int... bytes) {
        for (int i = 0; i < bytes.length; i++) s[addr + i] = (byte) bytes[i];
    }

    static void put16(byte[] s, int addr, int v) {
        s[addr] = (byte) (v >> 8);
        s[addr + 1] = (byte) v;
    }

    static String run(byte[] story, String... inputLines) {
        TestIO io = new TestIO();
        for (String l : inputLines) io.input.add(l);
        new Interpreter(story, io, null).run();
        return io.out.toString();
    }
}
