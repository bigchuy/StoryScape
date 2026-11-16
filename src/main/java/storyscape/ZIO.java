package storyscape;

import java.util.Map;

/**
 * Everything the interpreter needs from the outside world: screen, keyboard and prompts.
 * Implement this to run the Z-machine in a GUI, a web page, or a test harness.
 */
public interface ZIO {
    /** Called once before execution starts. */
    void init(int version);

    /** Print text to the currently selected window ('\n' ends a line). */
    void print(String text);

    /** Read a line of input (no terminator). Returns null at end of input. */
    String readLine();

    /**
     * Read one keystroke as a Unicode char (13 for Enter). ZSCII control codes may be returned
     * directly: 8 (delete), 27 (escape), 129-132 (cursor keys), 133-144 (function keys).
     * Returns -1 at end of input.
     */
    int readChar();

    /** Ask a question outside the story's own output (used for save/restore file names). */
    String promptLine(String message);

    /**
     * Ask for a file name for save/restore. The default asks on the console; a GUI can
     * show a file dialog. Return null to cancel.
     */
    default String chooseFile(boolean forSave, String suggested) {
        String reply = promptLine("Enter file name [" + suggested + "]: ");
        if (reply == null) return null;
        reply = reply.trim();
        return reply.isEmpty() ? suggested : reply;
    }

    void showStatus(String left, String right);

    void splitWindow(int lines);
    void setWindow(int window);
    int currentWindow();
    void eraseWindow(int window);
    void eraseLine();
    void setCursor(int row, int col);
    /** Returns {row, column}, both 1-based. */
    int[] getCursor();
    void setTextStyle(int style);
    void setColour(int foreground, int background);
    void setBuffering(boolean on);
    void bell();

    int width();
    int height();
    /** True if text styles, colours and a split screen are available. */
    boolean supportsStyles();

    void flush();
    void close();

    /**
     * The player's location changed (read from global 0 just before each line of input).
     * {@code travelled} is false when the jump came from restart, restore or undo rather than
     * from playing a turn, so a map should not draw a connection for it.
     */
    default void locationChanged(int object, String name, boolean travelled) {}

    /** Extra IFF chunks (4-character id to contents) to store in a saved game. */
    default Map<String, byte[]> extraSaveChunks() { return Map.of(); }

    /** Chunks from a restored game that the interpreter itself does not understand. */
    default void restoreExtraChunks(Map<String, byte[]> chunks) {}
}
