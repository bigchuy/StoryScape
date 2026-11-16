package storyscape.fx;

import javafx.application.Application;

/**
 * Plain main class for the JavaFX front-end. (A main class that extends Application cannot be
 * started from the class path on a modern JDK, hence this thin launcher.)
 */
public final class FxLauncher {
    private FxLauncher() {}

    public static void main(String[] args) {
        Application.launch(FxApp.class, args);
    }
}
