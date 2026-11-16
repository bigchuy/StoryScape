package storyscape.fx;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import storyscape.Interpreter;
import storyscape.Main;
import storyscape.ZMachineException;
import storyscape.ZMachineHalt;

/** The JavaFX application window: menus, theming, drag-and-drop and the interpreter thread. */
public final class FxApp extends Application {
    static final String TITLE = "StoryScape";
    private static final double MIN_SIDE_WIDTH = 220;   // side panel, divider and window frame

    private Stage stage;
    private BorderPane root;
    private GameView view;
    private SidePanel side;

    private Interpreter interpreter;
    private FxIO io;
    private Thread worker;
    private Path current;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        this.view = new GameView();
        this.side = new SidePanel();

        MenuBar menuBar = buildMenus();
        menuBar.setUseSystemMenuBar(true);

        SplitPane split = new SplitPane(view, side);
        split.setDividerPositions(0.7);
        // window resizes go to the notes/map panel; the story keeps its width (drag the divider to change it)
        SplitPane.setResizableWithParent(view, false);

        root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(split);

        Scene scene = new Scene(root, 1400, 800);
        scene.getStylesheets().add(Styles.SHEET);
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            Dragboard board = e.getDragboard();
            boolean ok = board.hasFiles() && !board.getFiles().isEmpty();
            if (ok) openStory(board.getFiles().get(0).toPath());
            e.setDropCompleted(ok);
            e.consume();
        });

        applyTheme();
        Platform.getPreferences().colorSchemeProperty().addListener((obs, oldScheme, newScheme) -> applyTheme());

        stage.setTitle(TITLE);
        stage.setScene(scene);
        // the window can't shrink below the story pane's minimum (60 characters) plus some of the
        // side panel and the window frame, so the divider never has to cut into the story
        stage.minWidthProperty().bind(view.minWidthProperty().add(MIN_SIDE_WIDTH));
        stage.setOnCloseRequest(e -> {
            stopStory();
            Platform.exit();
        });
        stage.show();

        List<String> args = getParameters().getRaw();
        String file = null;
        for (String a : args) {
            if (!a.startsWith("--")) file = a;
        }
        if (file != null) {
            openStory(Path.of(file));
        } else {
            view.appendLower(TITLE + "\n\nOpen a story file with File \u25B8 Open Story\u2026 (Ctrl/Cmd+O), "
                    + "or drop one onto this window.\n\nSupported: .z1 \u2013 .z5, .z7, .z8, .dat and .zblorb files.\n",
                    0, 0, false);
        }
    }

    @Override
    public void stop() {
        stopStory();
    }

    // ------------------------------------------------------------------ menus

    private MenuBar buildMenus() {
        Menu file = new Menu("File", null,
                item("Open Story\u2026", KeyCode.O, this::chooseStory),
                item("Restart Story", KeyCode.R, this::restartStory),
                item("Save Transcript\u2026", KeyCode.T, this::saveTranscript),
                new SeparatorMenuItem(),
                item("Preferences\u2026", KeyCode.COMMA, this::preferences),
                new SeparatorMenuItem(),
                item("Quit", KeyCode.Q, () -> {
                    stopStory();
                    Platform.exit();
                }));

        ToggleGroup themes = new ToggleGroup();
        Menu theme = new Menu("Theme", null,
                themeItem("Follow System", Settings.ThemeMode.AUTO, themes),
                themeItem("Light", Settings.ThemeMode.LIGHT, themes),
                themeItem("Dark", Settings.ThemeMode.DARK, themes));

        CheckMenuItem proportional = new CheckMenuItem("Proportional Text");
        proportional.setSelected(Settings.proportional());
        proportional.setOnAction(e -> view.setProportional(proportional.isSelected()));

        Menu viewMenu = new Menu("View", null,
                theme,
                new SeparatorMenuItem(),
                item("Larger Text", KeyCode.EQUALS, () -> view.adjustFontSize(1)),
                item("Smaller Text", KeyCode.MINUS, () -> view.adjustFontSize(-1)),
                item("Default Text Size", KeyCode.DIGIT0, () -> view.setFontSize(16)),
                new SeparatorMenuItem(),
                proportional);

        Menu help = new Menu("Help", null, item("About", null, this::about));
        return new MenuBar(file, viewMenu, help);
    }

    private static MenuItem item(String text, KeyCode key, Runnable action) {
        MenuItem mi = new MenuItem(text);
        if (key != null) mi.setAccelerator(new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN));
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private RadioMenuItem themeItem(String text, Settings.ThemeMode mode, ToggleGroup group) {
        RadioMenuItem r = new RadioMenuItem(text);
        r.setToggleGroup(group);
        r.setSelected(Settings.theme() == mode);
        r.setOnAction(e -> {
            Settings.setTheme(mode);
            applyTheme();
        });
        return r;
    }

    /** File ▸ Preferences: fonts and colours for the story and the map; OK applies them at once. */
    private void preferences() {
        if (!PreferencesDialog.show(stage, root.getStyleClass().contains("dark"))) return;
        view.applyAppearance();
        side.applyAppearance();
    }

    private void applyTheme() {
        Settings.ThemeMode mode = Settings.theme();
        boolean dark = mode == Settings.ThemeMode.DARK
                || (mode == Settings.ThemeMode.AUTO && Platform.getPreferences().getColorScheme() == ColorScheme.DARK);
        ObservableList<String> classes = root.getStyleClass();
        classes.remove("dark");
        classes.remove("light");
        classes.add(dark ? "dark" : "light");
        view.setDark(dark);
        side.setDark(dark);
        Styles.setDark(dark);
    }

    // ------------------------------------------------------------------ story lifecycle

    private void chooseStory() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open story file");
        String last = Settings.lastDirectory();
        if (!last.isEmpty() && new File(last).isDirectory()) chooser.setInitialDirectory(new File(last));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Z-code stories", "*.z1", "*.z2", "*.z3", "*.z4", "*.z5",
                        "*.z7", "*.z8", "*.dat", "*.zblorb", "*.zlb"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) openStory(chosen.toPath());
    }

    private void restartStory() {
        if (current != null) openStory(current);
    }

    private void openStory(Path path) {
        stopStory();
        byte[] data;
        String metadataTitle;
        try {
            byte[] raw = Files.readAllBytes(path);
            metadataTitle = Main.blorbTitle(raw);
            data = Main.unwrapBlorb(raw);
        } catch (IOException | ZMachineException e) {
            error("Cannot load " + path.getFileName(), e.getMessage());
            return;
        }
        // a Blorb's metadata names the story; otherwise watch the opening banner for its title
        FxIO[] self = new FxIO[1];
        FxIO newIo = new FxIO(stage, view, side, metadataTitle != null ? null : title -> {
            if (io == self[0]) setStoryTitle(title);
        });
        self[0] = newIo;
        Interpreter z;
        try {
            z = new Interpreter(data, newIo, path);
        } catch (ZMachineException e) {
            error("Cannot start " + path.getFileName(), e.getMessage());
            return;
        }
        boolean restart = path.equals(current);
        view.resetAll();
        if (restart) {
            side.resetMaps();                           // restart: keep notes, start a fresh map
        } else {
            side.clearAll();
            side.setStory(path);                        // cover art and manual beside the story file
        }
        side.showCoverArt();
        if (!restart) {
            Path save = chooseSaveToResume(path, z);    // a saved game beside a newly opened story
            if (save != null) z.restoreOnStart(save);
        }
        current = path;
        interpreter = z;
        io = newIo;
        // until the banner shows the real title, the file name stands in for it
        setStoryTitle(metadataTitle != null ? metadataTitle : path.getFileName().toString());
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Settings.setLastDirectory(parent.toString());

        Thread t = new Thread(() -> runStory(z), "z-machine");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /**
     * The *.sav files in the story's folder that were saved from this story: none gives null (start
     * fresh), one is restored straight away, and with several the player picks one (or Cancel to
     * start a new game). The newest save is offered first.
     */
    private Path chooseSaveToResume(Path story, Interpreter z) {
        Path dir = story.toAbsolutePath().getParent();
        if (dir == null) return null;
        List<Path> saves = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sav")
                        && Files.isRegularFile(p) && z.isSaveFor(p)) {
                    saves.add(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (saves.isEmpty()) return null;
        if (saves.size() == 1) return saves.get(0);

        saves.sort(Comparator.comparing(FxApp::modified).reversed());
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path p : saves) byName.put(p.getFileName().toString(), p);
        ChoiceDialog<String> dialog = Styles.dialog(
                new ChoiceDialog<>(saves.get(0).getFileName().toString(), byName.keySet()));
        dialog.initOwner(stage);
        dialog.setTitle(TITLE);
        dialog.setHeaderText("Several saved games were found for " + story.getFileName() + ".");
        dialog.setContentText("Restore:");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("New Game");
        return dialog.showAndWait().map(byName::get).orElse(null);
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private void setStoryTitle(String storyTitle) {
        stage.setTitle(TITLE + " — " + storyTitle);
    }

    /** Runs on the interpreter thread. */
    private void runStory(Interpreter z) {
        try {
            z.run();
            notice(z, "[Story finished \u2014 File \u25B8 Restart Story to play again]");
        } catch (ZMachineHalt halt) {
            // window closed or another story opened: nothing to report
        } catch (RuntimeException e) {
            notice(z, "[Z-machine error: " + e.getMessage() + "]");
        }
    }

    private void notice(Interpreter z, String message) {
        Platform.runLater(() -> {
            if (interpreter == z) view.appendNotice(message);
        });
    }

    private void stopStory() {
        if (io != null) io.close();
        if (interpreter != null) interpreter.stop();
        if (worker != null) worker.interrupt();
        io = null;
        interpreter = null;
        worker = null;
    }

    private void saveTranscript() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save transcript");
        chooser.setInitialFileName(current == null ? "transcript.txt" : current.getFileName() + ".txt");
        File chosen = chooser.showSaveDialog(stage);
        if (chosen == null) return;
        try {
            Files.writeString(chosen.toPath(), view.plainText(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            error("Cannot save transcript", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ dialogs

    private void error(String header, String detail) {
        Alert alert = Styles.dialog(new Alert(Alert.AlertType.ERROR, detail));
        alert.initOwner(stage);
        alert.setTitle(TITLE);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private void about() {
        Alert alert = Styles.dialog(new Alert(Alert.AlertType.INFORMATION,
                "StoryScape plays interactive fiction: a Z-machine interpreter (Standard 1.1) for Infocom and "
                        + "Inform story files, versions 1\u20135, 7 and 8, with an automatic map, notes, cover art, "
                        + "the game manual and hints alongside the story."));
        alert.initOwner(stage);
        alert.setTitle("About");
        alert.setHeaderText(TITLE);
        alert.showAndWait();
    }
}
