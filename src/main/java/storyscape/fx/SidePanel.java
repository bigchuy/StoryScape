package storyscape.fx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Callback;
import javafx.util.Duration;

/**
 * The panel beside the story: Cover Art, Game Manual, Hints, Notes (free text) and a Map tab. The
 * Map tab has one zoom control and one Auto Map toggle for all maps; a lone map fills the tab, and
 * two or more get their own tabs inside it. Rooms the player
 * visits go onto the active map only: the map tab selected most recently. Double-clicking a room
 * opens a new map tab that starts from just that room; the room becomes a circle on both maps, and
 * clicking either circle shows the other map. Map tab titles can be edited by
 * double-clicking them (or with Rename… on their context menu). Notes and all maps are kept in a
 * file beside the story ({@link NotesFile}), apart from saved games: they load when the story opens
 * and are written on every save, each turn, every half minute and on close. FX thread only.
 */
final class SidePanel extends TabPane {
    static final String NOTES_CHUNK = "JZnt";
    static final String MAP_CHUNK = "JZmp";
    static final String ROOM_NOTES_CHUNK = "JZrn";
    private static final String DEFAULT_TITLE = "Map";
    private static final double TAB_WIDTH = 120, TAB_HEIGHT = 30;

    private final TextArea notes = new TextArea();
    private final Tab notesTab = new Tab("Notes", notes);
    private final CoverArtView cover = new CoverArtView();
    private final Tab coverTab = new Tab("Cover Art", cover);
    private final ManualView manual = ManualView.gameManual();
    private final Tab manualTab = new Tab("Game Manual", manual);
    private final ManualView hints = ManualView.hints();
    private final Tab hintsTab = new Tab("Hints", hints);
    /** Each map's own tab (shown in {@link #mapTabs} only while there is more than one map). */
    private final Map<Tab, MapView> maps = new LinkedHashMap<>();
    /** The inner tab pane holding the maps when there are several. */
    private final TabPane mapTabs = new TabPane();
    /** The Map tab's content: the shared zoom / Auto Map bar over the lone map or {@link #mapTabs}. */
    private final BorderPane mapArea = new BorderPane();
    private final Tab mapTab = new Tab("Map", mapArea);
    /** Auto Map: when false, visiting rooms no longer adds them (or connections) to any map. */
    private final BooleanProperty autoMap = new SimpleBooleanProperty(true);
    /** The zoom shared by every map: changing it on one (bar buttons, Ctrl+scroll, pinch) zooms them all. */
    private final DoubleProperty mapZoom = new SimpleDoubleProperty(1.0);
    private boolean restoringMaps;           // while true, maps loading their saved zoom don't spread it
    /** Room notes by room object: shared by every map the room is on, and kept when rooms move. */
    private final Map<Integer, String> roomNotes = new LinkedHashMap<>();
    private MapView activeMap;
    private boolean dark;
    private int playerLocation = -1;
    private int nextMapId = 1;
    /** Where this story's notes and maps are kept (story.storyscape), or null with no story open. */
    private Path notesFile;
    /** The bytes last read from or written to {@link #notesFile}: unchanged notes aren't rewritten. */
    private byte[] lastWritten;
    /** True once notes and maps came from {@link #notesFile}, so old saves' copies are ignored. */
    private boolean notesLoaded;
    private static final int AUTOSAVE_SECONDS = 30;
    /** Saves a moment after each turn, so a run of quick moves is written once. */
    private final PauseTransition saveSoon = new PauseTransition(Duration.seconds(2));

    SidePanel() {
        getStyleClass().add("zm-side");
        notes.getStyleClass().add("zm-notes");
        notes.setWrapText(true);
        notes.setPromptText("Your notes — saved automatically beside the story file.");
        saveSoon.setOnFinished(e -> saveNotesAndMap());
        Timeline autosave = new Timeline(new KeyFrame(Duration.seconds(AUTOSAVE_SECONDS), e -> saveNotesAndMap()));
        autosave.setCycleCount(Animation.INDEFINITE);
        autosave.play();
        notesTab.setClosable(false);
        coverTab.setClosable(false);
        manualTab.setClosable(false);
        hintsTab.setClosable(false);
        mapTab.setClosable(false);
        getTabs().addAll(coverTab, manualTab, hintsTab, notesTab, mapTab);
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        setMinWidth(180);
        // every tab the same size; long map titles are shortened with "…" (hover for the full title)
        for (TabPane pane : new TabPane[] {this, mapTabs}) {
            pane.setTabMinWidth(TAB_WIDTH);
            pane.setTabMaxWidth(TAB_WIDTH);
            pane.setTabMinHeight(TAB_HEIGHT);
        }

        mapTabs.getStyleClass().addAll("zm-side", "zm-map-tabs");
        mapTabs.setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
        mapTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            MapView m = maps.get(newTab);
            if (m != null) activeMap = m;
        });
        mapArea.setTop(mapBar());
        mapArea.setMinSize(0, 0);
        mapZoom.addListener((o, a, z) -> applyZoom(z.doubleValue()));
        // a left click anywhere in the window closes an open map menu
        sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene != null) scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() == MouseButton.PRIMARY) for (MapView m : maps.values()) m.hideMenu();
            });
        });
        addMap(DEFAULT_TITLE);
    }

    void setDark(boolean on) {
        dark = on;
        for (MapView m : maps.values()) m.setDark(on);
    }

    /** File ▸ Preferences changed the map's fonts or colours. */
    void applyAppearance() {
        for (MapView m : maps.values()) m.applyAppearance();
    }

    /**
     * A story was opened: show the cover art and manual found beside it (story.jpg, story.pdf) and
     * load its notes and maps from story.storyscape, if there is one. Call {@link #clearAll} first.
     */
    void setStory(Path story) {
        cover.show(story);
        manual.show(story);
        hints.show(story);
        loadNotesAndMap(NotesFile.forStory(story));
    }

    /**
     * A different story is being opened: start with empty notes and a single empty map. Save the
     * current story's notes and maps ({@link #saveNotesAndMap}) before calling this.
     */
    void clearAll() {
        notesFile = null;                              // nothing is written until setStory names the new file
        lastWritten = null;
        notesLoaded = false;
        notes.clear();
        roomNotes.clear();
        resetMaps();
    }

    /** The same story was restarted: the notes and maps stay, but the player is nowhere yet. */
    void restarted() {
        playerLocation = -1;
    }

    // ------------------------------------------------------------------ notes-and-map file

    private void loadNotesAndMap(Path file) {
        notesFile = file;
        try {
            Map<String, byte[]> chunks = NotesFile.read(file);
            if (chunks != null) {
                restoreFrom(chunks);
                notesLoaded = true;
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: keep a copy so the next autosave doesn't destroy it, and start afresh
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bad"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // nothing more can be done
            }
        }
        lastWritten = NotesFile.encode(saveChunks());  // what's on screen now matches the file
    }

    /**
     * Writes the notes and maps to the story's .storyscape file if they changed since the last
     * write. Called after a successful save, every turn, every {@link #AUTOSAVE_SECONDS} seconds
     * and when the story or the window is closed. Returns false if the file couldn't be written.
     */
    boolean saveNotesAndMap() {
        if (notesFile == null) return true;
        byte[] data = NotesFile.encode(saveChunks());
        if (Arrays.equals(data, lastWritten)) return true;
        try {
            NotesFile.write(notesFile, data);
            lastWritten = data;
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The story file's notes-and-map file, or null before a story is opened. */
    Path notesFile() {
        return notesFile;
    }

    /** Back to a single empty map at 100% (the notes are kept). */
    void resetMaps() {
        removeAllMaps();
        playerLocation = -1;
        mapZoom.set(1.0);
        addMap(DEFAULT_TITLE);
    }

    private void removeAllMaps() {
        mapTabs.getTabs().clear();
        mapArea.setCenter(null);
        maps.clear();
        activeMap = null;
        nextMapId = 1;
    }

    /**
     * The player is now in {@code object}: add it to the active map (unless Auto Map is off), first
     * switching to the map that holds the room they came from if the active one doesn't (see
     * {@link #followPlayer}); other maps, and every map while Auto Map is off, just highlight the
     * room if they already have it.
     */
    void locationChanged(int object, String name, String command, boolean travelled) {
        int from = playerLocation;
        playerLocation = object;
        if (autoMap.get() && travelled) followPlayer(from, object);
        for (MapView m : maps.values()) {
            if (m == activeMap && autoMap.get()) m.enter(object, name, command, travelled);
            else m.locate(object);
        }
        saveSoon.playFromStart();                      // autosave the notes and maps shortly
    }

    /**
     * Before a move is mapped: a room that isn't on the selected map yet is only added there if the
     * room the player came from is on it too. Otherwise the first map that has that room is
     * selected (its inner tab, if there are several) and the new room goes onto that map instead,
     * joined to the room it was reached from.
     */
    private void followPlayer(int from, int to) {
        if (activeMap == null || from < 0 || activeMap.contains(to) || activeMap.contains(from)) return;
        for (Map.Entry<Tab, MapView> e : maps.entrySet()) {
            if (e.getValue().contains(from)) {
                activeMap = e.getValue();
                if (mapTabs.getTabs().contains(e.getKey())) mapTabs.getSelectionModel().select(e.getKey());
                return;
            }
        }
        // no map has the room the player came from: the new room goes onto the selected map
    }

    // ------------------------------------------------------------------ map tabs

    private Tab addMap(String title) {
        MapView map = new MapView();
        map.setId(nextMapId++);
        map.setDark(dark);
        map.applyAppearance();
        map.zoomTo(mapZoom.get());
        map.zoomProperty().addListener((o, a, z) -> {
            if (!restoringMaps) mapZoom.set(z.doubleValue());   // zoomed on this map: zoom them all
        });
        Tab tab = new Tab(null, map);
        setTitle(tab, title);
        map.setOnRoomDoubleClick((obj, name) -> openMapFrom(map, obj, name));
        map.setOnPortalClick(this::showMap);
        map.setOnMoveRequest(objects -> moveRooms(map, objects));
        map.setMoveAvailable(() -> maps.size() > 1);
        map.setHasNote(roomNotes::containsKey);
        map.setOnNoteRequest(this::editRoomNote);
        map.setManualEditing(() -> !autoMap.get());   // Add Room… only while Auto Map is off
        map.setOnAddRequest(req -> addRoomByHand(map, req));
        map.setOnLoopRequest((obj, name) -> addLoop(map, obj, name));
        map.setOnDeleteRequest(objects -> deleteRooms(map, objects));
        map.setOnLinkEditRequest(edit -> editConnection(map, edit));
        map.setOnConnectRequest(edit -> editConnection(map, edit, true));
        map.setOnRoomEditRequest(edit -> editRoom(map, edit));
        tab.setOnCloseRequest(e -> {
            if (maps.size() <= 1 || !confirmClose(tab)) e.consume();
        });
        tab.setOnClosed(e -> discard(tab));

        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(e -> editTitle(tab));
        tab.setContextMenu(new ContextMenu(rename));

        maps.put(tab, map);
        if (activeMap == null) activeMap = map;
        updateMapLayout();
        return tab;
    }

    /**
     * One map fills the Map tab by itself; two or more each get a tab in {@link #mapTabs}. Called
     * whenever maps are added or removed. The active map stays active (and selected).
     */
    private void updateMapLayout() {
        MapView keep = activeMap;
        if (maps.size() <= 1) {
            mapTabs.getTabs().clear();
            for (Tab t : maps.keySet()) t.setContent(null);   // free the map from its tab first
            mapArea.setCenter(maps.isEmpty() ? null : maps.values().iterator().next());
        } else {
            if (mapArea.getCenter() != mapTabs) mapArea.setCenter(mapTabs);   // frees the lone map
            for (Map.Entry<Tab, MapView> e : maps.entrySet()) {
                if (e.getKey().getContent() != e.getValue()) e.getKey().setContent(e.getValue());
                if (!mapTabs.getTabs().contains(e.getKey())) mapTabs.getTabs().add(e.getKey());
            }
            mapTabs.getTabs().retainAll(maps.keySet());
        }
        activeMap = keep;
        Tab t = tabOf(keep);
        if (t != null && mapTabs.getTabs().contains(t)) mapTabs.getSelectionModel().select(t);
        for (Tab tab : maps.keySet()) tab.setClosable(maps.size() > 1);   // the last map can't be closed
    }

    private Tab tabOf(MapView map) {
        for (Map.Entry<Tab, MapView> e : maps.entrySet()) {
            if (e.getValue() == map) return e.getKey();
        }
        return null;
    }

    /** Bring a map into view: the Map tab, and its own tab when there are several. */
    private void selectMap(Tab tab) {
        getSelectionModel().select(mapTab);
        if (mapTabs.getTabs().contains(tab)) mapTabs.getSelectionModel().select(tab);
        else activeMap = maps.get(tab);
    }

    /** Every map follows the shared zoom (each around the middle of its own view). */
    private void applyZoom(double z) {
        for (MapView m : maps.values()) m.zoomTo(z);
    }

    /**
     * The bar at the top of the Map tab, shared by every map: zoom out, zoom level, zoom in and
     * reset on the left, and the Auto Map toggle on the right.
     */
    private HBox mapBar() {
        Button out = zoomButton("−", "Zoom out all maps (Ctrl/Cmd + scroll down)",
                () -> mapZoom.set(clampZoom(mapZoom.get() / MapView.ZOOM_STEP)));
        Button in = zoomButton("+", "Zoom in all maps (Ctrl/Cmd + scroll up)",
                () -> mapZoom.set(clampZoom(mapZoom.get() * MapView.ZOOM_STEP)));
        Button reset = zoomButton("Reset", "Reset zoom to 100%", () -> mapZoom.set(1.0));
        Label level = new Label();
        level.getStyleClass().add("zm-zoom-level");
        level.setMinWidth(44);
        level.setAlignment(Pos.CENTER);
        level.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(mapZoom.get() * 100) + "%", mapZoom));
        out.disableProperty().bind(mapZoom.lessThanOrEqualTo(MapView.MIN_ZOOM + 1e-9));
        in.disableProperty().bind(mapZoom.greaterThanOrEqualTo(MapView.MAX_ZOOM - 1e-9));
        reset.disableProperty().bind(mapZoom.isEqualTo(1.0, 1e-9));

        CheckBox auto = new CheckBox("Auto Map");
        auto.getStyleClass().add("zm-auto-map");
        auto.setTooltip(new Tooltip("Add rooms to the selected map as you visit them"));
        auto.setFocusTraversable(false);
        auto.selectedProperty().bindBidirectional(autoMap);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(4, out, level, in, reset, spacer, auto);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.getStyleClass().add("zm-zoom-bar");
        return bar;
    }

    /** The same limits and snap-to-100% as zooming a map directly. */
    private static double clampZoom(double z) {
        z = Math.max(MapView.MIN_ZOOM, Math.min(MapView.MAX_ZOOM, z));
        return Math.abs(z - 1) < 0.02 ? 1 : z;
    }

    private static Button zoomButton(String text, String tip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("zm-zoom-button");
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    /**
     * A room was double-clicked: open a new map tab, named after it, holding only that room. The
     * room on both maps becomes a circle linked to the other map.
     */
    private void openMapFrom(MapView source, int object, String name) {
        Tab tab = addMap(name);
        MapView created = maps.get(tab);
        created.seed(object, name, object == playerLocation, source.id());
        created.setRoomColours(List.of(object), source.roomText(object), source.roomOutline(object));
        source.setPortal(object, created.id());
        selectMap(tab);
    }

    /**
     * Move was chosen on rooms in {@code source}: ask which map to move them to, then move them
     * there. Every line touching them goes too: a line to a room left behind ends at a copy of
     * that room on the target map, and the two become circles linked to each other's map.
     */
    private void moveRooms(MapView source, List<Integer> objects) {
        List<Tab> targets = new ArrayList<>();
        for (Map.Entry<Tab, MapView> e : maps.entrySet()) {
            if (e.getValue() != source) targets.add(e.getKey());
        }
        if (targets.isEmpty() || objects.isEmpty()) return;

        ComboBox<Tab> combo = new ComboBox<>(FXCollections.observableArrayList(targets));
        Callback<ListView<Tab>, ListCell<Tab>> cells = lv -> new ListCell<>() {
            @Override protected void updateItem(Tab tab, boolean empty) {
                super.updateItem(tab, empty);
                setText(empty || tab == null ? null : title(tab));
            }
        };
        combo.setCellFactory(cells);
        combo.setButtonCell(cells.call(null));
        combo.getSelectionModel().selectFirst();
        combo.setMaxWidth(Double.MAX_VALUE);

        Label prompt = new Label("Map:");
        HBox row = new HBox(8, prompt, combo);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(combo, Priority.ALWAYS);
        row.setPadding(new Insets(10, 0, 0, 0));
        row.setPrefWidth(320);

        Dialog<Tab> dialog = Styles.dialog(new Dialog<>());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Move");
        dialog.setHeaderText(objects.size() == 1
                ? "Move this room to another map:" : "Move these " + objects.size() + " rooms to another map:");
        dialog.getDialogPane().setContent(row);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ButtonType.OK ? combo.getValue() : null);
        Platform.runLater(combo::requestFocus);

        dialog.showAndWait().ifPresent(tab -> {
            MapView target = maps.get(tab);
            if (target == null) return;
            // every line goes along; rooms left behind at a line's far end become linked circles
            for (int end : target.putRooms(source.takeRooms(objects), source.id())) {
                source.linkIfFree(end, target.id());
            }
            validatePortals();                    // circles whose partner room has gone revert to rectangles
            source.locate(playerLocation);
            target.locate(playerLocation);
        });
    }

    /** Directions offered when linking a hand-made room, with their opposites. */
    private static final String[][] DIRECTIONS = {
        {"n", "s"}, {"ne", "sw"}, {"e", "w"}, {"se", "nw"}, {"s", "n"}, {"sw", "ne"}, {"w", "e"},
        {"nw", "se"}, {"up", "down"}, {"down", "up"}, {"in", "out"}, {"out", "in"},
    };
    private static final String NO_DIRECTION = "(none)";

    /**
     * Add Room… / Add Room Linked to … (empty-space menu while Auto Map is off): ask for the
     * room's name and, when linking, the directions each way, then add it where the user clicked.
     * Hand-made rooms get negative object numbers, so they never clash with the game's rooms.
     */
    private void addRoomByHand(MapView map, MapView.AddRequest req) {
        TextField name = new TextField();
        name.setPromptText("Room name");
        name.setPrefColumnCount(24);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10, 0, 0, 0));
        grid.addRow(0, new Label("Name:"), name);

        ComboBox<String> out = directionCombo(), back = directionCombo();
        if (req.linkTo() != null) {
            grid.addRow(1, new Label("Direction from “" + req.linkToName() + "”:"), out);
            grid.addRow(2, new Label("Direction back:"), back);
            boolean[] backTouched = {false};
            back.setOnAction(e -> backTouched[0] = true);
            out.valueProperty().addListener((o, a, dir) -> {
                if (backTouched[0]) return;
                String opposite = NO_DIRECTION;
                for (String[] d : DIRECTIONS) if (d[0].equals(dir)) opposite = d[1];
                back.setOnAction(null);               // filling it in isn't the user's choice
                back.setValue(opposite);
                back.setOnAction(e -> backTouched[0] = true);
            });
        }

        Dialog<ButtonType> dialog = Styles.dialog(new Dialog<>());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Add Room");
        dialog.setHeaderText(req.linkTo() == null
                ? "Add a room to the map:"
                : "Add a room linked to “" + req.linkToName() + "”:");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(Bindings.createBooleanBinding(() -> name.getText().isBlank(), name.textProperty()));
        Platform.runLater(name::requestFocus);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        String title = name.getText().replace('\n', ' ').strip();
        if (title.isEmpty()) return;
        map.addRoom(nextHandMadeObject(), title, req.x(), req.y(), req.linkTo(),
                direction(out), direction(back));
    }

    /**
     * Add Loop… on a room's menu (Auto Map off): a connection from the room back to itself, such
     * as a passage that leads round in a circle. Asks for its one label: a direction from the list
     * or any word typed in; left blank, the loop has no label.
     */
    private void addLoop(MapView map, int object, String name) {
        ComboBox<String> dir = new ComboBox<>();
        for (String[] d : DIRECTIONS) dir.getItems().add(d[0]);
        dir.setEditable(true);
        dir.setVisibleRowCount(12);
        dir.setPromptText("e.g. n, up, wait");

        HBox row = new HBox(10, new Label("Direction:"), dir);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 0, 0, 0));

        Dialog<ButtonType> dialog = Styles.dialog(new Dialog<>());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Add Loop");
        dialog.setHeaderText("Add a connection from “" + name + "” back to itself:");
        dialog.getDialogPane().setContent(row);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Platform.runLater(dir::requestFocus);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        String text = dir.getEditor().getText();      // what is typed, even if not picked from the list
        text = text == null ? "" : text.replace('\n', ' ').strip();
        map.addLoop(object, text.isEmpty() ? null : text);
    }

    /**
     * Edit Room… on a room's menu: pick the room's text and outline colours, or tick "Usual" to
     * go back to the map's own colours. With several rooms selected, all of them change.
     */
    private void editRoom(MapView map, MapView.RoomEdit edit) {
        Color[] usual = map.usualRoomColours();
        ColorPicker text = new ColorPicker(edit.text() != null ? edit.text() : usual[0]);
        ColorPicker outline = new ColorPicker(edit.outline() != null ? edit.outline() : usual[1]);
        CheckBox textUsual = new CheckBox("Usual");
        CheckBox outlineUsual = new CheckBox("Usual");
        textUsual.setSelected(edit.text() == null);
        outlineUsual.setSelected(edit.outline() == null);
        // picking a colour unticks "Usual"; ticking it shows the usual colour again
        text.setOnAction(e -> textUsual.setSelected(false));
        outline.setOnAction(e -> outlineUsual.setSelected(false));
        textUsual.setOnAction(e -> { if (textUsual.isSelected()) text.setValue(usual[0]); });
        outlineUsual.setOnAction(e -> { if (outlineUsual.isSelected()) outline.setValue(usual[1]); });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));
        grid.addRow(0, new Label("Text colour:"), text, textUsual);
        grid.addRow(1, new Label("Outline colour:"), outline, outlineUsual);

        Dialog<ButtonType> dialog = Styles.dialog(new Dialog<>());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(edit.objects().size() == 1 ? "Edit Room" : "Edit Rooms");
        dialog.setHeaderText(edit.name() != null ? "Colours for “" + edit.name() + "”:"
                : "Colours for the " + edit.objects().size() + " selected rooms:");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        map.setRoomColours(edit.objects(),
                textUsual.isSelected() ? null : text.getValue(),
                outlineUsual.isSelected() ? null : outline.getValue());
    }

    /**
     * Edit Connection / Edit Loop on a connection's menu (Auto Map off): change the direction
     * label each way (just the one for a loop). Each is a direction from the list or any word
     * typed in; left blank, that end has no label.
     */
    private void editConnection(MapView map, MapView.LinkEdit edit) {
        editConnection(map, edit, false);
    }

    /**
     * The connection dialog. With {@code create} (Connect Rooms… with two rooms selected) OK
     * draws a new line between the rooms; picking the first direction fills in its opposite for
     * the way back until that is chosen by hand.
     */
    private void editConnection(MapView map, MapView.LinkEdit edit, boolean create) {
        ComboBox<String> there = labelCombo(edit.dirA()), back = labelCombo(edit.dirB());
        if (create) {
            boolean[] backTouched = {false};
            back.getEditor().textProperty().addListener((o, a, t) -> {
                if (back.getEditor().isFocused()) backTouched[0] = true;
            });
            back.setOnAction(e -> {
                if (back.isFocused() || back.isShowing()) backTouched[0] = true;
            });
            there.valueProperty().addListener((o, a, dir) -> {
                if (backTouched[0]) return;
                String opposite = "";
                for (String[] d : DIRECTIONS) if (d[0].equals(dir)) opposite = d[1];
                back.getEditor().setText(opposite);
            });
        }
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10, 0, 0, 0));
        if (edit.loop()) {
            grid.addRow(0, new Label("Direction:"), there);
        } else {
            grid.addRow(0, new Label("From “" + edit.aName() + "” to “" + edit.bName() + "”:"), there);
            grid.addRow(1, new Label("From “" + edit.bName() + "” to “" + edit.aName() + "”:"), back);
        }

        Dialog<ButtonType> dialog = Styles.dialog(new Dialog<>());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(create ? "Connect Rooms" : edit.loop() ? "Edit Loop" : "Edit Connection");
        dialog.setHeaderText(create
                ? "Connect “" + edit.aName() + "” and “" + edit.bName() + "”:"
                : edit.loop()
                ? "The loop from “" + edit.aName() + "” back to itself:"
                : "The connection between “" + edit.aName() + "” and “" + edit.bName() + "”:");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Platform.runLater(there::requestFocus);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        if (create) map.connect(edit.a(), edit.b(), label(there), label(back));
        else map.setLinkLabels(edit.a(), edit.b(), label(there), edit.loop() ? null : label(back));
    }

    /** An editable list of directions starting at {@code value} (blank for none). */
    private static ComboBox<String> labelCombo(String value) {
        ComboBox<String> c = new ComboBox<>();
        for (String[] d : DIRECTIONS) c.getItems().add(d[0]);
        c.setEditable(true);
        c.setVisibleRowCount(12);
        c.setPromptText("none");
        c.setValue(value);
        c.getEditor().setText(value == null ? "" : value);
        return c;
    }

    /** What is typed in an editable combo, even if not picked from its list; blank means none. */
    private static String label(ComboBox<String> c) {
        String text = c.getEditor().getText();
        text = text == null ? "" : text.replace('\n', ' ').strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Delete Room on a room's menu (Auto Map off): after confirming, remove the rooms from this
     * map with their connections. Circles linked to them revert to rectangles, and room notes are
     * dropped once no map has the room any more.
     */
    private void deleteRooms(MapView map, List<Integer> objects) {
        if (objects.isEmpty()) return;
        Alert alert = Styles.dialog(new Alert(Alert.AlertType.CONFIRMATION,
                "Their connections are deleted too.", ButtonType.OK, ButtonType.CANCEL));
        if (getScene() != null) alert.initOwner(getScene().getWindow());
        alert.setTitle("Delete");
        alert.setHeaderText(objects.size() == 1
                ? "Delete this room from the map?" : "Delete these " + objects.size() + " rooms from the map?");
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        map.deleteRooms(objects);
        validatePortals();
        for (int obj : objects) {
            boolean elsewhere = false;
            for (MapView m : maps.values()) elsewhere |= m.contains(obj);
            if (!elsewhere) roomNotes.remove(obj);
        }
        for (MapView m : maps.values()) m.refreshNotes();
    }

    private static ComboBox<String> directionCombo() {
        ComboBox<String> c = new ComboBox<>();
        c.getItems().add(NO_DIRECTION);
        for (String[] d : DIRECTIONS) c.getItems().add(d[0]);
        c.setValue(NO_DIRECTION);
        c.setVisibleRowCount(13);
        return c;
    }

    private static String direction(ComboBox<String> c) {
        String v = c.getValue();
        return v == null || v.equals(NO_DIRECTION) ? null : v;
    }

    /** A new negative object number, unused on every map and by room notes (-1 means "nowhere"). */
    private int nextHandMadeObject() {
        int min = -1;
        for (MapView m : maps.values()) min = Math.min(min, m.minObject());
        for (int obj : roomNotes.keySet()) min = Math.min(min, obj);
        return min - 1;
    }

    /**
     * Show the note dialog for a room. OK saves the text; clearing it removes the note (and the
     * icon). The note belongs to the room, so it shows on every map that has the room.
     */
    private void editRoomNote(int object, String name) {
        TextArea area = new TextArea(roomNotes.getOrDefault(object, ""));
        area.setWrapText(true);
        area.setPrefSize(380, 200);
        area.setPromptText("Notes about this room — saved along with the game.");

        Dialog<String> dialog = Styles.dialog(new Dialog<>());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Room Notes");
        dialog.setHeaderText("Notes for “" + name + "”");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ButtonType.OK ? area.getText() : null);
        Platform.runLater(() -> {
            area.requestFocus();
            area.end();
        });

        dialog.showAndWait().ifPresent(text -> {
            if (text.isBlank()) roomNotes.remove(object);
            else roomNotes.put(object, text.stripTrailing());
            for (MapView m : maps.values()) m.refreshNotes();
        });
    }

    /**
     * A circle is only kept while its linked map still exists, is a different map, and still holds
     * the same room; otherwise the room goes back to being a rectangle.
     */
    private void validatePortals() {
        Map<Integer, MapView> byId = new LinkedHashMap<>();
        for (MapView m : maps.values()) byId.put(m.id(), m);
        for (MapView m : maps.values()) {
            m.retainPortals((object, mapId) -> {
                MapView linked = byId.get(mapId);
                return linked != null && linked != m && linked.contains(object);
            });
        }
    }

    /** A circle room was clicked: show the map it is linked to. */
    private void showMap(int mapId) {
        for (Map.Entry<Tab, MapView> e : maps.entrySet()) {
            if (e.getValue().id() == mapId) {
                selectMap(e.getKey());
                return;
            }
        }
    }

    /**
     * Forget a closed map tab (safe to call twice): rooms linked to it on other maps go back to
     * rectangles, and the first remaining map takes over if it was active.
     */
    private void discard(Tab tab) {
        MapView map = maps.remove(tab);
        if (map == null) return;
        validatePortals();
        if (activeMap == map) {
            Tab shown = mapTabs.getSelectionModel().getSelectedItem();
            activeMap = maps.containsKey(shown) ? maps.get(shown)
                    : maps.isEmpty() ? null : maps.values().iterator().next();
        }
        updateMapLayout();                             // back to a lone map when only one is left
    }

    private boolean confirmClose(Tab tab) {
        Alert alert = Styles.dialog(new Alert(Alert.AlertType.CONFIRMATION,
                "The rooms on this map will be discarded.", ButtonType.OK, ButtonType.CANCEL));
        if (getScene() != null) alert.initOwner(getScene().getWindow());
        alert.setTitle("Close Map");
        alert.setHeaderText("Close the map “" + title(tab) + "”?");
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    // ------------------------------------------------------------------ editable titles

    private static String title(Tab tab) {
        return tab.getGraphic() instanceof Label l ? l.getText() : String.valueOf(tab.getText());
    }

    /** Titles are shown as a Label graphic so a double-click can swap in a text field. */
    private void setTitle(Tab tab, String title) {
        Label label = new Label(title);
        label.getStyleClass().add("zm-tab-title");
        label.setMaxWidth(TAB_WIDTH - 34);              // room for the close button; longer titles get "…"
        label.setTooltip(new Tooltip(title));
        label.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) editTitle(tab);
        });
        tab.setGraphic(label);
    }

    private void editTitle(Tab tab) {
        String old = title(tab);
        TextField field = new TextField(old);
        field.getStyleClass().add("zm-tab-edit");
        field.setPrefColumnCount(Math.max(6, Math.min(24, old.length() + 2)));
        boolean[] done = {false};
        Runnable commit = () -> {
            if (done[0]) return;
            done[0] = true;
            String t = field.getText().replace('\n', ' ').strip();
            setTitle(tab, t.isEmpty() ? old : t);
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, was, is) -> {
            if (!is) commit.run();
        });
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                done[0] = true;
                setTitle(tab, old);
                e.consume();
            }
        });
        tab.setGraphic(field);
        mapTabs.getSelectionModel().select(tab);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Notes are UTF-8 text. Maps are stored one after another, each introduced by a "T title" line
     * and followed by {@link MapView#serialize()}'s lines; "A n" gives the active map's index.
     */
    Map<String, byte[]> saveChunks() {
        Map<String, byte[]> chunks = new LinkedHashMap<>();
        chunks.put(NOTES_CHUNK, notes.getText().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        int i = 0, active = 0;
        for (Map.Entry<Tab, MapView> e : maps.entrySet()) {
            if (e.getValue() == activeMap) active = i;
            sb.append("T ").append(title(e.getKey()).replace('\n', ' ')).append('\n');
            sb.append(new String(e.getValue().serialize(), StandardCharsets.UTF_8));
            i++;
        }
        sb.append("A ").append(active).append('\n');
        chunks.put(MAP_CHUNK, sb.toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder rn = new StringBuilder();      // one "obj text" line per room, newlines escaped
        for (Map.Entry<Integer, String> e : roomNotes.entrySet()) {
            rn.append(e.getKey()).append(' ').append(escape(e.getValue())).append('\n');
        }
        chunks.put(ROOM_NOTES_CHUNK, rn.toString().getBytes(StandardCharsets.UTF_8));
        return chunks;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void restoreRoomNotes(byte[] data) {
        roomNotes.clear();
        for (String line : new String(data, StandardCharsets.UTF_8).lines().toList()) {
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            try {
                roomNotes.put(Integer.parseInt(line.substring(0, sp)), unescape(line.substring(sp + 1)));
            } catch (NumberFormatException ignored) {
                // skip a damaged line
            }
        }
        for (MapView m : maps.values()) m.refreshNotes();
    }

    /**
     * A game was restored. Notes and maps now live in the story's .storyscape file and stay as they
     * are; only a save from an older StoryScape, restored when that file doesn't exist yet, supplies
     * them (they are then written to the file).
     */
    void restoreChunks(Map<String, byte[]> chunks) {
        boolean legacy = chunks.containsKey(NOTES_CHUNK) || chunks.containsKey(MAP_CHUNK)
                || chunks.containsKey(ROOM_NOTES_CHUNK);
        if (legacy && !notesLoaded) {
            restoreFrom(chunks);
            notesLoaded = true;
            saveNotesAndMap();
        }
        showCoverArt();                                // a restore always ends on the Cover Art tab
    }

    /** Bring the Cover Art tab to the front (after a story loads or a game is restored). */
    void showCoverArt() {
        getSelectionModel().select(coverTab);
    }

    private void restoreFrom(Map<String, byte[]> chunks) {
        byte[] n = chunks.get(NOTES_CHUNK);
        if (n != null) notes.setText(new String(n, StandardCharsets.UTF_8));
        byte[] rn = chunks.get(ROOM_NOTES_CHUNK);
        if (rn != null) restoreRoomNotes(rn);
        byte[] m = chunks.get(MAP_CHUNK);
        if (m == null) return;

        List<String> titles = new ArrayList<>();
        List<StringBuilder> bodies = new ArrayList<>();
        int active = 0;
        for (String line : new String(m, StandardCharsets.UTF_8).lines().toList()) {
            if (line.startsWith("T ")) {
                titles.add(line.substring(2));
                bodies.add(new StringBuilder());
            } else if (line.startsWith("A ")) {
                try {
                    active = Integer.parseInt(line.substring(2).strip());
                } catch (NumberFormatException ignored) {
                    // keep the first map active
                }
            } else {
                if (bodies.isEmpty()) {               // older saves held one untitled map
                    titles.add(DEFAULT_TITLE);
                    bodies.add(new StringBuilder());
                }
                bodies.get(bodies.size() - 1).append(line).append('\n');
            }
        }
        if (bodies.isEmpty()) {
            titles.add(DEFAULT_TITLE);
            bodies.add(new StringBuilder());
        }

        removeAllMaps();
        List<Tab> tabs = new ArrayList<>();
        restoringMaps = true;                          // each map loads its own saved zoom: don't spread it yet
        try {
            for (int i = 0; i < bodies.size(); i++) {
                Tab tab = addMap(titles.get(i));
                maps.get(tab).deserialize(bodies.get(i).toString().getBytes(StandardCharsets.UTF_8));
                tabs.add(tab);
            }
        } finally {
            restoringMaps = false;
        }
        for (MapView mv : maps.values()) nextMapId = Math.max(nextMapId, mv.id() + 1);   // ids came from the save
        Tab act = tabs.get(Math.max(0, Math.min(tabs.size() - 1, active)));
        activeMap = maps.get(act);                     // rooms keep going onto the map that was active
        if (mapTabs.getTabs().contains(act)) mapTabs.getSelectionModel().select(act);

        // the active map's saved zoom becomes the shared zoom (older saves could differ per map)
        double z = activeMap.zoomProperty().get();
        mapZoom.set(z);
        applyZoom(z);
    }
}
