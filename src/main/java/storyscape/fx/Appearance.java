package storyscape.fx;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * The fonts and colours chosen in File ▸ Preferences, saved in {@link Settings}. Each value is
 * either the user's choice or null, meaning "follow the theme": the defaults below change with
 * Light / Dark, a choice doesn't. FX thread only.
 */
final class Appearance {
    /** A font choice: family and size in points. */
    record FontChoice(String family, double size) {}

    // theme defaults (the same colours as storyscape.css)
    static final String LIGHT_BG = "#fbf8f1", DARK_BG = "#1b1d21";
    static final String LIGHT_FG = "#26231f", DARK_FG = "#dcdad5";
    static final String LIGHT_ACCENT = "#9a3b2e", DARK_ACCENT = "#e0a35e";
    static final String NOTE_DEFAULT = "#f7d33c";
    static final double MAP_FONT_SIZE = 12;

    // Story
    static FontChoice headerFont;      // status line: room name and score
    static Color storyBackground;
    static Color storyText;
    static Color commandColour;        // typed and echoed commands, the prompt
    // Map
    static FontChoice mapFont;
    static Color mapText;
    static Color mapBackground;
    static Color mapNote;
    static Color mapActiveRoom;

    private static Font mapPlain, mapBold, mapLabel;

    private Appearance() {}

    static {
        load();
    }

    // ------------------------------------------------------------------ effective values

    static Color storyBackground(boolean dark) { return or(storyBackground, dark ? DARK_BG : LIGHT_BG); }

    static Color storyText(boolean dark) { return or(storyText, dark ? DARK_FG : LIGHT_FG); }

    static Color commandColour(boolean dark) { return or(commandColour, dark ? DARK_ACCENT : LIGHT_ACCENT); }

    static Color mapText(boolean dark) { return or(mapText, dark ? DARK_FG : LIGHT_FG); }

    static Color mapBackground(boolean dark) { return or(mapBackground, dark ? DARK_BG : LIGHT_BG); }

    static Color mapNote() { return or(mapNote, NOTE_DEFAULT); }

    static Color mapActiveRoom(boolean dark) { return or(mapActiveRoom, dark ? DARK_ACCENT : LIGHT_ACCENT); }

    /** The status line font when none is chosen: the UI's own. */
    static FontChoice defaultHeaderFont() {
        Font f = Font.getDefault();
        return new FontChoice(f.getFamily(), Math.round(f.getSize()));
    }

    static FontChoice defaultMapFont() { return new FontChoice(Font.getDefault().getFamily(), MAP_FONT_SIZE); }

    /** Room names on the map. */
    static Font mapFont() { return mapPlain; }

    /** The current room's name. */
    static Font mapBoldFont() { return mapBold; }

    /** Exit direction labels: bold, a point smaller. */
    static Font mapLabelFont() { return mapLabel; }

    /** Black or white, whichever reads better on {@code fill}. */
    static Color contrastOn(Color fill) {
        double lum = 0.2126 * fill.getRed() + 0.7152 * fill.getGreen() + 0.0722 * fill.getBlue();
        return lum > 0.55 ? Color.web("#1b1d21") : Color.web("#fbf8f1");
    }

    static String web(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private static Color or(Color c, String fallback) { return c != null ? c : Color.web(fallback); }

    // ------------------------------------------------------------------ persistence

    static void load() {
        headerFont = font("pref.headerFont");
        storyBackground = colour("pref.storyBg");
        storyText = colour("pref.storyText");
        commandColour = colour("pref.command");
        mapFont = font("pref.mapFont");
        mapText = colour("pref.mapText");
        mapBackground = colour("pref.mapBg");
        mapNote = colour("pref.mapNote");
        mapActiveRoom = colour("pref.mapActive");
        refreshFonts();
    }

    static void save() {
        putFont("pref.headerFont", headerFont);
        putColour("pref.storyBg", storyBackground);
        putColour("pref.storyText", storyText);
        putColour("pref.command", commandColour);
        putFont("pref.mapFont", mapFont);
        putColour("pref.mapText", mapText);
        putColour("pref.mapBg", mapBackground);
        putColour("pref.mapNote", mapNote);
        putColour("pref.mapActive", mapActiveRoom);
        refreshFonts();
    }

    private static void refreshFonts() {
        FontChoice f = mapFont != null ? mapFont : defaultMapFont();
        mapPlain = Font.font(f.family(), f.size());
        mapBold = Font.font(f.family(), FontWeight.BOLD, f.size());
        mapLabel = Font.font(f.family(), FontWeight.BOLD, Math.max(6, f.size() - 1));
    }

    private static Color colour(String key) {
        String s = Settings.get(key);
        if (s.isEmpty()) return null;
        try {
            return Color.web(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void putColour(String key, Color c) { Settings.put(key, c == null ? null : web(c)); }

    /** Stored as "size|family". */
    private static FontChoice font(String key) {
        String s = Settings.get(key);
        int bar = s.indexOf('|');
        if (bar <= 0 || bar == s.length() - 1) return null;
        try {
            return new FontChoice(s.substring(bar + 1), Double.parseDouble(s.substring(0, bar)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putFont(String key, FontChoice f) {
        Settings.put(key, f == null ? null : f.size() + "|" + f.family());
    }
}
