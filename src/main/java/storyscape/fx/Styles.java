package storyscape.fx;

import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;

/**
 * Dialogs open in their own windows, which don't see the main scene's stylesheet. Pass each one
 * through {@link #dialog} before showing it so it gets the app's UI font size and current theme.
 * FX thread only.
 */
final class Styles {
    static final String SHEET = Styles.class.getResource("storyscape.css").toExternalForm();
    private static boolean dark;

    private Styles() {}

    /** The theme for dialogs opened from now on. */
    static void setDark(boolean on) { dark = on; }

    static <D extends Dialog<?>> D dialog(D d) {
        DialogPane pane = d.getDialogPane();
        if (!pane.getStylesheets().contains(SHEET)) pane.getStylesheets().add(SHEET);
        pane.getStyleClass().removeAll("dark", "light");
        if (!pane.getStyleClass().contains("zm-dialog")) pane.getStyleClass().add("zm-dialog");
        pane.getStyleClass().add(dark ? "dark" : "light");
        return d;
    }
}
