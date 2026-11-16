package storyscape.fx;

import java.util.Arrays;

/**
 * Character-cell model of the Z-machine upper window. It is written by the interpreter thread
 * and read by the JavaFX thread, so every method is synchronized.
 */
final class UpperGrid {
    interface CellVisitor {
        void cell(int row, int col, char ch, int style, int fg, int bg);
    }

    private final int cols;
    private final int maxRows;
    private final char[][] chars;
    private final int[][] attrs;       // style | fg << 4 | bg << 10
    private int lines;
    private int row = 1, col = 1;

    UpperGrid(int cols, int maxRows) {
        this.cols = cols;
        this.maxRows = maxRows;
        this.chars = new char[maxRows][cols];
        this.attrs = new int[maxRows][cols];
        for (char[] r : chars) Arrays.fill(r, ' ');
    }

    synchronized void resize(int newLines) {
        lines = Math.max(0, Math.min(newLines, maxRows));
    }

    synchronized int lines() { return lines; }

    synchronized void erase() {
        for (int r = 0; r < maxRows; r++) {
            Arrays.fill(chars[r], ' ');
            Arrays.fill(attrs[r], 0);
        }
        row = 1;
        col = 1;
    }

    synchronized void eraseToEndOfLine() {
        if (row < 1 || row > maxRows) return;
        for (int c = col - 1; c < cols; c++) {
            if (c >= 0) {
                chars[row - 1][c] = ' ';
                attrs[row - 1][c] = 0;
            }
        }
    }

    synchronized void home() {
        row = 1;
        col = 1;
    }

    synchronized void setCursor(int r, int c) {
        row = Math.max(1, r);
        col = Math.max(1, c);
    }

    synchronized int[] cursor() { return new int[] {row, col}; }

    synchronized void put(String s, int style, int fg, int bg) {
        int attr = (style & 0x0F) | (fg << 4) | (bg << 10);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                row++;
                col = 1;
            } else {
                if (row >= 1 && row <= lines && col >= 1 && col <= cols) {
                    chars[row - 1][col - 1] = ch;
                    attrs[row - 1][col - 1] = attr;
                }
                col++;
            }
        }
    }

    synchronized void forEachCell(CellVisitor v) {
        for (int r = 0; r < lines; r++) {
            for (int c = 0; c < cols; c++) {
                int a = attrs[r][c];
                v.cell(r, c, chars[r][c], a & 0x0F, (a >> 4) & 0x3F, (a >> 10) & 0x3F);
            }
        }
    }
}
