package storyscape.fx;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * A PDF tab (Game Manual, Hints): shows a PDF found beside the story file (which one is decided by
 * the {@code locate} function given to the constructor, e.g. zork1.pdf for zork1.z3, or hints.pdf)
 * as one continuous column of pages, each scaled to the tab's width times the zoom level, read
 * with the scroll bars. Zoom with the − / + buttons, Ctrl/Cmd + scroll or a trackpad pinch;
 * "Fit" goes back to fitting the tab's width. Pages are rendered with PDFBox on a background thread (which owns
 * the open document) only when they are on or near the screen, and dropped again when far away,
 * so long manuals don't fill memory. Prev / Next jump a page; "Open" hands the PDF to the
 * system's own viewer. Public methods are FX thread only.
 */
final class ManualView extends BorderPane {
    private static final double PAD = 10;                 // around and between pages
    private static final double MAX_RENDER_PX = 3200;     // widest bitmap ever rendered for a page
    static final double MIN_ZOOM = 0.5, MAX_ZOOM = 4.0, ZOOM_STEP = 1.25;
    private static final int KEEP_SCREENS = 3;            // pages further away than this are dropped

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "manual-renderer");
        t.setDaemon(true);
        return t;
    });

    /** One page: a white sheet sized to the tab width, holding its picture once rendered. */
    private static final class Slot extends StackPane {
        final int index;
        final ImageView view = new ImageView();
        double renderedPx;                  // width of the bitmap currently shown (0 = none)
        boolean inFlight;

        /** {@code aspect} is the page's height / width. */
        Slot(int index, double aspect, DoubleBinding width) {
            this.index = index;
            getStyleClass().add("zm-manual-page");
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.fitWidthProperty().bind(width);
            minWidthProperty().bind(width);
            prefWidthProperty().bind(width);
            maxWidthProperty().bind(width);
            DoubleBinding height = width.multiply(aspect);
            minHeightProperty().bind(height);
            prefHeightProperty().bind(height);
            maxHeightProperty().bind(height);
            getChildren().add(view);
        }
    }

    private final VBox pages = new VBox(PAD);
    private final ScrollPane scroll = new ScrollPane(pages);
    private final Label message = new Label();
    private final Button prev = barButton("‹ Prev", "Scroll to the previous page");
    private final Button next = barButton("Next ›", "Scroll to the next page");
    private final Button open = barButton("Open", "Open this PDF in your PDF viewer");
    private final Button zoomOut = barButton("−", "Zoom out (Ctrl/Cmd + scroll down)");
    private final Button zoomIn = barButton("+", "Zoom in (Ctrl/Cmd + scroll up)");
    private final Button fit = barButton("Fit", "Fit pages to the tab's width");
    private final Label zoomLabel = new Label();
    private final DoubleProperty zoom = new SimpleDoubleProperty(1.0);   // 1 = fit the tab's width
    private final String noun;                            // "game manual", "hints": used in messages
    private final Function<Path, Path> locate;            // story file -> PDF beside it, or null
    private final Function<Path, MissingFilePanel.Help> help;   // story file -> how to add the PDF
    private final MissingFilePanel missing = new MissingFilePanel();
    private final HBox bar;
    private final Label pageLabel = new Label();
    private final DoubleBinding pageWidth;
    private final List<Slot> slots = new ArrayList<>();

    // FX-thread state
    private Path file;
    private int currentPage;
    private boolean updateQueued;
    private volatile int generation;        // bumps on every new story, so stale work is dropped

    /** Pages the FX thread still wants rendered; the worker skips any that scrolled away meanwhile. */
    private final Set<Integer> wanted = ConcurrentHashMap.newKeySet();

    // worker-thread state
    private PDDocument doc;
    private PDFRenderer renderer;

    /**
     * @param noun     what the PDF is, for messages ("manual", "hints")
     * @param locate   finds the PDF for a story file, or returns null
     * @param help     what to tell the user when it isn't found
     */
    ManualView(String noun, Function<Path, Path> locate, Function<Path, MissingFilePanel.Help> help) {
        this.noun = noun;
        this.locate = locate;
        this.help = help;
        getStyleClass().add("zm-manual");
        message.getStyleClass().add("zm-placeholder");
        message.setWrapText(true);

        pages.getStyleClass().add("zm-manual-surface");
        pages.setPadding(new Insets(PAD));
        pages.setAlignment(Pos.TOP_CENTER);
        pages.setFillWidth(false);
        scroll.getStyleClass().add("zm-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);   // only when zoomed in
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);   // steady width: no re-fit when it appears
        scroll.setPannable(false);
        pageWidth = Bindings.createDoubleBinding(
                () -> Math.max(40, scroll.getViewportBounds().getWidth() - 2 * PAD) * zoom.get(),
                scroll.viewportBoundsProperty(), zoom);
        zoom.addListener((o, a, b) -> queueUpdate());

        // Ctrl/Cmd + wheel and trackpad pinch zoom
        scroll.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isShortcutDown() || e.getDeltaY() == 0) return;
            setZoom(zoom.get() * (e.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP));
            e.consume();
        });
        scroll.setOnZoom(e -> {
            setZoom(zoom.get() * e.getZoomFactor());
            e.consume();
        });

        // the visible pages change when scrolling, resizing, or once the column has been laid out
        scroll.vvalueProperty().addListener((o, a, b) -> queueUpdate());
        scroll.viewportBoundsProperty().addListener((o, a, b) -> queueUpdate());
        pages.heightProperty().addListener((o, a, b) -> queueUpdate());

        prev.setOnAction(e -> scrollToPage(currentPage - 1));
        next.setOnAction(e -> scrollToPage(currentPage + 1));
        open.setOnAction(e -> openExternally());
        zoomOut.setOnAction(e -> setZoom(zoom.get() / ZOOM_STEP));
        zoomIn.setOnAction(e -> setZoom(zoom.get() * ZOOM_STEP));
        fit.setOnAction(e -> setZoom(1.0));
        zoomLabel.getStyleClass().add("zm-zoom-level");
        zoomLabel.setMinWidth(48);
        zoomLabel.setAlignment(Pos.CENTER);
        zoomLabel.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(zoom.get() * 100) + "%", zoom));
        pageLabel.getStyleClass().add("zm-zoom-level");
        pageLabel.setMinWidth(70);
        pageLabel.setAlignment(Pos.CENTER);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar = new HBox(4, prev, pageLabel, next, spacer, zoomOut, zoomLabel, zoomIn, fit, open);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.getStyleClass().add("zm-zoom-bar");

        setTop(bar);
        setCenter(new StackPane(scroll, message, missing));
        setMinSize(0, 0);
        show(null);
    }

    private static Button barButton(String text, String tip) {
        Button b = new Button(text);
        b.getStyleClass().add("zm-zoom-button");
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        return b;
    }

    /** The Game Manual tab: the PDF beside the story with the same base name (zork1.pdf). */
    static ManualView gameManual() {
        return new ManualView("game manual",
                story -> CoverArtView.StoryFiles.sibling(story, "pdf"),
                story -> {
                    String base = CoverArtView.StoryFiles.baseName(story);
                    return new MissingFilePanel.Help(
                            "No game manual yet",
                            "This tab shows the story's manual (and other documents that came with "
                                    + "it, such as maps or feelies) when there is a PDF with the same "
                                    + "name in the story's folder.",
                            "Get the manual as a PDF file: download one, scan the printed booklet, "
                                    + "or use \"Print to PDF\" on a document you already have.",
                            base + ".pdf",
                            "The PDF's name must match the story file's name: " + story.getFileName()
                                    + " goes with " + base + ".pdf.");
                });
    }

    /** The Hints tab: hints.pdf in the story's folder. */
    static ManualView hints() {
        return new ManualView("hints",
                story -> CoverArtView.StoryFiles.named(story, "hints.pdf"),
                story -> new MissingFilePanel.Help(
                        "No hints yet",
                        "This tab shows hints for the story, such as InvisiClues or a walkthrough, "
                                + "when there is a file called hints.pdf in the story's folder.",
                        "Get the hints as a PDF file: download one, scan a hint booklet, or use "
                                + "\"Print to PDF\" on a document you already have.",
                        "hints.pdf",
                        "Every story in a folder shares its hints.pdf, so keep each story in its own "
                                + "folder if you have hints for more than one."));
    }

    /** Look for the PDF beside {@code story}; null clears the tab. */
    void show(Path story) {
        int gen = ++generation;
        file = null;
        currentPage = 0;
        wanted.clear();
        slots.clear();
        pages.getChildren().clear();
        scroll.setVvalue(0);
        scroll.setHvalue(0);
        zoom.set(1.0);
        worker.execute(this::closeDocument);
        missing.setVisible(false);
        setBarShown(story != null);

        if (story == null) {
            showMessage("Open a story to see its " + noun + ".");
            return;
        }
        Path pdf = locate.apply(story);
        if (pdf == null) {
            message.setVisible(false);
            updateBar();
            setBarShown(false);   // nothing to page through or zoom
            missing.show(help.apply(story), story, () -> show(story));
            missing.setVisible(true);
            return;
        }
        file = pdf;
        showMessage("Loading " + pdf.getFileName() + "…");
        worker.execute(() -> {
            double[] aspects;
            try {
                doc = Loader.loadPDF(pdf.toFile());
                renderer = new PDFRenderer(doc);
                aspects = new double[doc.getNumberOfPages()];
                for (int i = 0; i < aspects.length; i++) aspects[i] = aspect(doc.getPage(i));
            } catch (IOException | RuntimeException e) {
                closeDocument();
                Platform.runLater(() -> {
                    if (gen == generation) showMessage("Cannot read " + pdf.getFileName() + ":\n" + e.getMessage());
                });
                return;
            }
            Platform.runLater(() -> {
                if (gen != generation) return;
                if (aspects.length == 0) {
                    showMessage(pdf.getFileName() + " has no pages.");
                    return;
                }
                for (int i = 0; i < aspects.length; i++) slots.add(new Slot(i, aspects[i], pageWidth));
                pages.getChildren().setAll(slots);
                message.setVisible(false);
                updateBar();
                queueUpdate();
            });
        });
    }

    /** Height / width of a page as displayed (a page rotated a quarter turn swaps the two). */
    private static double aspect(PDPage page) {
        PDRectangle box = page.getCropBox();
        double w = box.getWidth(), h = box.getHeight();
        if (page.getRotation() % 180 != 0) {
            double t = w;
            w = h;
            h = t;
        }
        return w > 0 && h > 0 ? h / w : 1.294;   // fall back to US Letter
    }

    // ------------------------------------------------------------------ which pages to draw

    /** Coalesce bursts of scroll and resize events into one pass on the next pulse. */
    private void queueUpdate() {
        if (updateQueued) return;
        updateQueued = true;
        Platform.runLater(() -> {
            updateQueued = false;
            updateVisible();
        });
    }

    /**
     * Render the pages on screen (and a screenful either side), drop the ones far away, re-render
     * any that the tab has grown too wide for, and update the page counter.
     */
    private void updateVisible() {
        if (slots.isEmpty()) return;
        Bounds vp = scroll.getViewportBounds();
        double vpH = vp.getHeight();
        double top = scroll.getVvalue() * Math.max(0, pages.getHeight() - vpH);
        double bottom = top + vpH;
        double targetPx = Math.min(MAX_RENDER_PX, pageWidth.get() * outputScale());

        int middle = -1;
        for (Slot s : slots) {
            Bounds b = s.getBoundsInParent();
            if (middle < 0 && b.getMaxY() >= top + vpH / 3) middle = s.index;
            boolean near = b.getMaxY() >= top - vpH && b.getMinY() <= bottom + vpH;
            boolean far = b.getMaxY() < top - KEEP_SCREENS * vpH || b.getMinY() > bottom + KEEP_SCREENS * vpH;
            if (near) {
                if (s.renderedPx == 0 || targetPx > s.renderedPx * 1.2) request(s, targetPx);
            } else if (far) {
                wanted.remove(s.index);
                s.view.setImage(null);
                s.renderedPx = 0;
            }
        }
        currentPage = Math.max(0, middle);
        updateBar();
    }

    private double outputScale() {
        Window w = getScene() == null ? null : getScene().getWindow();
        return w == null ? 1 : Math.max(1, w.getOutputScaleX());
    }

    private void request(Slot s, double targetPx) {
        wanted.add(s.index);
        if (s.inFlight) return;
        s.inFlight = true;
        int gen = generation;
        int index = s.index;
        worker.execute(() -> {
            WritableImage img = null;
            if (gen == generation && wanted.contains(index) && renderer != null) {
                try {
                    float scale = (float) (targetPx / pageWidthPoints(index));
                    img = toFx(renderer.renderImage(index, scale));
                } catch (IOException | RuntimeException ignored) {
                    // leave the sheet blank
                }
            }
            WritableImage done = img;
            Platform.runLater(() -> {
                if (gen != generation) return;
                s.inFlight = false;
                if (done != null && wanted.contains(index)) {
                    s.view.setImage(done);
                    s.renderedPx = done.getWidth();
                }
                queueUpdate();              // the view may have moved on while this page was drawn
            });
        });
    }

    /** Worker thread only: the displayed width of a page in PDF points. */
    private double pageWidthPoints(int index) {
        PDPage page = doc.getPage(index);
        PDRectangle box = page.getCropBox();
        double w = page.getRotation() % 180 != 0 ? box.getHeight() : box.getWidth();
        return w > 0 ? w : 612;
    }

    private static WritableImage toFx(BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        int[] argb = bi.getRGB(0, 0, w, h, null, 0, w);
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return img;
    }

    // ------------------------------------------------------------------ zoom

    /** Change the zoom (clamped), keeping the same part of the document in view. */
    private void setZoom(double z) {
        z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        if (Math.abs(z - 1.0) < 0.02) z = 1.0;      // stepping back and forth lands exactly on Fit
        if (z == zoom.get()) return;
        double v = scroll.getVvalue(), h = scroll.getHvalue();
        zoom.set(z);
        // the column scales evenly, so the old scroll fractions point at the same place once laid out
        Platform.runLater(() -> {
            scroll.setVvalue(v);
            scroll.setHvalue(h);
        });
        updateBar();
    }

    // ------------------------------------------------------------------ bar and messages

    /** Scroll so page {@code index} starts at the top of the tab. */
    private void scrollToPage(int index) {
        if (index < 0 || index >= slots.size()) return;
        double range = pages.getHeight() - scroll.getViewportBounds().getHeight();
        if (range <= 0) return;
        double y = slots.get(index).getBoundsInParent().getMinY() - PAD;
        scroll.setVvalue(Math.max(0, Math.min(1, y / range)));
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisible(true);
        updateBar();
    }

    private void setBarShown(boolean shown) {
        bar.setVisible(shown);
        bar.setManaged(shown);
    }

    private void updateBar() {
        int count = slots.size();
        pageLabel.setText(count == 0 ? "" : (currentPage + 1) + " / " + count);
        prev.setDisable(count == 0 || currentPage <= 0);
        next.setDisable(count == 0 || currentPage >= count - 1);
        open.setDisable(file == null);
        zoomOut.setDisable(count == 0 || zoom.get() <= MIN_ZOOM + 1e-9);
        zoomIn.setDisable(count == 0 || zoom.get() >= MAX_ZOOM - 1e-9);
        fit.setDisable(count == 0 || zoom.get() == 1.0);
    }

    /** Hand the PDF to the system viewer (off the FX thread: AWT may take a moment to start). */
    private void openExternally() {
        Path f = file;
        if (f == null) return;
        Thread t = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(f.toFile());
                }
            } catch (IOException | RuntimeException ignored) {
                // no PDF viewer installed: the in-tab view still works
            }
        }, "open-manual");
        t.setDaemon(true);
        t.start();
    }

    /** Worker thread only. */
    private void closeDocument() {
        renderer = null;
        if (doc != null) {
            try {
                doc.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            doc = null;
        }
    }
}
