package storyscape.fx;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Shown in a side tab (Cover Art, Game Manual, Hints) when the file it displays isn't beside the
 * story: says what the file is, the exact name to give it, the folder to put it in (copyable), and
 * offers Open Folder and Check Again. FX thread only.
 */
final class MissingFilePanel extends ScrollPane {
    private static final double MAX_TEXT_WIDTH = 480;

    /**
     * What to tell the user.
     *
     * @param heading  e.g. "No cover art yet"
     * @param intro    what the tab shows and where it looks
     * @param what     the first step: what kind of file to get
     * @param fileName the exact name the file must have
     * @param note     an extra remark after the steps, or null
     */
    record Help(String heading, String intro, String what, String fileName, String note) {}

    private final Label heading = text("zm-missing-title");
    private final Label intro = text("zm-missing-text");
    private final Label step1 = text("zm-missing-text");
    private final Label step2 = text("zm-missing-text");
    private final Label step3 = text("zm-missing-text");
    private final TextField folderField = new TextField();
    private final Label step4 = text("zm-missing-text");
    private final Label note = text("zm-missing-note");
    private Path folder;
    private Runnable onRetry;

    MissingFilePanel() {
        getStyleClass().add("zm-scroll");
        folderField.setEditable(false);
        folderField.setFocusTraversable(false);
        folderField.getStyleClass().add("zm-missing-path");

        Button openFolder = new Button("Open Folder");
        openFolder.setOnAction(e -> openFolder());
        Button retry = new Button("Check Again");
        retry.setOnAction(e -> {
            if (onRetry != null) onRetry.run();
        });
        HBox buttons = new HBox(8, openFolder, retry);
        buttons.setPadding(new Insets(6, 0, 0, 0));

        VBox body = new VBox(10, heading, intro, step1, step2, step3, folderField, step4, buttons, note);
        body.setMaxWidth(MAX_TEXT_WIDTH);
        body.setFillWidth(true);
        VBox.setMargin(folderField, new Insets(-4, 0, 0, 22));

        StackPane holder = new StackPane(body);
        holder.setAlignment(Pos.TOP_CENTER);
        holder.setPadding(new Insets(24, 20, 20, 20));
        holder.getStyleClass().add("zm-cover");
        setContent(holder);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setMinSize(0, 0);
    }

    private static Label text(String style) {
        Label l = new Label();
        l.getStyleClass().add(style);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    /** Fill in the help for {@code story}; {@code retry} runs on Check Again. */
    void show(Help help, Path story, Runnable retry) {
        folder = story.toAbsolutePath().getParent();
        onRetry = retry;
        heading.setText(help.heading());
        intro.setText(help.intro());
        step1.setText("1.  " + help.what());
        step2.setText("2.  Name it  " + help.fileName());
        step3.setText("3.  Put it in the same folder as the story file:");
        folderField.setText(folder == null ? "" : folder.toString());
        step4.setText("4.  Click Check Again (or open the story again).");
        boolean hasNote = help.note() != null && !help.note().isBlank();
        note.setText(hasNote ? help.note() : "");
        note.setVisible(hasNote);
        note.setManaged(hasNote);
        setVvalue(0);
    }

    /** Show the story's folder in the system file manager (off the FX thread: AWT may be slow to start). */
    private void openFolder() {
        Path dir = folder;
        if (dir == null) return;
        Thread t = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir.toFile());
                }
            } catch (IOException | RuntimeException ignored) {
                // no file manager available: the path is still there to copy
            }
        }, "open-folder");
        t.setDaemon(true);
        t.start();
    }
}
