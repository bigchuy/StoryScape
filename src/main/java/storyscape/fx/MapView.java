package storyscape.fx;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * An automatically drawn map. Every room the player visits becomes a box, joined by a line to the
 * room they came from; the line carries the direction taken, drawn next to the room it leads out
 * of. New rooms are placed by compass (north up, east right) when that spot is free. Rooms, lines
 * (by dragging a bend into them) and direction labels can all be selected and dragged. Dragging on
 * empty space draws a selection box; every selected room moves together. The right or middle
 * button pans. A
 * double-click on a line or label puts it back where it started, and a double-click on a room is
 * passed to {@link #setOnRoomDoubleClick} (the side panel opens a new map from it). Rooms linked to
 * another map that way are drawn as circles; clicking one is passed to {@link #setOnPortalClick}.
 * FX thread only.
 */
final class MapView extends ScrollPane {
    private static final double STEP_X = 170, STEP_Y = 100;
    private static final double BOX_W = 130, BOX_H = 42;
    private static final double CIRCLE_D = 84;             // rooms linked to another map are circles
    private static final double GAP_X = 20, GAP_Y = 30;   // minimum space kept around a new room
    private static final double MARGIN = 60;               // space around the map's contents
    private static final double LABEL_GAP = 16;          // distance from a room's edge to its label
    // room name and label fonts come from File ▸ Preferences: Appearance.mapFont() and friends

    /**
     * A room: its centre in map coordinates. {@code portal} is the id of another map this room is
     * linked to (0 for none); such rooms are drawn as circles and clicking one opens that map.
     */
    private static final class Room {
        final int object;
        String name;
        double x, y;
        int portal;
        /** Colours picked with Edit Room… (null: the map's usual colour). */
        Color text, outline;
        Room(int object, String name, double x, double y) {
            this.object = object;
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * The line between rooms {@code a} and {@code b} (a &lt; b). {@code dirA} is the direction that
     * leads from a to b, {@code dirB} from b to a (either may be null). The line bends through the
     * midpoint shifted by (bendX, bendY); each label sits at its default spot shifted by its offset.
     */
    private static final class Link {
        final int a, b;
        String dirA, dirB;
        double bendX, bendY;
        double offAx, offAy, offBx, offBy;
        Link(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    /** The label for one end of a link: {@code atA} means the label of the direction leaving room a. */
    private record LabelRef(Link link, boolean atA) {}

    private final Map<Integer, Room> rooms = new LinkedHashMap<>();
    private final Map<Long, Link> links = new LinkedHashMap<>();
    private final Canvas canvas = new Canvas();
    private final StackPane holder = new StackPane(canvas);
    private int current = -1;
    private boolean dark;

    private BiConsumer<Integer, String> onRoomDoubleClick = (obj, name) -> { };
    private IntConsumer onPortalClick = mapId -> { };
    private int id;                        // identifies this map to portals on other maps
    private Consumer<List<Integer>> onMoveRequest = objects -> { };
    private BooleanSupplier moveAvailable = () -> false;
    private ContextMenu roomMenu;
    private BiConsumer<Integer, String> onNoteRequest = (obj, name) -> { };
    private IntPredicate hasNote = obj -> false;
    private static final double NOTE_SIZE = 13;          // the note icon in a room's lower right

    static final double MIN_ZOOM = 0.25, MAX_ZOOM = 3.0, ZOOM_STEP = 1.2;
    private final DoubleProperty zoom = new SimpleDoubleProperty(1.0);

    private double ox, oy;                // map coordinates -> unscaled canvas coordinates
    private Object selected;              // the item under the last press: a Room, Link or LabelRef
    private final Set<Room> selectedRooms = new LinkedHashSet<>();
    private boolean dragging;
    private boolean banding;              // dragging out a selection box
    private Set<Room> bandBase = Set.of();
    private double bandX0, bandY0, bandX1, bandY1;   // selection box corners, map coordinates
    private boolean moved;                // the current press turned into a drag
    private double lastX, lastY, pressX, pressY;

    MapView() {
        getStyleClass().add("zm-map");
        setContent(holder);
        setPannable(true);
        holder.getStyleClass().add("zm-map-surface");
        viewportBoundsProperty().addListener((obs, o, n) -> {
            if (!dragging) relayout();
        });
        canvas.setOnMousePressed(this::onPressed);
        canvas.setOnMouseDragged(this::onDragged);
        canvas.setOnMouseReleased(this::onReleased);
        canvas.setOnContextMenuRequested(this::showRoomMenu);
        // a left click anywhere on the map closes an open right-click menu
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY) hideMenu();
        });
        canvas.setOnMouseMoved(e -> {
            double x = mapX(e.getX()), y = mapY(e.getY());
            canvas.setCursor(noteHit(x, y) != null ? Cursor.HAND
                    : hit(x, y) != null ? Cursor.MOVE : Cursor.DEFAULT);
        });
        // Ctrl/Cmd + wheel and trackpad pinch zoom around the pointer
        addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isShortcutDown() || e.getDeltaY() == 0) return;
            setZoom(zoom.get() * (e.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP), e.getX(), e.getY());
            e.consume();
        });
        setOnZoom(e -> {
            setZoom(zoom.get() * e.getZoomFactor(), e.getX(), e.getY());
            e.consume();
        });
        relayout();
    }

    // ------------------------------------------------------------------ zoom

    /** The current zoom factor (1 = 100%); read-only for callers, change it with the methods below. */
    ReadOnlyDoubleProperty zoomProperty() { return zoom; }

    /** Zoom to {@code z} (clamped) around the middle of the view: how the shared zoom reaches every map. */
    void zoomTo(double z) { zoomAroundCentre(z); }

    private void zoomAroundCentre(double z) {
        Bounds vp = getViewportBounds();
        setZoom(z, vp.getWidth() / 2, vp.getHeight() / 2);
    }

    /** Change the zoom keeping the map point under viewport position (ax, ay) where it is. */
    private void setZoom(double z, double ax, double ay) {
        z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        if (Math.abs(z - 1) < 0.02) z = 1;                     // snap to 100% when passing it
        double old = zoom.get();
        if (z == old) return;
        double mx = (scrollX() + ax) / old - ox;
        double my = (scrollY() + ay) / old - oy;
        zoom.set(z);
        sizeCanvas();
        setScroll((mx + ox) * z - ax, (my + oy) * z - ay);
    }

    private double scrollX() {
        return getHvalue() * Math.max(0, canvas.getWidth() - getViewportBounds().getWidth());
    }

    private double scrollY() {
        return getVvalue() * Math.max(0, canvas.getHeight() - getViewportBounds().getHeight());
    }

    private void setScroll(double px, double py) {
        Bounds vp = getViewportBounds();
        double extraW = canvas.getWidth() - vp.getWidth(), extraH = canvas.getHeight() - vp.getHeight();
        setHvalue(extraW > 0 ? clamp(px / extraW) : 0);
        setVvalue(extraH > 0 ? clamp(py / extraH) : 0);
    }

    void setDark(boolean on) {
        dark = on;
        redraw();
    }

    void clear() {
        rooms.clear();
        links.clear();
        current = -1;
        selected = null;
        selectedRooms.clear();
        banding = false;
        relayout();
    }

    /** Called with a room's object number and name when the user double-clicks it. */
    void setOnRoomDoubleClick(BiConsumer<Integer, String> handler) { onRoomDoubleClick = handler; }

    /** Called with the linked map's id when the user clicks a circle (portal) room. */
    void setOnPortalClick(IntConsumer handler) { onPortalClick = handler; }

    int id() { return id; }

    void setId(int id) { this.id = id; }

    /**
     * Start this map with a single room, linked back to map {@code portalTo}; {@code here} marks it
     * as the player's location.
     */
    void seed(int object, String name, boolean here, int portalTo) {
        clear();
        Room r = new Room(object, name, 0, 0);
        r.portal = portalTo;
        rooms.put(object, r);
        current = here ? object : -1;
        relayout();
    }

    /** Link a room on this map to map {@code mapId}: it becomes a circle. */
    void setPortal(int object, int mapId) {
        Room r = rooms.get(object);
        if (r == null) return;
        r.portal = mapId;
        relayout();
    }

    /**
     * Keep only the portals for which {@code valid.test(object, linkedMapId)} holds; the others go
     * back to being rectangles (their map was closed, or the room is no longer on it).
     */
    void retainPortals(BiPredicate<Integer, Integer> valid) {
        boolean changed = false;
        for (Room r : rooms.values()) {
            if (r.portal != 0 && !valid.test(r.object, r.portal)) {
                r.portal = 0;
                changed = true;
            }
        }
        if (changed) relayout();
    }

    boolean contains(int object) { return rooms.containsKey(object); }

    // ------------------------------------------------------------------ room notes

    /**
     * Called with a room's object number and name when the user asks to see or edit its note
     * (Notes… on its menu, or a click on its note icon).
     */
    void setOnNoteRequest(BiConsumer<Integer, String> handler) { onNoteRequest = handler; }

    /** Tells the map which rooms have a note, so it can draw their note icon. */
    void setHasNote(IntPredicate test) { hasNote = test; }

    /** Notes changed elsewhere: redraw the note icons. */
    void refreshNotes() { redraw(); }

    /** Top-left corner, in map coordinates, of a room's note icon: inside a box's lower right corner, on a circle's edge. */
    private static double[] noteIconAt(Room r) {
        if (r.portal != 0) {
            double d = CIRCLE_D / 2 * Math.sqrt(0.5);       // 45° down-right on the circle
            return new double[] {r.x + d - NOTE_SIZE / 2, r.y + d - NOTE_SIZE / 2};
        }
        return new double[] {r.x + BOX_W / 2 - NOTE_SIZE - 3, r.y + BOX_H / 2 - NOTE_SIZE - 3};
    }

    /** The room whose note icon is under a map point, or null. */
    private Room noteHit(double x, double y) {
        List<Room> rs = new ArrayList<>(rooms.values());
        for (int i = rs.size() - 1; i >= 0; i--) {
            Room r = rs.get(i);
            if (!hasNote.test(r.object)) continue;
            double[] p = noteIconAt(r);
            if (x >= p[0] - 2 && x <= p[0] + NOTE_SIZE + 2 && y >= p[1] - 2 && y <= p[1] + NOTE_SIZE + 2) return r;
        }
        return null;
    }

    /** A small yellow sticky note with a folded corner and a few lines of "writing". */
    private static void drawNoteIcon(GraphicsContext g, double x, double y) {
        double s = NOTE_SIZE, f = 4;
        Color paper = Appearance.mapNote(), edge = paper.deriveColor(0, 1, 0.6, 1);   // a darker rim of the same colour
        g.setLineDashes();
        g.setFill(paper);
        g.fillPolygon(new double[] {x, x + s - f, x + s, x + s, x},
                new double[] {y, y, y + f, y + s, y + s}, 5);
        g.setStroke(edge);
        g.setLineWidth(1);
        g.strokePolygon(new double[] {x, x + s - f, x + s, x + s, x},
                new double[] {y, y, y + f, y + s, y + s}, 5);
        g.setFill(edge.deriveColor(0, 1, 1.2, 0.8));
        g.fillPolygon(new double[] {x + s - f, x + s - f, x + s}, new double[] {y, y + f, y + f}, 3);
        g.setLineWidth(0.8);
        for (int i = 0; i < 3; i++) {
            double ly = y + 5 + i * 2.6;
            g.strokeLine(x + 2.5, ly, x + s - (i == 2 ? 5 : 2.5), ly);
        }
    }

    // ------------------------------------------------------------------ moving rooms between maps

    /** Called with the selected rooms' object numbers when the user chooses Move on a room's menu. */
    void setOnMoveRequest(Consumer<List<Integer>> handler) { onMoveRequest = handler; }

    /** Decides whether Move is offered (there must be another map to move to). */
    void setMoveAvailable(BooleanSupplier available) { moveAvailable = available; }

    /**
     * Rooms lifted off one map with every line that touched them, ready for {@link #putRooms}.
     * {@code neighbours} are copies of the rooms left behind at the far end of those lines.
     */
    static final class Transfer {
        private final List<Room> rooms = new ArrayList<>();
        private final List<Link> links = new ArrayList<>();
        private final List<Room> neighbours = new ArrayList<>();
    }

    /**
     * Remove these rooms from this map, with every line that touches them. A line to a room that
     * stays behind travels too, along with a copy of that room for it to end at.
     */
    Transfer takeRooms(List<Integer> objects) {
        Transfer t = new Transfer();
        Set<Integer> moving = new LinkedHashSet<>();
        for (int obj : objects) {
            Room r = rooms.remove(obj);
            if (r == null) continue;
            moving.add(obj);
            t.rooms.add(r);
            selectedRooms.remove(r);
        }
        Set<Integer> copied = new LinkedHashSet<>();
        links.values().removeIf(l -> {
            boolean a = moving.contains(l.a), b = moving.contains(l.b);
            if (!a && !b) return false;
            t.links.add(l);
            Room other = a == b ? null : rooms.get(a ? l.b : l.a);   // the end that stays here
            if (other != null && copied.add(other.object)) {
                Room copy = new Room(other.object, other.name, other.x, other.y);
                copy.text = other.text;
                copy.outline = other.outline;
                t.neighbours.add(copy);
            }
            return true;
        });
        if (moving.contains(current)) current = -1;
        if (selected instanceof Room r && moving.contains(r.object)
                || selected instanceof Link l && (moving.contains(l.a) || moving.contains(l.b))
                || selected instanceof LabelRef ref && (moving.contains(ref.link().a) || moving.contains(ref.link().b))) {
            selected = null;
        }
        relayout();
        return t;
    }

    /**
     * Add rooms taken from map {@code fromMap}, keeping their layout and every line that touched
     * them. They go to the right of this map's rooms so nothing overlaps. A room that is already
     * here is not duplicated. A line's far end that isn't on this map yet arrives as a copy of
     * that room, drawn as a circle linked back to {@code fromMap}. The moved rooms end up selected.
     *
     * @return the rooms left behind on {@code fromMap} that lines now lead to from this map, so
     *         that map can link them here
     */
    List<Integer> putRooms(Transfer t, int fromMap) {
        List<Room> incoming = new ArrayList<>();
        for (Room r : t.rooms) if (!rooms.containsKey(r.object)) incoming.add(r);
        List<Room> stubs = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        for (Room n : t.neighbours) {
            ends.add(n.object);
            Room here = rooms.get(n.object);
            if (here == null) {
                n.portal = fromMap;
                stubs.add(n);
            } else if (here.portal == 0) {
                here.portal = fromMap;               // already here: just link it back
            }
        }

        List<Room> placed = new ArrayList<>(incoming);
        placed.addAll(stubs);
        if (!placed.isEmpty()) placeGroup(placed);
        if (!incoming.isEmpty()) {
            selectedRooms.clear();
            selectedRooms.addAll(incoming);
            selected = incoming.get(0);
        }
        for (Link l : t.links) {
            if (rooms.containsKey(l.a) && rooms.containsKey(l.b)) links.putIfAbsent(linkKey(l.a, l.b), l);
        }
        relayout();
        return ends;
    }

    /** Link a room to map {@code mapId} (it becomes a circle) unless it is already linked elsewhere. */
    void linkIfFree(int object, int mapId) {
        Room r = rooms.get(object);
        if (r == null || r.portal != 0) return;
        r.portal = mapId;
        relayout();
    }

    /** Add rooms from another map as one block, shifted to the right of this map's rooms. */
    private void placeGroup(List<Room> group) {
        double gx0 = Double.MAX_VALUE, gy0 = Double.MAX_VALUE, gx1 = -Double.MAX_VALUE, gy1 = -Double.MAX_VALUE;
        for (Room r : group) {
            gx0 = Math.min(gx0, r.x - halfW(r));
            gy0 = Math.min(gy0, r.y - halfH(r));
            gx1 = Math.max(gx1, r.x + halfW(r));
            gy1 = Math.max(gy1, r.y + halfH(r));
        }
        double dx, dy;
        if (rooms.isEmpty()) {
            dx = -(gx0 + gx1) / 2;
            dy = -(gy0 + gy1) / 2;
        } else {
            double ex1 = -Double.MAX_VALUE, ey0 = Double.MAX_VALUE;
            for (Room r : rooms.values()) {
                ex1 = Math.max(ex1, r.x + halfW(r));
                ey0 = Math.min(ey0, r.y - halfH(r));
            }
            dx = ex1 + (STEP_X - BOX_W) - gx0;      // one normal gap to the right of everything
            dy = ey0 - gy0;
        }

        for (Room r : group) {
            r.x += dx;
            r.y += dy;
            rooms.put(r.object, r);
        }
    }

    // ------------------------------------------------------------------ adding rooms by hand

    /**
     * A request to add a room by hand at map point (x, y). {@code linkTo} is the selected room to
     * join it to (null for none), and {@code linkToName} that room's name.
     */
    record AddRequest(double x, double y, Integer linkTo, String linkToName) {}

    private BooleanSupplier manualEditing = () -> false;
    private Consumer<AddRequest> onAddRequest = r -> { };
    private double rightX = Double.NaN, rightY = Double.NaN;   // where the right button went down

    /** Decides whether the empty-space menu (Add Room…) is offered: only while Auto Map is off. */
    void setManualEditing(BooleanSupplier allowed) { manualEditing = allowed; }

    /** Called when the user picks Add Room… or Add Room Linked to … on the empty-space menu. */
    void setOnAddRequest(Consumer<AddRequest> handler) { onAddRequest = handler; }

    /** The lowest object number on this map (0 when it has no rooms): hand-made rooms use negative ones. */
    int minObject() {
        int min = 0;
        for (int obj : rooms.keySet()) min = Math.min(min, obj);
        return min;
    }

    /**
     * Add a room made by hand, centred at map point (x, y), and select it. With {@code linkTo}
     * (a room on this map) it is joined to that room by a line; {@code dirOut} is the direction
     * from {@code linkTo} to the new room and {@code dirBack} the way back (either may be null).
     */
    void addRoom(int object, String name, double x, double y, Integer linkTo, String dirOut, String dirBack) {
        if (rooms.containsKey(object)) return;
        Room room = new Room(object, name, x, y);
        rooms.put(object, room);
        if (linkTo != null && rooms.containsKey(linkTo) && linkTo != object) {
            Link link = links.computeIfAbsent(linkKey(linkTo, object),
                    k -> new Link(Math.min(linkTo, object), Math.max(linkTo, object)));
            boolean fromA = link.a == linkTo;
            if (dirOut != null) {
                if (fromA) link.dirA = dirOut; else link.dirB = dirOut;
            }
            if (dirBack != null) {
                if (fromA) link.dirB = dirBack; else link.dirA = dirBack;
            }
        }
        selectOnly(room);
        relayout();
    }

    /** Right-click on empty space while Auto Map is off: Add Room… / Add Room Linked to …. */
    private void showAddMenu(ContextMenuEvent e) {
        if (!manualEditing.getAsBoolean()) return;
        // a right-drag pans the map; don't pop up a menu when it ends
        if (rightDragged(e)) return;
        e.consume();
        double x = mapX(e.getX()), y = mapY(e.getY());
        Room sel = selectedRooms.size() == 1 ? selectedRooms.iterator().next() : null;

        MenuItem add = new MenuItem("Add Room…");
        add.setOnAction(a -> onAddRequest.accept(new AddRequest(x, y, null, null)));
        MenuItem linked = new MenuItem(sel == null
                ? "Add Room Linked to Selected Room…"
                : "Add Room Linked to “" + shorten(sel.name) + "”…");
        linked.setDisable(sel == null);              // select exactly one room first
        if (sel != null) {
            linked.setOnAction(a -> onAddRequest.accept(new AddRequest(x, y, sel.object, sel.name)));
        }
        showMenu(e, add, linked);
    }

    private static String shorten(String s) {
        return s.length() <= 28 ? s : s.substring(0, 27) + "…";
    }

    private BiConsumer<Integer, String> onLoopRequest = (obj, name) -> { };
    private Consumer<List<Integer>> onDeleteRequest = objects -> { };

    /** Called with a room when the user picks Add Loop… on its menu (Auto Map off). */
    void setOnLoopRequest(BiConsumer<Integer, String> handler) { onLoopRequest = handler; }

    /** Called with the selected rooms when the user picks Delete Room on a room's menu (Auto Map off). */
    void setOnDeleteRequest(Consumer<List<Integer>> handler) { onDeleteRequest = handler; }

    /** Add a loop (a connection from a room back to itself) labelled {@code dir} (may be null), and select it. */
    void addLoop(int object, String dir) {
        if (!rooms.containsKey(object)) return;
        Link loop = links.computeIfAbsent(linkKey(object, object), k -> new Link(object, object));
        loop.dirA = dir;
        selectedRooms.clear();
        selected = loop;
        relayout();
    }

    /** Remove rooms from this map with every connection that touches them. */
    void deleteRooms(List<Integer> objects) {
        Set<Integer> gone = new LinkedHashSet<>(objects);
        rooms.keySet().removeAll(gone);
        links.values().removeIf(l -> gone.contains(l.a) || gone.contains(l.b));
        selectedRooms.removeIf(r -> gone.contains(r.object));
        if (gone.contains(current)) current = -1;
        selected = null;
        relayout();
    }

    /** Remove one connection (a line or a loop) and its labels. */
    private void deleteLink(Link l) {
        links.remove(linkKey(l.a, l.b));
        if (selected == l || selected instanceof LabelRef ref && ref.link() == l) selected = null;
        relayout();
    }

    /** True if the right button moved since it went down (a pan, not a click); forgets the press. */
    private boolean rightDragged(ContextMenuEvent e) {
        boolean panned = !Double.isNaN(rightX) && Math.hypot(e.getX() - rightX, e.getY() - rightY) > 4;
        rightX = rightY = Double.NaN;
        return panned;
    }

    /** Close the right-click menu, if one is open. */
    void hideMenu() {
        if (roomMenu != null && roomMenu.isShowing()) roomMenu.hide();
    }

    private void showMenu(ContextMenuEvent e, MenuItem... items) {
        if (roomMenu != null) roomMenu.hide();
        roomMenu = new ContextMenu(items);
        roomMenu.setAutoHide(true);
        roomMenu.setConsumeAutoHidingEvents(false);   // the click that closes it still does its job
        roomMenu.show(canvas, e.getScreenX(), e.getScreenY());
    }

    /** Right-click on a line, loop or label while Auto Map is off: Delete Connection. */
    private void showLinkMenu(ContextMenuEvent e, Object h) {
        if (!manualEditing.getAsBoolean() || rightDragged(e)) return;
        Link l = h instanceof LabelRef ref ? ref.link() : (Link) h;
        e.consume();
        selectedRooms.clear();
        selected = l;
        redraw();
        MenuItem edit = new MenuItem(isLoop(l) ? "Edit Loop…" : "Edit Connection…");
        edit.setOnAction(a -> {
            Room ra = rooms.get(l.a), rb = rooms.get(l.b);
            if (ra != null && rb != null) {
                onLinkEditRequest.accept(new LinkEdit(l.a, ra.name, l.b, rb.name, l.dirA, isLoop(l) ? null : l.dirB));
            }
        });
        MenuItem delete = new MenuItem(isLoop(l) ? "Delete Loop" : "Delete Connection");
        delete.setOnAction(a -> deleteLink(l));
        showMenu(e, edit, delete);
    }

    /**
     * A connection to edit: rooms {@code a} and {@code b} (the same room for a loop) and its
     * labels, {@code dirA} leading from a to b and {@code dirB} from b to a (null for none; a loop
     * only has dirA).
     */
    record LinkEdit(int a, String aName, int b, String bName, String dirA, String dirB) {
        boolean loop() { return a == b; }
    }

    private Consumer<LinkEdit> onLinkEditRequest = r -> { };

    /** Called when the user picks Edit Connection… / Edit Loop… on a connection's menu. */
    void setOnLinkEditRequest(Consumer<LinkEdit> handler) { onLinkEditRequest = handler; }

    /**
     * Change a connection's labels: {@code dirA} leads from room a to b, {@code dirB} from b to a
     * (null removes a label). A label that is removed and later given again starts at its default spot.
     */
    void setLinkLabels(int a, int b, String dirA, String dirB) {
        Link l = links.get(linkKey(a, b));
        if (l == null) return;
        if (l.a != a) {                               // the caller's a is this link's b
            String t = dirA;
            dirA = dirB;
            dirB = t;
        }
        if (dirA == null) l.offAx = l.offAy = 0;
        if (dirB == null) l.offBx = l.offBy = 0;
        l.dirA = dirA;
        l.dirB = isLoop(l) ? null : dirB;
        if (selected instanceof LabelRef ref && ref.link() == l && labelText(ref) == null) selected = l;
        relayout();
    }

    private void showRoomMenu(ContextMenuEvent e) {
        Object h = hit(mapX(e.getX()), mapY(e.getY()));
        if (h == null) {
            showAddMenu(e);
            return;
        }
        if (!(h instanceof Room r)) {
            showLinkMenu(e, h);
            return;
        }
        rightDragged(e);                          // forget the press
        e.consume();
        if (!selectedRooms.contains(r)) selectOnly(r);   // right-click outside the selection: just this room
        redraw();
        List<Integer> objects = new ArrayList<>();
        for (Room s : selectedRooms) objects.add(s.object);

        MenuItem note = new MenuItem("Notes…");             // always for the room that was right-clicked
        note.setOnAction(a -> onNoteRequest.accept(r.object, r.name));
        MenuItem move = new MenuItem(objects.size() == 1 ? "Move" : "Move " + objects.size() + " Rooms");
        move.setDisable(!moveAvailable.getAsBoolean());
        move.setOnAction(a -> onMoveRequest.accept(objects));
        MenuItem edit = new MenuItem(objects.size() == 1 ? "Edit Room…" : "Edit " + objects.size() + " Rooms…");
        edit.setOnAction(a -> onRoomEditRequest.accept(
                new RoomEdit(objects, objects.size() == 1 ? r.name : null, r.text, r.outline)));
        if (!manualEditing.getAsBoolean()) {
            showMenu(e, note, edit, move);
            return;
        }
        // Auto Map off: editing by hand
        MenuItem loop = new MenuItem("Add Loop…");          // for the room that was right-clicked
        loop.setDisable(links.containsKey(linkKey(r.object, r.object)));   // one loop per room
        loop.setOnAction(a -> onLoopRequest.accept(r.object, r.name));
        MenuItem delete = new MenuItem(objects.size() == 1 ? "Delete Room" : "Delete " + objects.size() + " Rooms");
        delete.setOnAction(a -> onDeleteRequest.accept(objects));
        if (selectedRooms.size() != 2) {
            showMenu(e, note, edit, move, new SeparatorMenuItem(), loop, delete);
            return;
        }
        // exactly two rooms selected: join them (or edit the line that already joins them)
        List<Room> pair = new ArrayList<>(selectedRooms);       // in the order they were selected
        Room ra = pair.get(0), rb = pair.get(1);
        Link existing = links.get(linkKey(ra.object, rb.object));
        MenuItem connect = new MenuItem(existing == null ? "Connect Rooms…" : "Edit Connection…");
        connect.setOnAction(a -> {
            String dirA = null, dirB = null;                     // ra -> rb and rb -> ra
            if (existing != null) {
                dirA = existing.a == ra.object ? existing.dirA : existing.dirB;
                dirB = existing.a == ra.object ? existing.dirB : existing.dirA;
            }
            LinkEdit linkEdit = new LinkEdit(ra.object, ra.name, rb.object, rb.name, dirA, dirB);
            if (existing == null) onConnectRequest.accept(linkEdit);
            else onLinkEditRequest.accept(linkEdit);
        });
        showMenu(e, note, edit, move, new SeparatorMenuItem(), connect, loop, delete);
    }

    /**
     * Edit Room… was picked for {@code objects} (the right-clicked room, or the selection it is
     * in). {@code name} is the room's name when there is just one; {@code text} and
     * {@code outline} are the right-clicked room's own colours (null for the usual ones).
     */
    record RoomEdit(List<Integer> objects, String name, Color text, Color outline) {}

    private Consumer<RoomEdit> onRoomEditRequest = r -> { };

    void setOnRoomEditRequest(Consumer<RoomEdit> handler) { onRoomEditRequest = handler; }

    /** Give these rooms their own text and outline colours (null puts back the usual colour). */
    void setRoomColours(List<Integer> objects, Color text, Color outline) {
        for (int obj : objects) {
            Room r = rooms.get(obj);
            if (r == null) continue;
            r.text = text;
            r.outline = outline;
        }
        redraw();
    }

    /** The room's own text colour, or null for the usual one (or if the room isn't here). */
    Color roomText(int object) {
        Room r = rooms.get(object);
        return r == null ? null : r.text;
    }

    /** The room's own outline colour, or null for the usual one (or if the room isn't here). */
    Color roomOutline(int object) {
        Room r = rooms.get(object);
        return r == null ? null : r.outline;
    }

    /** The colours a room has when none are picked for it: {text, outline}. */
    Color[] usualRoomColours() {
        return new Color[] {Appearance.mapText(dark), lineColour()};
    }

    private Color lineColour() {
        return Color.web(dark ? "#6b6f78" : "#a39d90");
    }

    private Consumer<LinkEdit> onConnectRequest = r -> { };

    /** Called with the two selected rooms when the user picks Connect Rooms… (Auto Map off). */
    void setOnConnectRequest(Consumer<LinkEdit> handler) { onConnectRequest = handler; }

    /**
     * Join rooms {@code a} and {@code b} with a straight line, labelled {@code dirA} (from a to b)
     * and {@code dirB} (from b to a), either may be null; the line ends up selected. An existing
     * line between them just gets the new labels.
     */
    void connect(int a, int b, String dirA, String dirB) {
        if (a == b || !rooms.containsKey(a) || !rooms.containsKey(b)) return;
        Link l = links.computeIfAbsent(linkKey(a, b), k -> new Link(Math.min(a, b), Math.max(a, b)));
        selectedRooms.clear();
        selected = l;
        setLinkLabels(a, b, dirA, dirB);                         // also relays out and redraws
    }

    private static double halfW(Room r) { return r.portal != 0 ? CIRCLE_D / 2 : BOX_W / 2; }

    private static double halfH(Room r) { return r.portal != 0 ? CIRCLE_D / 2 : BOX_H / 2; }

    /**
     * The player moved, but rooms are being added to a different map: just track whether they
     * are in one of this map's rooms, so it is highlighted and later moves link from it.
     */
    void locate(int object) {
        int c = rooms.containsKey(object) ? object : -1;
        if (c == current) return;
        current = c;
        redraw();
    }

    /**
     * The player is now in {@code object}. {@code command} is the line that took them there and
     * {@code travelled} is false after a restart, restore or undo (no connection is drawn then).
     */
    void enter(int object, String name, String command, boolean travelled) {
        if (object == current) return;
        Room from = rooms.get(current);
        String dir = travelled ? directionName(command) : null;
        Room room = rooms.get(object);
        if (room == null) {
            double[] at = place(from, dir);
            room = new Room(object, name, at[0], at[1]);
            rooms.put(object, room);
        } else {
            room.name = name;                 // some games rename rooms (e.g. lit/unlit)
        }
        if (travelled && from != null) {
            Link link = links.computeIfAbsent(linkKey(from.object, object),
                    k -> new Link(Math.min(from.object, object), Math.max(from.object, object)));
            if (dir != null) {
                if (link.a == from.object && link.dirA == null) link.dirA = dir;
                else if (link.b == from.object && link.dirB == null) link.dirB = dir;
            }
        }
        current = object;
        relayout();
        scrollToCurrent();
    }

    // ------------------------------------------------------------------ directions and placement

    private static long linkKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /** The label for a movement command ("n", "ne", "up", ...), or null if it isn't one. */
    static String directionName(String command) {
        if (command == null) return null;
        String c = command.strip().toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"go ", "walk ", "run ", "climb "}) {
            if (c.startsWith(prefix)) c = c.substring(prefix.length()).strip();
        }
        int space = c.indexOf(' ');
        if (space > 0) c = c.substring(0, space);
        switch (c) {
            case "n": case "north": return "n";
            case "s": case "south": return "s";
            case "e": case "east": return "e";
            case "w": case "west": return "w";
            case "ne": case "northeast": return "ne";
            case "nw": case "northwest": return "nw";
            case "se": case "southeast": return "se";
            case "sw": case "southwest": return "sw";
            case "u": case "up": return "up";
            case "d": case "down": return "down";
            case "in": case "inside": return "in";
            case "out": case "outside": return "out";
            default: return null;
        }
    }

    /** Unit step on the map for a compass direction (north is up, east is right); null otherwise. */
    static int[] offset(String dir) {
        if (dir == null) return null;
        switch (dir) {
            case "n": return new int[] {0, -1};
            case "s": return new int[] {0, 1};
            case "e": return new int[] {1, 0};
            case "w": return new int[] {-1, 0};
            case "ne": return new int[] {1, -1};
            case "nw": return new int[] {-1, -1};
            case "se": return new int[] {1, 1};
            case "sw": return new int[] {-1, 1};
            default: return null;
        }
    }

    /**
     * True if a new (rectangular) room centred at (x, y) would keep at least GAP_X / GAP_Y of space
     * from every existing room, using each room's real extent (circles are taller than rectangles,
     * and rooms the user dragged can be anywhere).
     */
    private boolean free(double x, double y) {
        for (Room r : rooms.values()) {
            if (Math.abs(r.x - x) < halfW(r) + BOX_W / 2 + GAP_X
                    && Math.abs(r.y - y) < halfH(r) + BOX_H / 2 + GAP_Y) return false;
        }
        return true;
    }

    /**
     * Where a new room goes. A compass move puts it one step that way from the previous room, or
     * further along the same line if that spot is taken; anything else takes the nearest free spot.
     */
    private double[] place(Room from, String dir) {
        double fx = from != null ? from.x : 0, fy = from != null ? from.y : 0;
        if (rooms.isEmpty()) return new double[] {0, 0};
        int[] d = offset(dir);
        if (d != null) {
            for (int k = 1; k <= 6; k++) {
                double x = fx + d[0] * k * STEP_X, y = fy + d[1] * k * STEP_Y;
                if (free(x, y)) return new double[] {x, y};
            }
        }
        double tx = fx + (d != null ? d[0] : 1) * STEP_X;
        double ty = fy + (d != null ? d[1] : 0) * STEP_Y;
        for (int r = 1; r < 200; r++) {
            double[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    double x = fx + dx * STEP_X, y = fy + dy * STEP_Y;
                    if (!free(x, y)) continue;
                    double dist = Math.hypot(x - tx, y - ty);
                    if (dist < bestD) {
                        bestD = dist;
                        best = new double[] {x, y};
                    }
                }
            }
            if (best != null) return best;
        }
        // practically unreachable, but never return an occupied spot: go below everything
        double y = fy + STEP_Y;
        for (Room r : rooms.values()) y = Math.max(y, r.y + halfH(r) + BOX_H / 2 + GAP_Y);
        return new double[] {fx, y};
    }

    // ------------------------------------------------------------------ geometry

    /**
     * A loop is a link from a room back to itself (a == b): a small circle just outside the room
     * with an arrow on it and one label (dirA). Its bend (bendX, bendY) only gives the direction
     * from the room's centre to the loop; (0, 0) means the default, up and to the right.
     */
    private static boolean isLoop(Link l) { return l.a == l.b; }

    private static final double LOOP_R = 11;              // radius of a loop's circle

    /** Unit vector from a loop's room towards the loop. */
    private static double[] loopDir(Link l) {
        double len = Math.hypot(l.bendX, l.bendY);
        if (len < 1e-6) return new double[] {Math.sqrt(0.5), -Math.sqrt(0.5)};
        return new double[] {l.bendX / len, l.bendY / len};
    }

    /** Distance from a room's centre to its edge in the direction of unit vector (ux, uy). */
    private static double edge(Room r, double ux, double uy) {
        return r.portal != 0 ? CIRCLE_D / 2
                : Math.min(Math.abs(ux) < 1e-9 ? Double.MAX_VALUE : BOX_W / 2 / Math.abs(ux),
                        Math.abs(uy) < 1e-9 ? Double.MAX_VALUE : BOX_H / 2 / Math.abs(uy));
    }

    /** Centre of a loop's circle in map coordinates: overlapping the room's edge a little. */
    private double[] loopCentre(Link l) {
        Room r = rooms.get(l.a);
        double[] u = loopDir(l);
        double d = edge(r, u[0], u[1]) + LOOP_R * 0.6;
        return new double[] {r.x + u[0] * d, r.y + u[1] * d};
    }

    /** The bend point of a link, in map coordinates (for a loop: the far side of its circle). */
    private double[] bend(Link l) {
        if (isLoop(l)) {
            double[] c = loopCentre(l), u = loopDir(l);
            return new double[] {c[0] + u[0] * LOOP_R, c[1] + u[1] * LOOP_R};
        }
        Room a = rooms.get(l.a), b = rooms.get(l.b);
        return new double[] {(a.x + b.x) / 2 + l.bendX, (a.y + b.y) / 2 + l.bendY};
    }

    /** Centre of a label in map coordinates: just outside its room, along the line, plus its offset. */
    private double[] labelPos(LabelRef ref) {
        Link l = ref.link();
        if (isLoop(l)) {                              // beyond the loop, plus its offset
            double[] m = bend(l), u = loopDir(l);
            return new double[] {m[0] + u[0] * LABEL_GAP + l.offAx, m[1] + u[1] * LABEL_GAP + l.offAy};
        }
        Room r = rooms.get(ref.atA() ? l.a : l.b);
        double[] m = bend(l);
        double vx = m[0] - r.x, vy = m[1] - r.y;
        double len = Math.hypot(vx, vy);
        double px, py;
        if (len < 1e-6) {
            px = r.x;
            py = r.y - halfH(r) - LABEL_GAP;
        } else {
            double ux = vx / len, uy = vy / len;
            double edge = edge(r, ux, uy);
            double dist = Math.min(edge + LABEL_GAP, len * 0.9);
            px = r.x + ux * dist;
            py = r.y + uy * dist;
        }
        return ref.atA() ? new double[] {px + l.offAx, py + l.offAy} : new double[] {px + l.offBx, py + l.offBy};
    }

    private static String labelText(LabelRef ref) { return ref.atA() ? ref.link().dirA : ref.link().dirB; }

    private List<LabelRef> labels() {
        List<LabelRef> out = new ArrayList<>();
        for (Link l : links.values()) {
            if (!rooms.containsKey(l.a) || !rooms.containsKey(l.b)) continue;
            if (l.dirA != null) out.add(new LabelRef(l, true));
            if (l.dirB != null && !isLoop(l)) out.add(new LabelRef(l, false));   // a loop has one label
        }
        return out;
    }

    private static double[] labelSize(String text) {
        Text probe = new Text(text);
        probe.setFont(Appearance.mapLabelFont());
        return new double[] {probe.getLayoutBounds().getWidth() + 10, probe.getLayoutBounds().getHeight() + 4};
    }

    private static double segmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / len2));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    /** The topmost item under a map point: labels, then rooms, then lines. */
    private Object hit(double x, double y) {
        List<LabelRef> ls = labels();
        for (int i = ls.size() - 1; i >= 0; i--) {
            LabelRef ref = ls.get(i);
            double[] p = labelPos(ref);
            double[] s = labelSize(labelText(ref));
            if (Math.abs(x - p[0]) <= s[0] / 2 && Math.abs(y - p[1]) <= s[1] / 2) return ref;
        }
        List<Room> rs = new ArrayList<>(rooms.values());
        for (int i = rs.size() - 1; i >= 0; i--) {
            Room r = rs.get(i);
            boolean inside = r.portal != 0
                    ? Math.hypot(x - r.x, y - r.y) <= CIRCLE_D / 2
                    : Math.abs(x - r.x) <= BOX_W / 2 && Math.abs(y - r.y) <= BOX_H / 2;
            if (inside) return r;
        }
        for (Link l : links.values()) {
            Room a = rooms.get(l.a), b = rooms.get(l.b);
            if (a == null || b == null) continue;
            if (isLoop(l)) {
                double[] c = loopCentre(l);
                if (Math.abs(Math.hypot(x - c[0], y - c[1]) - LOOP_R) <= 6) return l;
                continue;
            }
            double[] m = bend(l);
            if (segmentDistance(x, y, a.x, a.y, m[0], m[1]) <= 6
                    || segmentDistance(x, y, m[0], m[1], b.x, b.y) <= 6) return l;
        }
        return null;
    }

    private double mapX(double canvasX) { return canvasX / zoom.get() - ox; }
    private double mapY(double canvasY) { return canvasY / zoom.get() - oy; }

    // ------------------------------------------------------------------ mouse

    /**
     * Primary button: on a room, select it (Shift/Ctrl/Cmd toggles it in the selection) and drag
     * every selected room; on a line or label, select and drag just that; on empty space, drag out a
     * selection box (with Shift/Ctrl/Cmd it adds to the selection). Other buttons pan the map.
     */
    private void onPressed(MouseEvent e) {
        if (e.getButton() == MouseButton.SECONDARY) {
            rightX = e.getX();
            rightY = e.getY();
        }
        if (e.getButton() != MouseButton.PRIMARY) return;
        e.consume();                          // primary drags select or move; they never pan
        boolean additive = e.isShiftDown() || e.isShortcutDown();
        Room noted = noteHit(mapX(e.getX()), mapY(e.getY()));
        if (noted != null) {                  // a click on a note icon opens the note; no select or drag
            dragging = false;
            banding = false;
            Platform.runLater(() -> onNoteRequest.accept(noted.object, noted.name));   // after the press finishes
            return;
        }
        Object h = hit(mapX(e.getX()), mapY(e.getY()));
        moved = false;
        lastX = pressX = e.getX();
        lastY = pressY = e.getY();

        if (h instanceof Room r && e.getClickCount() == 2) {
            selectOnly(r);
            redraw();
            if (r.portal == 0) onRoomDoubleClick.accept(r.object, r.name);   // circles already have a map
            return;
        }
        if (h instanceof Room r) {
            selected = r;
            if (additive) {
                if (!selectedRooms.remove(r)) selectedRooms.add(r);
                if (!selectedRooms.contains(r)) selected = null;
            } else if (!selectedRooms.contains(r)) {
                selectOnly(r);                // clicking inside a multi-selection keeps it, so it can be dragged
            }
            dragging = selected != null;
        } else if (h != null) {
            selectedRooms.clear();
            selected = h;
            if (e.getClickCount() == 2) resetPosition(h);
            dragging = true;
        } else {
            if (!additive) selectedRooms.clear();
            selected = null;
            banding = true;
            bandBase = new LinkedHashSet<>(selectedRooms);
            bandX0 = bandX1 = mapX(e.getX());
            bandY0 = bandY1 = mapY(e.getY());
        }
        redraw();
    }

    private void selectOnly(Room r) {
        selectedRooms.clear();
        selectedRooms.add(r);
        selected = r;
    }

    /** Rooms touched by the selection box, added to whatever was selected when it started. */
    private void updateBandSelection() {
        double x0 = Math.min(bandX0, bandX1), x1 = Math.max(bandX0, bandX1);
        double y0 = Math.min(bandY0, bandY1), y1 = Math.max(bandY0, bandY1);
        selectedRooms.clear();
        selectedRooms.addAll(bandBase);
        for (Room r : rooms.values()) {
            if (r.x + halfW(r) >= x0 && r.x - halfW(r) <= x1 && r.y + halfH(r) >= y0 && r.y - halfH(r) <= y1) {
                selectedRooms.add(r);
            }
        }
    }

    private void onDragged(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        e.consume();
        if (banding) {
            bandX1 = mapX(e.getX());
            bandY1 = mapY(e.getY());
            updateBandSelection();
            redraw();
            return;
        }
        if (!dragging || selected == null) return;
        if (!moved && Math.hypot(e.getX() - pressX, e.getY() - pressY) < 4) return;   // ignore jitter
        moved = true;
        double dx = (e.getX() - lastX) / zoom.get(), dy = (e.getY() - lastY) / zoom.get();
        lastX = e.getX();
        lastY = e.getY();
        if (selected instanceof Room) {
            for (Room r : selectedRooms) {    // lines and labels between them follow automatically
                r.x += dx;
                r.y += dy;
            }
        } else if (selected instanceof Link l && isLoop(l)) {
            Room r = rooms.get(l.a);           // a loop swings round its room towards the pointer
            l.bendX = mapX(e.getX()) - r.x;
            l.bendY = mapY(e.getY()) - r.y;
        } else if (selected instanceof Link l) {
            l.bendX += dx;
            l.bendY += dy;
        } else if (selected instanceof LabelRef ref) {
            Link l = ref.link();
            if (ref.atA()) {
                l.offAx += dx;
                l.offAy += dy;
            } else {
                l.offBx += dx;
                l.offBy += dy;
            }
        }
        redraw();
    }

    private void onReleased(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        e.consume();
        if (banding) {
            banding = false;
            bandBase = Set.of();
            redraw();
            return;
        }
        if (!dragging) return;
        dragging = false;
        relayout();                           // grow the canvas if something was dragged past an edge
        boolean additive = e.isShiftDown() || e.isShortcutDown();
        if (!moved && !additive && selected instanceof Room r && e.getClickCount() == 1) {
            if (selectedRooms.size() > 1) {   // a plain click (no drag) inside a group selects just that room
                selectOnly(r);
                redraw();
            }
            if (r.portal != 0) onPortalClick.accept(r.portal);   // a plain click on a circle opens its other map
        }
    }

    /** Double-click: straighten a line (or put a loop back up and to the right), or put a label back. */
    private static void resetPosition(Object item) {
        if (item instanceof Link l) {
            l.bendX = 0;
            l.bendY = 0;
        } else if (item instanceof LabelRef ref) {
            if (ref.atA()) {
                ref.link().offAx = 0;
                ref.link().offAy = 0;
            } else {
                ref.link().offBx = 0;
                ref.link().offBy = 0;
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    /** Size the canvas to fit everything and keep the visible part of the map where it was. */
    private void relayout() {
        double oldOx = ox, oldOy = oy;
        double sx = scrollX(), sy = scrollY();
        sizeCanvas();
        double z = zoom.get();
        setScroll(sx + (ox - oldOx) * z, sy + (oy - oldOy) * z);
    }

    /** Recompute the map origin and the canvas size (scaled by the zoom), then redraw. */
    private void sizeCanvas() {
        double minX = 0, minY = 0, maxX = 0, maxY = 0;
        boolean first = true;
        for (Room r : rooms.values()) {
            double[] box = {r.x - halfW(r), r.y - halfH(r), r.x + halfW(r), r.y + halfH(r)};
            if (first) {
                minX = box[0]; minY = box[1]; maxX = box[2]; maxY = box[3];
                first = false;
            } else {
                minX = Math.min(minX, box[0]); minY = Math.min(minY, box[1]);
                maxX = Math.max(maxX, box[2]); maxY = Math.max(maxY, box[3]);
            }
        }
        for (Link l : links.values()) {
            if (!rooms.containsKey(l.a) || !rooms.containsKey(l.b)) continue;
            double[] m = bend(l);
            minX = Math.min(minX, m[0]); minY = Math.min(minY, m[1]);
            maxX = Math.max(maxX, m[0]); maxY = Math.max(maxY, m[1]);
        }
        for (LabelRef ref : labels()) {
            double[] p = labelPos(ref);
            double[] s = labelSize(labelText(ref));
            minX = Math.min(minX, p[0] - s[0] / 2); minY = Math.min(minY, p[1] - s[1] / 2);
            maxX = Math.max(maxX, p[0] + s[0] / 2); maxY = Math.max(maxY, p[1] + s[1] / 2);
        }

        Bounds vp = getViewportBounds();
        double z = zoom.get();
        ox = MARGIN - minX;
        oy = MARGIN - minY;
        double w = Math.max(vp.getWidth(), (maxX - minX + 2 * MARGIN) * z);
        double h = Math.max(vp.getHeight(), (maxY - minY + 2 * MARGIN) * z);
        canvas.setWidth(Math.max(1, w));
        canvas.setHeight(Math.max(1, h));
        redraw();
    }

    /** File ▸ Preferences changed: the map background behind the canvas, then everything drawn on it. */
    void applyAppearance() {
        Color bg = Appearance.mapBackground;
        String style = bg == null ? "" : "-zm-bg: " + Appearance.web(bg) + ";";
        setStyle(style);
        relayout();                         // room name widths depend on the font
    }

    private void redraw() {
        Color bg = Appearance.mapBackground(dark);
        Color fg = Appearance.mapText(dark);
        Color line = lineColour();
        Color box = Color.web(dark ? "#272a30" : "#ece6d8");
        Color accent = Appearance.mapActiveRoom(dark);
        Color select = Color.web(dark ? "#6fa8ff" : "#2f6fd6");

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(bg);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);

        if (rooms.isEmpty()) {
            g.setFill(fg.deriveColor(0, 1, 1, 0.6));
            g.setFont(Appearance.mapFont());
            g.fillText("Rooms appear here as you explore.", canvas.getWidth() / 2, 40);
            return;
        }

        g.save();
        g.scale(zoom.get(), zoom.get());
        drawMap(g, bg, fg, line, box, accent, select);
        g.restore();
    }

    private void drawMap(GraphicsContext g, Color bg, Color fg, Color line, Color box, Color accent, Color select) {

        g.setLineDashes();
        for (Link l : links.values()) {
            Room a = rooms.get(l.a), b = rooms.get(l.b);
            if (a == null || b == null) continue;
            double[] m = bend(l);
            boolean sel = l == selected;
            g.setStroke(sel ? select : line);
            g.setLineWidth(sel ? 3 : 2);
            if (isLoop(l)) {                  // drawn before the rooms, so the part inside the room is hidden
                double[] c = loopCentre(l), u = loopDir(l);
                g.strokeOval(c[0] + ox - LOOP_R, c[1] + oy - LOOP_R, 2 * LOOP_R, 2 * LOOP_R);
                // arrowhead at the far side of the loop, pointing round it (clockwise)
                double tx = -u[1], ty = u[0], s = 5;
                double ax = m[0] + ox, ay = m[1] + oy;
                g.setFill(sel ? select : line);
                g.fillPolygon(
                        new double[] {ax + tx * s, ax - tx * s + u[0] * s, ax - tx * s - u[0] * s},
                        new double[] {ay + ty * s, ay - ty * s + u[1] * s, ay - ty * s - u[1] * s}, 3);
                continue;
            }
            g.strokePolyline(new double[] {a.x + ox, m[0] + ox, b.x + ox},
                    new double[] {a.y + oy, m[1] + oy, b.y + oy}, 3);
            if (sel) {
                g.setFill(select);
                g.fillOval(m[0] + ox - 4, m[1] + oy - 4, 8, 8);
            }
        }

        for (Room r : rooms.values()) {
            boolean here = r.object == current;
            Font font = here ? Appearance.mapBoldFont() : Appearance.mapFont();
            // the room you're in keeps readable text on its highlight; a picked outline still shows
            Color nameColour = here ? Appearance.contrastOn(accent) : r.text != null ? r.text : fg;
            g.setFill(here ? accent : box);
            g.setStroke(r.outline != null ? r.outline : here ? accent : line);
            g.setLineWidth(here || r.outline != null ? 2 : 1.2);
            if (r.portal != 0) {
                double x = r.x + ox - CIRCLE_D / 2, y = r.y + oy - CIRCLE_D / 2;
                g.fillOval(x, y, CIRCLE_D, CIRCLE_D);
                g.strokeOval(x, y, CIRCLE_D, CIRCLE_D);
                if (selectedRooms.contains(r)) {
                    g.setStroke(select);
                    g.setLineWidth(2);
                    g.setLineDashes(5, 3);
                    g.strokeOval(x - 4, y - 4, CIRCLE_D + 8, CIRCLE_D + 8);
                    g.setLineDashes();
                }
                g.setFill(nameColour);
                g.setFont(font);
                List<String> lines = wrap(r.name, font, CIRCLE_D - 14);
                double lh = Math.ceil(font.getSize() * 1.25);
                double top = r.y + oy - lh * (lines.size() - 1) / 2;
                for (int i = 0; i < lines.size(); i++) g.fillText(lines.get(i), r.x + ox, top + i * lh);
            } else {
                double x = r.x + ox - BOX_W / 2, y = r.y + oy - BOX_H / 2;
                g.fillRoundRect(x, y, BOX_W, BOX_H, 8, 8);
                g.strokeRoundRect(x, y, BOX_W, BOX_H, 8, 8);
                if (selectedRooms.contains(r)) {
                    g.setStroke(select);
                    g.setLineWidth(2);
                    g.setLineDashes(5, 3);
                    g.strokeRoundRect(x - 4, y - 4, BOX_W + 8, BOX_H + 8, 11, 11);
                    g.setLineDashes();
                }
                g.setFill(nameColour);
                g.setFont(font);
                g.fillText(fit(r.name, font, BOX_W - 12), r.x + ox, r.y + oy);
            }
            if (hasNote.test(r.object)) {
                double[] p = noteIconAt(r);
                drawNoteIcon(g, p[0] + ox, p[1] + oy);
            }
        }

        g.setFont(Appearance.mapLabelFont());
        for (LabelRef ref : labels()) {
            String text = labelText(ref);
            double[] p = labelPos(ref);
            double[] s = labelSize(text);
            boolean sel = ref.equals(selected);
            double x = p[0] + ox - s[0] / 2, y = p[1] + oy - s[1] / 2;
            g.setFill(bg);
            g.fillRoundRect(x, y, s[0], s[1], 8, 8);
            g.setStroke(sel ? select : line);
            g.setLineWidth(sel ? 2 : 1);
            g.strokeRoundRect(x, y, s[0], s[1], 8, 8);
            // exit labels keep the theme's accent unless a map font colour is chosen
            g.setFill(sel ? select : Appearance.mapText != null ? fg
                    : Color.web(dark ? Appearance.DARK_ACCENT : Appearance.LIGHT_ACCENT));
            g.fillText(text, p[0] + ox, p[1] + oy);
        }

        if (banding) {
            double x = Math.min(bandX0, bandX1) + ox, y = Math.min(bandY0, bandY1) + oy;
            double w = Math.abs(bandX1 - bandX0), h = Math.abs(bandY1 - bandY0);
            g.setFill(select.deriveColor(0, 1, 1, 0.12));
            g.fillRect(x, y, w, h);
            g.setStroke(select);
            g.setLineWidth(1 / zoom.get());   // a hairline at any zoom
            g.setLineDashes(4 / zoom.get(), 3 / zoom.get());
            g.strokeRect(x, y, w, h);
            g.setLineDashes();
        }
    }

    private static double textWidth(String s, Font font) {
        Text probe = new Text(s);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    /** Break a name into at most three lines that fit {@code max}; the last one is truncated if needed. */
    private static List<String> wrap(String s, Font font, double max) {
        List<String> lines = new ArrayList<>();
        String[] words = s.split("\\s+");
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < words.length && lines.size() < 2) {
            String trial = cur.length() == 0 ? words[i] : cur + " " + words[i];
            if (cur.length() == 0 || textWidth(trial, font) <= max) {
                cur.setLength(0);
                cur.append(trial);
                i++;
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
            }
        }
        StringBuilder rest = new StringBuilder(cur);
        for (; i < words.length; i++) rest.append(rest.length() == 0 ? "" : " ").append(words[i]);
        if (rest.length() > 0) lines.add(rest.toString());
        for (int k = 0; k < lines.size(); k++) lines.set(k, fit(lines.get(k), font, max));
        return lines;
    }

    private static String fit(String s, Font font, double max) {
        Text probe = new Text(s);
        probe.setFont(font);
        if (probe.getLayoutBounds().getWidth() <= max) return s;
        for (int n = s.length() - 1; n > 1; n--) {
            String t = s.substring(0, n).stripTrailing() + "…";
            probe.setText(t);
            if (probe.getLayoutBounds().getWidth() <= max) return t;
        }
        return s.substring(0, 1) + "…";
    }

    private void scrollToCurrent() {
        Room r = rooms.get(current);
        if (r == null) return;
        Bounds vp = getViewportBounds();
        double z = zoom.get();
        setScroll((r.x + ox) * z - vp.getWidth() / 2, (r.y + oy) * z - vp.getHeight() / 2);
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    // ------------------------------------------------------------------ persistence

    /**
     * Plain-text form, one item per line: "V 2", "R obj x y name",
     * "L a b bendX bendY offAx offAy offBx offBy dirA dirB" ("-" for no direction), "C obj",
     * "Z zoom", "I mapId", "P obj linkedMapId" and "K obj textColour outlineColour" (after all
     * rooms; colours as #rrggbb, "-" for the usual colour).
     */
    byte[] serialize() {
        StringBuilder sb = new StringBuilder("V 2\n");
        for (Room r : rooms.values()) {
            sb.append("R ").append(r.object).append(' ').append(r.x).append(' ').append(r.y)
                    .append(' ').append(r.name.replace('\n', ' ')).append('\n');
        }
        for (Link l : links.values()) {
            sb.append("L ").append(l.a).append(' ').append(l.b)
                    .append(' ').append(l.bendX).append(' ').append(l.bendY)
                    .append(' ').append(l.offAx).append(' ').append(l.offAy)
                    .append(' ').append(l.offBx).append(' ').append(l.offBy)
                    .append(' ').append(l.dirA == null ? "-" : l.dirA)
                    .append(' ').append(l.dirB == null ? "-" : l.dirB).append('\n');
        }
        if (current >= 0) sb.append("C ").append(current).append('\n');
        sb.append("Z ").append(zoom.get()).append('\n');
        sb.append("I ").append(id).append('\n');
        for (Room r : rooms.values()) {
            if (r.portal != 0) sb.append("P ").append(r.object).append(' ').append(r.portal).append('\n');
            if (r.text != null || r.outline != null) {
                sb.append("K ").append(r.object)
                        .append(' ').append(r.text == null ? "-" : Appearance.web(r.text))
                        .append(' ').append(r.outline == null ? "-" : Appearance.web(r.outline)).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    void deserialize(byte[] data) {
        rooms.clear();
        links.clear();
        current = -1;
        selected = null;
        selectedRooms.clear();
        banding = false;
        zoom.set(1.0);
        List<String> lines = new String(data, StandardCharsets.UTF_8).lines().toList();
        boolean v2 = !lines.isEmpty() && lines.get(0).equals("V 2");
        for (String ln : lines) {
            String[] p = ln.split(" ", v2 && ln.startsWith("L ") ? 11 : 5);
            try {
                switch (p[0]) {
                    case "R" -> {
                        double x = Double.parseDouble(p[2]), y = Double.parseDouble(p[3]);
                        if (!v2) {                         // first format stored grid cells
                            x *= STEP_X;
                            y *= STEP_Y;
                        }
                        Room r = new Room(Integer.parseInt(p[1]), p.length > 4 ? p[4] : "?", x, y);
                        rooms.put(r.object, r);
                    }
                    case "L" -> {
                        Link l = new Link(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                        l.bendX = Double.parseDouble(p[3]);
                        l.bendY = Double.parseDouble(p[4]);
                        l.offAx = Double.parseDouble(p[5]);
                        l.offAy = Double.parseDouble(p[6]);
                        l.offBx = Double.parseDouble(p[7]);
                        l.offBy = Double.parseDouble(p[8]);
                        l.dirA = p[9].equals("-") ? null : p[9];
                        l.dirB = p[10].equals("-") ? null : p[10];
                        links.put(linkKey(l.a, l.b), l);
                    }
                    case "E" -> {
                        int a = Integer.parseInt(p[1]), b = Integer.parseInt(p[2]);
                        links.put(linkKey(a, b), new Link(Math.min(a, b), Math.max(a, b)));
                    }
                    case "C" -> current = Integer.parseInt(p[1]);
                    case "Z" -> zoom.set(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Double.parseDouble(p[1]))));
                    case "I" -> id = Integer.parseInt(p[1]);
                    case "P" -> {
                        Room r = rooms.get(Integer.parseInt(p[1]));
                        if (r != null) r.portal = Integer.parseInt(p[2]);
                    }
                    case "K" -> {
                        Room r = rooms.get(Integer.parseInt(p[1]));
                        if (r != null) {
                            r.text = p[2].equals("-") ? null : Color.web(p[2]);
                            r.outline = p[3].equals("-") ? null : Color.web(p[3]);
                        }
                    }
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // skip malformed lines
            }
        }
        relayout();
        scrollToCurrent();
    }
}
