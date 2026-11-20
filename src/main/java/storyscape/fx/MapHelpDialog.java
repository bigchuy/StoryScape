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
            "No line is drawn after Restart, Restore or Undo, since you didn't walk there.",
            "With Auto Map unticked you can add rooms yourself: right-click an empty spot and "
                    + "choose Add Room…. To join the new room to an existing one, select that room "
                    + "first and choose Add Room Linked to “…”; you can give the direction each way.",
            "To join two rooms already on the map, untick Auto Map, select both (Shift-click or "
                    + "Ctrl-click the second), right-click one of them and choose Connect Rooms…, "
                    + "giving the direction each way if you like. If they are already joined, the "
                    + "menu offers Edit Connection… instead.",
            "Also with Auto Map unticked, right-click a room and choose Add Loop… for an exit that "
                    + "leads back into the same room. It is drawn as a small loop with an arrow and "
                    + "one direction label; drag it to move it round the room, double-click it to "
                    + "put it back.",
            "To delete, untick Auto Map, then right-click a room (or a selection) and choose Delete "
                    + "Room, or right-click a line, loop or label and choose Delete Connection "
                    + "(Delete Loop). Deleting a room deletes its connections too.",
            "To change a connection's direction labels, untick Auto Map, right-click the line, "
                    + "loop or one of its labels and choose Edit Connection… (Edit Loop…). Pick a "
                    + "direction or type any word; leave it blank for no label."},
        {"Arranging rooms and lines",
            "Drag a room to move it.",
            "Click a room to select it. Shift-click or Ctrl-click (Cmd-click on a Mac) adds a room "
                    + "to the selection or removes it.",
            "Drag on empty space to draw a selection box around several rooms, then drag any "
                    + "selected room to move them all together.",
            "Drag a line to bend it. Double-click a line to straighten it again.",
            "Drag a direction label to move it. Double-click it to put it back.",
            "Right-click a room and choose Edit Room… to give it its own text and outline "
                    + "colours, for example to mark dangerous or unfinished rooms. With several "
                    + "rooms selected, all of them change. Tick Usual to go back to the map's "
                    + "colours. The room you are in keeps its highlight so its name stays readable, "
                    + "but its own outline still shows."},
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
            "Your maps, room notes and the Notes tab are kept in their own file beside the story "
                    + "(for zork1.z3 it is zork1.storyscape), separate from your saved games.",
            "That file is written whenever you save the game, after each move, every 30 seconds "
                    + "and when you close the story or StoryScape. It is loaded whenever the story "
                    + "opens, whether or not you restore a game, and restoring doesn't change it.",
            "Restart Story keeps your maps and notes.",
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
