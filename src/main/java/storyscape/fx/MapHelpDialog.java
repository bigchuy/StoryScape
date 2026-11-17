package storyscape.fx;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/** Help ▸ Map Help: a modal dialog explaining what the Map tab can do. FX thread only. */
final class MapHelpDialog {
    private MapHelpDialog() {}

    /** Each section: a heading followed by its points. */
    private static final String[][] SECTIONS = {
        {"Building the map",
            "With Auto Map ticked, every new room you visit is added to the map as a box, joined by a "
                    + "line to the room you came from.",
            "The direction you typed (n, se, up, in…) is written beside the line, next to the room it "
                    + "leads out of.",
            "New rooms are placed by compass direction (north is up, east is right) when there is "
                    + "space; otherwise they go in the nearest free spot.",
            "The room you are in is highlighted.",
            "Untick Auto Map to stop adding rooms and lines, for example while wandering a maze. "
                    + "Rooms already on a map are still highlighted when you enter them.",
            "No line is drawn after Restart, Restore or Undo, since you didn't walk there."},
        {"Arranging rooms and lines",
            "Drag a room to move it.",
            "Click a room to select it. Shift-click or Ctrl-click (Cmd-click on a Mac) adds a room "
                    + "to the selection or removes it.",
            "Drag on empty space to draw a selection box around several rooms, then drag any "
                    + "selected room to move them all together.",
            "Drag a line to bend it. Double-click a line to straighten it again.",
            "Drag a direction label to move it. Double-click it to put it back."},
        {"Moving around and zooming",
            "Drag with the right or middle mouse button to pan the map, or use the scroll bars.",
            "Zoom with the − and + buttons, Ctrl + scroll wheel (Cmd + scroll on a Mac) or a "
                    + "trackpad pinch. Reset returns to 100%.",
            "The zoom applies to every map at once."},
        {"Several maps",
            "Double-click a room to start a new map from it: useful for a separate area such as "
                    + "an underground level or a maze. Each map then gets its own tab inside the "
                    + "Map tab.",
            "The room you double-clicked becomes a circle on both maps. Click a circle to jump to "
                    + "the other map.",
            "New rooms are added to the selected map when the room you came from is on it. If it "
                    + "isn't, StoryScape switches to the first map that has the room you came from "
                    + "and adds the new room there, joined to it.",
            "Right-click a room (or a selection) and choose Move to send it to another map. Its "
                    + "lines go with it. A line to a room that stays behind keeps that room at its "
                    + "far end as a circle on the new map, and the room left behind becomes a circle "
                    + "too: click either to jump between the two maps.",
            "Double-click a map's tab title to rename it (or right-click the tab ▸ Rename…).",
            "Click × on a map's tab to close it; its rooms are discarded. The last map can't be "
                    + "closed, and with only one map left the tabs disappear."},
        {"Room notes",
            "Right-click a room and choose Notes… to write a note about it.",
            "A room with a note shows a small sticky-note icon; click the icon to read or edit it.",
            "Delete all of a note's text to remove it.",
            "A note belongs to the room, so it shows on every map that has that room."},
        {"Saving and appearance",
            "Your maps, room notes and the Notes tab are stored inside your saved games and "
                    + "come back when you restore.",
            "Change the map's font and colours (text, background, notes and the current room) "
                    + "in File ▸ Preferences."},
    };

    static void show(Window owner) {
        VBox body = new VBox(4);
        body.setPadding(new Insets(4, 16, 12, 4));
        for (String[] section : SECTIONS) {
            Label heading = new Label(section[0]);
            heading.getStyleClass().add("zm-pref-section");
            VBox.setMargin(heading, new Insets(body.getChildren().isEmpty() ? 0 : 12, 0, 2, 0));
            body.getChildren().add(heading);
            for (int i = 1; i < section.length; i++) {
                Label point = new Label("•  " + section[i]);
                point.setWrapText(true);
                point.setMaxWidth(Double.MAX_VALUE);
                VBox.setMargin(point, new Insets(0, 0, 0, 8));
                body.getChildren().add(point);
            }
        }
        ScrollPane scroll = new ScrollPane(body);
        scroll.getStyleClass().add("zm-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefSize(560, 480);

        Dialog<Void> dialog = Styles.dialog(new Dialog<>());
        if (owner != null) dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Map Help");
        dialog.setHeaderText("Using the Map");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
