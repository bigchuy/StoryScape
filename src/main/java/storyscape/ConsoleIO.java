package storyscape;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Terminal front-end. In ANSI mode it keeps the upper window (status line / v4+ windows)
 * pinned at the top using a terminal scroll region; in plain mode the upper window is
 * suppressed and the v3 status line is printed as an ordinary line.
 */
public final class ConsoleIO implements ZIO {
    private static final String ESC = "\033";
    private static final String CSI = "\033[";

    private final boolean ansi;
    private final int width;
    private final int height;
    private final PrintStream out;
    private final BufferedReader in;

    private int version;
    private int off;                 // screen rows reserved above window 1 (v3 status line)
    private int upperLines;
    private int window;
    private int lowerRow, lowerCol;  // lowerCol is 0-based, lowerRow 1-based
    private int upperRow = 1, upperCol = 1;
    private boolean buffering = true;
    private final StringBuilder word = new StringBuilder();
    private int style;
    private int fg = 1, bg = 1;

    public ConsoleIO(boolean ansi, int width, int height) {
        this.ansi = ansi;
        this.width = width;
        this.height = height;
        this.out = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192),
                false, StandardCharsets.UTF_8);
        this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    @Override public void init(int version) {
        this.version = version;
        this.off = version <= 3 ? 1 : 0;
        if (ansi) {
            raw(CSI + "2J" + CSI + "H");
            lowerRow = height;
            lowerCol = 0;
            applyRegion();
        } else {
            lowerRow = 1;
        }
    }

    // ---------------------------------------------------------------- output

    @Override public void print(String s) {
        if (window == 1) printUpper(s); else printLower(s);
    }

    private void printLower(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                flushWord();
                newlineLower();
            } else if (buffering) {
                if (c == ' ') {
                    flushWord();
                    if (lowerCol >= width) newlineLower();
                    else { raw(" "); lowerCol++; }
                } else {
                    word.append(c);
                }
            } else {
                raw(String.valueOf(c));
                if (++lowerCol >= width) { lowerCol = 0; lowerRow = Math.min(height, lowerRow + 1); }
            }
        }
    }

    private void flushWord() {
        if (word.length() == 0) return;
        if (lowerCol + word.length() > width && lowerCol > 0) newlineLower();
        raw(word.toString());
        lowerCol += word.length();
        if (lowerCol >= width) { lowerCol = lowerCol % width; lowerRow = Math.min(height, lowerRow + 1); }
        word.setLength(0);
    }

    private void newlineLower() {
        raw("\n");
        lowerCol = 0;
        lowerRow = Math.min(height, lowerRow + 1);
    }

    private void printUpper(String s) {
        if (!ansi) return;
        StringBuilder seg = new StringBuilder();
        int segRow = upperRow, segCol = upperCol;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                flushSeg(seg, segRow, segCol);
                upperRow++;
                upperCol = 1;
                segRow = upperRow;
                segCol = 1;
            } else {
                if (upperRow <= upperLines && upperCol <= width) seg.append(c);
                upperCol++;
            }
        }
        flushSeg(seg, segRow, segCol);
    }

    private void flushSeg(StringBuilder seg, int row, int col) {
        if (seg.length() == 0) return;
        raw(ESC + "7" + CSI + (row + off) + ";" + col + "H" + seg + ESC + "8");
        seg.setLength(0);
    }

    private void raw(String s) { out.print(s); }

    private void applyRegion() {
        if (!ansi) return;
        int top = Math.min(upperLines + off + 1, height);
        raw(top <= 1 ? CSI + "r" : CSI + top + ";" + height + "r");
        if (lowerRow < top) lowerRow = top;
        raw(CSI + lowerRow + ";" + Math.min(lowerCol + 1, width) + "H");
    }

    // ---------------------------------------------------------------- windows

    @Override public void splitWindow(int lines) {
        flushWord();
        upperLines = Math.max(0, Math.min(lines, height - 1 - off));
        applyRegion();
    }

    @Override public void setWindow(int w) {
        flushWord();
        window = w == 1 ? 1 : 0;
        if (window == 1) { upperRow = 1; upperCol = 1; }
    }

    @Override public int currentWindow() { return window; }

    @Override public void eraseWindow(int w) {
        flushWord();
        if (!ansi) return;
        boolean lower = w == 0 || w == -1 || w == -2;
        if (w == -1) { upperLines = 0; }
        if (w == -1 || w == -2) {
            raw(CSI + "2J");
            if (w == -1) applyRegion();
        } else if (w == 1) {
            for (int r = 1; r <= upperLines; r++) raw(CSI + (r + off) + ";1H" + CSI + "2K");
        } else if (lower) {
            for (int r = upperLines + off + 1; r <= height; r++) raw(CSI + r + ";1H" + CSI + "2K");
        }
        if (w == 1) {
            upperRow = 1;
            upperCol = 1;
            raw(CSI + lowerRow + ";" + Math.min(lowerCol + 1, width) + "H");
        } else {
            lowerRow = version == 4 ? height : upperLines + off + 1;
            lowerCol = 0;
            raw(CSI + lowerRow + ";1H");
        }
    }

    @Override public void eraseLine() {
        if (!ansi || window != 1) return;
        raw(ESC + "7" + CSI + (upperRow + off) + ";" + upperCol + "H" + CSI + "K" + ESC + "8");
    }

    @Override public void setCursor(int row, int col) {
        if (window != 1) return;
        upperRow = Math.max(1, row);
        upperCol = Math.max(1, col);
    }

    @Override public int[] getCursor() {
        return window == 1 ? new int[] {upperRow, upperCol}
                           : new int[] {lowerRow - upperLines - off, lowerCol + 1};
    }

    @Override public void showStatus(String left, String right) {
        int space = width - left.length() - right.length();
        String l = left;
        if (space < 1) {
            l = left.substring(0, Math.max(0, width - right.length() - 1));
            space = Math.max(1, width - l.length() - right.length());
        }
        String line = l + " ".repeat(space) + right;
        if (ansi) {
            raw(ESC + "7" + CSI + "1;1H" + CSI + "7m" + line + CSI + "0m" + ESC + "8");
        } else {
            flushWord();
            if (lowerCol > 0) newlineLower();
            raw(line + "\n");
        }
    }

    // ---------------------------------------------------------------- styles

    @Override public void setTextStyle(int s) {
        flushWord();
        style = s == 0 ? 0 : style | s;
        applyAttrs();
    }

    @Override public void setColour(int f, int b) {
        flushWord();
        if (f != 0) fg = f;
        if (b != 0) bg = b;
        applyAttrs();
    }

    private void applyAttrs() {
        if (!ansi) return;
        StringBuilder sb = new StringBuilder(CSI).append('0');
        if ((style & 1) != 0) sb.append(";7");
        if ((style & 2) != 0) sb.append(";1");
        if ((style & 4) != 0) sb.append(";3");
        sb.append(';').append(fg >= 2 && fg <= 9 ? 30 + fg - 2 : 39);
        sb.append(';').append(bg >= 2 && bg <= 9 ? 40 + bg - 2 : 49);
        raw(sb.append('m').toString());
    }

    @Override public void setBuffering(boolean on) {
        if (!on) flushWord();
        buffering = on;
    }

    @Override public void bell() { raw("\007"); out.flush(); }

    // ---------------------------------------------------------------- input

    @Override public String readLine() {
        flushWord();
        out.flush();
        try {
            String s = in.readLine();
            lowerCol = 0;
            lowerRow = Math.min(height, lowerRow + 1);
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    @Override public int readChar() {
        flushWord();
        out.flush();
        try {
            int c = in.read();
            if (c < 0) return -1;
            if (c == '\n' || c == '\r') return 13;
            while (in.ready()) {                       // discard the rest of the typed line
                int d = in.read();
                if (d == '\n' || d < 0) break;
            }
            return c;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override public String promptLine(String message) {
        flushWord();
        if (lowerCol > 0) newlineLower();
        raw(message);
        out.flush();
        try {
            String s = in.readLine();
            lowerCol = 0;
            lowerRow = Math.min(height, lowerRow + 1);
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- misc

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public boolean supportsStyles() { return ansi; }
    @Override public void flush() { flushWord(); out.flush(); }

    @Override public void close() {
        flushWord();
        if (ansi) raw(CSI + "0m" + CSI + "r" + CSI + height + ";1H");
        if (lowerCol > 0 || ansi) raw("\n");
        out.flush();
    }
}
