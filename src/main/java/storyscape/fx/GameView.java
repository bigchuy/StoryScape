package storyscape.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * The story view: an optional v3 status bar, the Z-machine upper window (a canvas of character
 * cells), the scrolling lower window (a TextFlow) and a command line. All methods except the
 * ones noted must be called on the JavaFX application thread.
 */
final class GameView extends StackPane {
    static final int COLS = 100;
    static final int ROWS = 32;
    static final int MIN_COLS = 60;                  // the story pane is never narrower than this

    private static final int MAX_NODES = 6000;
    private static final int MAX_NODE_CHARS = 4000;
    private static final String[] MONO_FAMILIES =
            {"Cascadia Mono", "Consolas", "Menlo", "SF Mono", "DejaVu Sans Mono", "Liberation Mono", "Courier New"};
    private static final String[] SERIF_FAMILIES =
            {"Georgia", "Iowan Old Style", "Palatino", "DejaVu Serif", "Liberation Serif", "Times New Roman"};

    private final UpperGrid grid = new UpperGrid(COLS, ROWS);
    private final HBox statusBar = new HBox();
    private final Label statusLeft = new Label();
    private final Label statusRight = new Label();
    private final Canvas upper = new Canvas();
    private final HBox upperBox = new HBox(upper);
    private final TextFlow flow = new TextFlow();
    private final ScrollPane scroll = new ScrollPane(flow);
    private final TextField input = new TextField();
    private final HBox inputBar = new HBox();
    private final VBox column = new VBox();
    private final AtomicBoolean redrawQueued = new AtomicBoolean();

    private final String monoFamily = pickFamily(MONO_FAMILIES, "Monospaced");
    private final String serifFamily = pickFamily(SERIF_FAMILIES, "Serif");
    private final Font[] storyFonts = new Font[16];
    private final Font[] monoFonts = new Font[16];

    private double fontSize = Settings.fontSize();
    private boolean proportional = Settings.proportional();
    private boolean dark;
    private double cellW = 10, cellH = 20;

    private Consumer<String> lineWaiter;
    private IntConsumer charWaiter;
    private final List<String> history = new ArrayList<>();
    private int historyPos;

    GameView() {
        getStyleClass().add("zm-story");
        setFocusTraversable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusBar.getChildren().addAll(statusLeft, spacer, statusRight);
        statusBar.getStyleClass().add("zm-status");
        show(statusBar, false);

        upperBox.setPadding(new Insets(6, 14, 6, 14));
        show(upperBox, false);

        flow.setPadding(new Insets(10, 14, 10, 14));
        flow.setLineSpacing(2);
        scroll.getStyleClass().add("zm-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        flow.heightProperty().addListener((obs, oldH, newH) -> scroll.setVvalue(1.0));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label prompt = new Label("\u25B8");
        prompt.getStyleClass().add("zm-prompt");
        input.getStyleClass().add("zm-input");
        HBox.setHgrow(input, Priority.ALWAYS);
        inputBar.getChildren().addAll(prompt, input);
        inputBar.setSpacing(8);
        inputBar.setAlignment(Pos.CENTER_LEFT);
        inputBar.getStyleClass().add("zm-status");

        column.getChildren().addAll(statusBar, upperBox, scroll, inputBar);
        getChildren().add(column);
        StackPane.setAlignment(column, Pos.TOP_CENTER);

        input.setOnAction(e -> submitLine());
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.UP) {
                recall(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                recall(1);
                e.consume();
            }
        });
        // a left click anywhere on the story side puts the keyboard back in the command field
        // Controls inside this pane take focus as they handle the mouse (the ScrollPane grabs it
        // on every click, scroll bars on press), and they do so after this filter has run. So
        // focus the field again once each press, release and click has been fully handled.
        javafx.event.EventHandler<MouseEvent> refocus = e -> {
            if (e.getButton() == MouseButton.PRIMARY) Platform.runLater(this::focusInput);
        };
        addEventFilter(MouseEvent.MOUSE_PRESSED, refocus);
        addEventFilter(MouseEvent.MOUSE_RELEASED, refocus);
        addEventFilter(MouseEvent.MOUSE_CLICKED, refocus);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);

        setInputEnabled(false, "");
        applyFonts();
        applyAppearance();
    }

    UpperGrid grid() { return grid; }

    // ------------------------------------------------------------------ fonts and theme

    private static String pickFamily(String[] preferred, String fallback) {
        List<String> installed = Font.getFamilies();
        for (String f : preferred) {
            if (installed.contains(f)) return f;
        }
        return fallback;
    }

    private Font fontFor(int style) {
        int key = style & 15;
        if (storyFonts[key] == null) {
            boolean fixed = !proportional || (key & 8) != 0;
            storyFonts[key] = Font.font(fixed ? monoFamily : serifFamily,
                    (key & 2) != 0 ? FontWeight.BOLD : FontWeight.NORMAL,
                    (key & 4) != 0 ? FontPosture.ITALIC : FontPosture.REGULAR, fontSize);
        }
        return storyFonts[key];
    }

    private Font monoFont(int style) {
        int key = style & 15;
        if (monoFonts[key] == null) {
            monoFonts[key] = Font.font(monoFamily,
                    (key & 2) != 0 ? FontWeight.BOLD : FontWeight.NORMAL,
                    (key & 4) != 0 ? FontPosture.ITALIC : FontPosture.REGULAR, fontSize);
        }
        return monoFonts[key];
    }

    private void applyFonts() {
        java.util.Arrays.fill(storyFonts, null);
        java.util.Arrays.fill(monoFonts, null);
        Text probe = new Text("M");
        probe.setFont(monoFont(0));
        cellW = probe.getLayoutBounds().getWidth();
        cellH = Math.ceil(probe.getLayoutBounds().getHeight());
        column.setMaxWidth(COLS * cellW + 28 + 16);
        // never narrower than MIN_COLS characters (plus the text padding and scroll bar); the
        // split pane's divider stops here, and FxApp keeps the window wide enough for it
        setMinWidth(Math.ceil(MIN_COLS * cellW + 28 + 16));
        input.setFont(fontFor(0));
        for (Node n : flow.getChildren()) {
            if (n instanceof Text t) {
                Object style = t.getProperties().get("zstyle");
                t.setFont(fontFor(style instanceof Integer i ? i : 0));
            }
        }
        layoutUpper();
    }

    void setFontSize(double size) {
        fontSize = Math.max(9, Math.min(40, size));
        Settings.setFontSize(fontSize);
        applyFonts();
    }

    void adjustFontSize(double delta) { setFontSize(fontSize + delta); }

    void setProportional(boolean on) {
        proportional = on;
        Settings.setProportional(on);
        applyFonts();
    }

    boolean isProportional() { return proportional; }

    void setDark(boolean on) {
        dark = on;
        redrawUpper();
    }

    private Color textColor() { return Appearance.storyText(dark); }

    private Color backgroundColor() { return Appearance.storyBackground(dark); }

    /**
     * Apply File ▸ Preferences. Chosen colours override the theme's looked-up colours for this
     * pane only (-zm-bg for the background, -zm-fg for story text, -zm-accent for commands and the
     * prompt); colours left at their default keep following the theme.
     */
    void applyAppearance() {
        Color bg = Appearance.storyBackground, fg = Appearance.storyText, cmd = Appearance.commandColour;
        setStyle(bg == null ? "" : "-zm-bg: " + web(bg) + ";");
        flow.setStyle((fg == null ? "" : "-zm-fg: " + web(fg) + ";")
                + (cmd == null ? "" : "-zm-accent: " + web(cmd) + ";"));
        inputBar.setStyle(cmd == null ? "" : "-zm-accent: " + web(cmd) + ";");
        input.setStyle(cmd == null ? "" : "-fx-text-fill: " + web(cmd) + ";");
        Appearance.FontChoice h = Appearance.headerFont;
        String header = h == null ? ""
                : "-fx-font-family: \"" + h.family().replace("\"", "") + "\"; -fx-font-size: " + h.size() + "px;";
        statusLeft.setStyle(header);
        statusRight.setStyle(header);
        redrawUpper();
    }

    private static Color zColour(int c) {
        switch (c) {
            case 2: return Color.web("#111111");
            case 3: return Color.web("#cc3333");
            case 4: return Color.web("#33aa33");
            case 5: return Color.web("#ccaa22");
            case 6: return Color.web("#3355cc");
            case 7: return Color.web("#aa44aa");
            case 8: return Color.web("#33aaaa");
            default: return Color.web("#f0f0f0");
        }
    }

    private static String web(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }

    // ------------------------------------------------------------------ lower window

    /** Append story text; {@code userInput} renders in the accent colour (echoed commands, notices). */
    void appendLower(String s, int style, int fg, boolean userInput) {
        boolean accent = userInput || (style & 1) != 0;       // reverse video becomes the accent colour
        String css = accent ? "zm-input-text" : "zm-text";
        String inline = (!accent && fg >= 2 && fg <= 9) ? "-fx-fill: " + web(zColour(fg)) + ";" : "";
        String key = css + "|" + style + "|" + inline;
        ObservableList<Node> kids = flow.getChildren();
        if (!kids.isEmpty() && kids.get(kids.size() - 1) instanceof Text last
                && key.equals(last.getProperties().get("zkey")) && last.getText().length() < MAX_NODE_CHARS) {
            last.setText(last.getText() + s);
            return;
        }
        Text t = new Text(s);
        t.getStyleClass().add(css);
        if (!inline.isEmpty()) t.setStyle(inline);
        t.getProperties().put("zkey", key);
        t.setFont(fontFor(style));
        kids.add(t);
        if (kids.size() > MAX_NODES) kids.remove(0, MAX_NODES / 5);   // drop the oldest fifth in one batched change
    }

    void appendNotice(String message) {
        appendLower("\n" + message + "\n", 0, 0, true);
    }

    void clearLower() { flow.getChildren().clear(); }

    String plainText() {
        StringBuilder sb = new StringBuilder();
        for (Node n : flow.getChildren()) {
            if (n instanceof Text t) sb.append(t.getText());
        }
        return sb.toString();
    }

    /** Return to a blank state before a new story starts. */
    void resetAll() {
        clearLower();
        grid.resize(0);
        grid.erase();
        show(statusBar, false);
        lineWaiter = null;
        charWaiter = null;
        setInputEnabled(false, "");
        layoutUpper();
    }

    // ------------------------------------------------------------------ status bar and upper window

    void showStatus(String left, String right) {
        statusLeft.setText(left.strip());
        statusRight.setText(right.strip());
        show(statusBar, true);
    }

    void layoutUpper() {
        int n = grid.lines();
        show(upperBox, n > 0);
        upper.setWidth(COLS * cellW);
        upper.setHeight(Math.max(1, n * cellH));
        redrawUpper();
    }

    /** Safe to call from any thread; coalesces bursts of updates into one repaint. */
    void requestRedraw() {
        if (redrawQueued.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                redrawQueued.set(false);
                redrawUpper();
            });
        }
    }

    private void redrawUpper() {
        if (grid.lines() == 0) return;
        GraphicsContext g = upper.getGraphicsContext2D();
        Color bg = backgroundColor();
        Color fg = textColor();
        g.setFill(bg);
        g.fillRect(0, 0, upper.getWidth(), upper.getHeight());
        g.setTextBaseline(VPos.TOP);
        grid.forEachCell((r, c, ch, style, fgc, bgc) -> {
            Color f = fgc >= 2 && fgc <= 9 ? zColour(fgc) : fg;
            Color b = bgc >= 2 && bgc <= 9 ? zColour(bgc) : null;
            if ((style & 1) != 0) {                      // reverse video
                Color swap = f;
                f = b != null ? b : bg;
                b = swap;
            }
            if (b != null) {
                g.setFill(b);
                g.fillRect(c * cellW, r * cellH, cellW + 0.5, cellH);
            }
            if (ch != ' ') {
                g.setFill(f);
                g.setFont(monoFont(style));
                g.fillText(String.valueOf(ch), c * cellW, r * cellH);
            }
        });
    }

    // ------------------------------------------------------------------ input

    void beginLineInput(Consumer<String> waiter) {
        charWaiter = null;
        lineWaiter = waiter;
        setInputEnabled(true, "Type a command and press Enter");
        input.requestFocus();
    }

    void beginCharInput(IntConsumer waiter) {
        lineWaiter = null;
        charWaiter = waiter;
        setInputEnabled(false, "Press any key");
        requestFocus();
    }

    /**
     * Focus the command field without disturbing what is typed in it; while it is disabled (waiting
     * for a single key, or for the story) focus the view itself so a key press still reaches it.
     */
    private void focusInput() {
        if (input.isDisabled()) {
            if (!isFocused()) requestFocus();
            return;
        }
        if (input.isFocused()) return;
        int caret = input.getCaretPosition();
        input.requestFocus();
        input.deselect();                       // focusing a text field may select all its text
        input.positionCaret(caret);
    }

    private void setInputEnabled(boolean on, String hint) {
        input.setDisable(!on);
        input.setPromptText(hint);
        if (on) input.getStyleClass().remove("zm-input-disabled");
        else if (!input.getStyleClass().contains("zm-input-disabled")) input.getStyleClass().add("zm-input-disabled");
    }

    private void submitLine() {
        Consumer<String> waiter = lineWaiter;
        if (waiter == null) return;
        lineWaiter = null;
        String text = input.getText();
        input.clear();
        setInputEnabled(false, "");
        if (!text.isBlank() && (history.isEmpty() || !history.get(history.size() - 1).equals(text))) {
            history.add(text);
        }
        historyPos = history.size();
        appendLower(text + "\n", 0, 0, true);
        waiter.accept(text);
    }

    private void recall(int delta) {
        if (history.isEmpty()) return;
        historyPos = Math.max(0, Math.min(history.size(), historyPos + delta));
        input.setText(historyPos == history.size() ? "" : history.get(historyPos));
        input.positionCaret(input.getText().length());
    }

    private void finishChar(IntConsumer waiter, int zscii) {
        charWaiter = null;
        setInputEnabled(false, "");
        waiter.accept(zscii);
    }

    private void onKeyPressed(KeyEvent e) {
        IntConsumer waiter = charWaiter;
        if (waiter == null) return;
        int z = specialKey(e.getCode());
        if (z != 0) {
            finishChar(waiter, z);
            e.consume();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        IntConsumer waiter = charWaiter;
        if (waiter == null || (e.isShortcutDown() && !e.isAltDown())) return;
        String s = e.getCharacter();
        if (s == null || s.isEmpty()) return;
        char c = s.charAt(0);
        if (c == '\r' || c == '\n') c = 13;
        else if (c == 127) c = 8;
        if ((c >= 32 && c != 127) || c == 13 || c == 8 || c == 27) {
            finishChar(waiter, c);
            e.consume();
        }
    }

    /** ZSCII codes for cursor and function keys (Standard 3.8.5); 0 for other keys. */
    private static int specialKey(KeyCode code) {
        switch (code) {
            case UP: return 129;
            case DOWN: return 130;
            case LEFT: return 131;
            case RIGHT: return 132;
            case F1: return 133;
            case F2: return 134;
            case F3: return 135;
            case F4: return 136;
            case F5: return 137;
            case F6: return 138;
            case F7: return 139;
            case F8: return 140;
            case F9: return 141;
            case F10: return 142;
            case F11: return 143;
            case F12: return 144;
            default: return 0;
        }
    }
}
