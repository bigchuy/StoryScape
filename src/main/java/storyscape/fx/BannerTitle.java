package storyscape.fx;

import java.util.regex.Pattern;

/**
 * Finds a story's title in its opening text. Plain Z-code files have no title field, but nearly
 * every game prints a banner at the start, with the title on the first line of the paragraph that
 * holds the copyright or "Release N / Serial number" line:
 *
 * <pre>
 * ZORK I: The Great Underground Empire
 * Copyright (c) 1981, 1982, 1983 Infocom, Inc. All rights reserved.
 *
 * Curses
 * An Interactive Fiction by Graham Nelson
 * Release 16 / Serial number 951024 / Inform v1502 Library 5/12
 * </pre>
 *
 * Feed it the lower window's text as it is printed; it gives up after the first few commands.
 * Not thread-safe: used from the interpreter thread only.
 */
final class BannerTitle {
    private static final int MAX_CHARS = 6000;
    private static final int MAX_INPUTS = 3;
    private static final int MAX_TITLE = 80;
    private static final Pattern MARKER = Pattern.compile(
            "(?i)(\\b(release|revision)\\s+\\d+\\s*/\\s*serial\\b|\\bcopyright\\b|\\(c\\)|©)");

    private final StringBuilder text = new StringBuilder();
    private int inputs;
    private boolean done;

    /** Add printed text; returns the title the first time it is found, otherwise null. */
    String append(String s) {
        if (done) return null;
        text.append(s);
        String found = scan();
        if (found != null || text.length() > MAX_CHARS) done = true;
        return found;
    }

    /** Stop looking (e.g. a saved game was restored: what follows isn't the opening any more). */
    void stop() {
        done = true;
    }

    /** The player was asked for input; after a few prompts the banner isn't coming. */
    void inputRequested() {
        if (++inputs >= MAX_INPUTS) done = true;
    }

    private String scan() {
        String[] lines = text.toString().split("\r?\n", -1);
        // the last line may still be incomplete, so only look at finished ones
        for (int i = 0; i < lines.length - 1; i++) {
            if (!MARKER.matcher(lines[i]).find()) continue;
            int start = i;
            while (start > 0 && !lines[start - 1].isBlank()) start--;
            if (start == i) continue;                      // nothing above the marker in its paragraph
            String title = lines[start].strip();
            if (title.isEmpty() || title.length() > MAX_TITLE || MARKER.matcher(title).find()) continue;
            return title;
        }
        return null;
    }
}
