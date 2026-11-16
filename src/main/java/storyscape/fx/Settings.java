package storyscape.fx;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** User preferences persisted between runs (theme, font, last folder). */
final class Settings {
    enum ThemeMode { AUTO, LIGHT, DARK }

    /** Where the settings lived before the package was renamed from zmachine to storyscape. */
    private static final String LEGACY_NODE = "/zmachine/fx";

    private static final Preferences PREFS = load();

    private Settings() {}

    /** This package's preferences; on the first run after the rename, copied over from the old package. */
    private static Preferences load() {
        Preferences prefs = Preferences.userNodeForPackage(Settings.class);
        try {
            if (!prefs.absolutePath().equals(LEGACY_NODE) && prefs.keys().length == 0
                    && Preferences.userRoot().nodeExists(LEGACY_NODE)) {
                Preferences old = Preferences.userRoot().node(LEGACY_NODE);
                for (String key : old.keys()) prefs.put(key, old.get(key, ""));
            }
        } catch (BackingStoreException | IllegalStateException e) {
            // start with defaults
        }
        return prefs;
    }

    static ThemeMode theme() {
        try {
            return ThemeMode.valueOf(PREFS.get("theme", ThemeMode.AUTO.name()));
        } catch (IllegalArgumentException e) {
            return ThemeMode.AUTO;
        }
    }

    static void setTheme(ThemeMode mode) { PREFS.put("theme", mode.name()); }

    static double fontSize() { return PREFS.getDouble("fontSize", 16); }

    static void setFontSize(double size) { PREFS.putDouble("fontSize", size); }

    static boolean proportional() { return PREFS.getBoolean("proportional", false); }

    static void setProportional(boolean on) { PREFS.putBoolean("proportional", on); }

    static String lastDirectory() { return PREFS.get("lastDir", ""); }

    static void setLastDirectory(String dir) { PREFS.put("lastDir", dir); }

    /** A free-form setting (used by {@link Appearance}); empty when unset. */
    static String get(String key) { return PREFS.get(key, ""); }

    /** Store a free-form setting; null or empty removes it. */
    static void put(String key, String value) {
        if (value == null || value.isEmpty()) PREFS.remove(key);
        else PREFS.put(key, value);
    }
}
