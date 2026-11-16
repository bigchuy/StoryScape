package storyscape.fx;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import storyscape.ZIO;
import storyscape.ZMachineHalt;

/**
 * Bridges the interpreter thread to the JavaFX UI. Output is posted to the FX thread; input
 * calls block the interpreter thread until the user answers. close() unblocks everything.
 */
final class FxIO implements ZIO {
    private final Window owner;
    private final GameView view;
    private final SidePanel side;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean halted;

    // Interpreter-thread state
    private String lastCommand;
    private int window;
    private int style;
    private int fg;
    private int bg;
    private int lowerCol;
    private final Consumer<String> onTitle;
    private final BannerTitle banner;

    /** @param onTitle told (on the FX thread) the title found in the story's banner; null to skip looking */
    FxIO(Window owner, GameView view, SidePanel side, Consumer<String> onTitle) {
        this.owner = owner;
        this.view = view;
        this.side = side;
        this.onTitle = onTitle;
        this.banner = onTitle == null ? null : new BannerTitle();
    }

    private void bannerText(String text) {
        if (banner == null) return;
        String title = banner.append(text);
        if (title != null) post(() -> onTitle.accept(title));
    }

    private void inputRequested() {
        if (banner != null) banner.inputRequested();
    }

    private void check() {
        if (halted) throw new ZMachineHalt();
    }

    private void post(Runnable r) {
        Platform.runLater(() -> {
            if (!halted) r.run();
        });
    }

    private <T> T await(CompletableFuture<T> f) {
        pending.add(f);
        if (halted) f.completeExceptionally(new ZMachineHalt());
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZMachineHalt();
        } catch (ExecutionException e) {
            throw new ZMachineHalt();
        } finally {
            pending.remove(f);
        }
    }

    private <T> T callFx(Supplier<T> action) {
        check();
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(action.get());
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return await(f);
    }

    // ------------------------------------------------------------------ ZIO

    @Override public void init(int version) {}

    @Override public void print(String text) {
        check();
        if (window == 1) {
            view.grid().put(text, style, fg, bg);
            view.requestRedraw();
            return;
        }
        int nl = text.lastIndexOf('\n');
        lowerCol = nl >= 0 ? text.length() - nl - 1 : lowerCol + text.length();
        int st = style;
        int f = fg;
        post(() -> view.appendLower(text, st, f, false));
        bannerText(text);
    }

    @Override public String readLine() {
        check();
        inputRequested();
        CompletableFuture<String> f = new CompletableFuture<>();
        post(() -> view.beginLineInput(f::complete));
        String line = await(f);
        lastCommand = line;
        return line;
    }

    @Override public int readChar() {
        check();
        inputRequested();
        CompletableFuture<Integer> f = new CompletableFuture<>();
        post(() -> view.beginCharInput(f::complete));
        return await(f);
    }

    @Override public String promptLine(String message) {
        return callFx(() -> {
            TextInputDialog dialog = Styles.dialog(new TextInputDialog());
            dialog.initOwner(owner);
            dialog.setTitle(FxApp.TITLE);
            dialog.setHeaderText(message);
            return dialog.showAndWait().orElse(null);
        });
    }

    @Override public String chooseFile(boolean forSave, String suggested) {
        return callFx(() -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(forSave ? "Save game" : "Restore game");
            File suggestion = new File(suggested);
            File dir = suggestion.getAbsoluteFile().getParentFile();
            String last = Settings.lastDirectory();
            if (!last.isEmpty() && new File(last).isDirectory()) dir = new File(last);
            if (dir != null && dir.isDirectory()) chooser.setInitialDirectory(dir);
            chooser.setInitialFileName(suggestion.getName());
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Saved games (*.sav, *.qzl)", "*.sav", "*.qzl"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            File chosen = forSave ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
            if (chosen == null) return null;
            if (chosen.getParentFile() != null) Settings.setLastDirectory(chosen.getParentFile().getPath());
            return chosen.getPath();
        });
    }

    @Override public void showStatus(String left, String right) {
        check();
        post(() -> view.showStatus(left, right));
    }

    @Override public void splitWindow(int lines) {
        check();
        view.grid().resize(Math.max(0, Math.min(lines, GameView.ROWS - 4)));
        post(view::layoutUpper);
    }

    @Override public void setWindow(int w) {
        window = w == 1 ? 1 : 0;
        if (window == 1) view.grid().home();
    }

    @Override public int currentWindow() { return window; }

    @Override public void eraseWindow(int w) {
        check();
        switch (w) {
            case 0:
                lowerCol = 0;
                post(view::clearLower);
                break;
            case 1:
                view.grid().erase();
                view.requestRedraw();
                break;
            case -1:
                window = 0;
                lowerCol = 0;
                view.grid().resize(0);
                view.grid().erase();
                post(() -> {
                    view.clearLower();
                    view.layoutUpper();
                });
                break;
            case -2:
                lowerCol = 0;
                view.grid().erase();
                post(() -> {
                    view.clearLower();
                    view.layoutUpper();
                });
                break;
            default:
                break;
        }
    }

    @Override public void eraseLine() {
        if (window != 1) return;
        view.grid().eraseToEndOfLine();
        view.requestRedraw();
    }

    @Override public void setCursor(int row, int col) {
        if (window == 1) view.grid().setCursor(row, col);
    }

    @Override public int[] getCursor() {
        return window == 1 ? view.grid().cursor() : new int[] {1, lowerCol + 1};
    }

    @Override public void setTextStyle(int s) { style = s == 0 ? 0 : style | s; }

    @Override public void setColour(int foreground, int background) {
        if (foreground != 0) fg = foreground;
        if (background != 0) bg = background;
    }

    @Override public void setBuffering(boolean on) {}

    @Override public void bell() {}

    @Override public int width() { return GameView.COLS; }

    @Override public int height() { return GameView.ROWS; }

    @Override public boolean supportsStyles() { return true; }

    @Override public void flush() {}

    @Override public void locationChanged(int object, String name, boolean travelled) {
        check();
        String command = lastCommand;
        post(() -> side.locationChanged(object, name, command, travelled));
    }

    @Override public Map<String, byte[]> extraSaveChunks() {
        return callFx(side::saveChunks);
    }

    @Override public void restoreExtraChunks(Map<String, byte[]> chunks) {
        check();
        if (banner != null) banner.stop();          // the opening is over; later text isn't the banner
        post(() -> side.restoreChunks(chunks));
    }

    @Override public void close() {
        halted = true;
        for (CompletableFuture<?> f : pending) f.completeExceptionally(new ZMachineHalt());
    }
}
