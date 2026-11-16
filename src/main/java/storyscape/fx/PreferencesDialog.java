package storyscape.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Window;

/**
 * File ▸ Preferences: fonts and colours for the story pane and the map. Each row starts at the
 * current setting (a theme default shows the theme's colour) and has a Default button that goes
 * back to following the theme. OK saves to {@link Appearance}. FX thread only.
 */
final class PreferencesDialog {
    private PreferencesDialog() {}

    /** One preference row: knows how to reset itself and how to store its value. */
    private interface Row {
        void reset();
        void commit();
    }

    /** Show the dialog; returns true if the user pressed OK (Appearance has been updated and saved). */
    static boolean show(Window owner, boolean dark) {
        List<Row> rows = new ArrayList<>();

        GridPane story = grid();
        int r = 0;
        r = fontRow(story, r, "Header font (room name and score)", rows,
                () -> Appearance.headerFont, Appearance::defaultHeaderFont, f -> Appearance.headerFont = f);
        String bg = dark ? Appearance.DARK_BG : Appearance.LIGHT_BG;
        String fg = dark ? Appearance.DARK_FG : Appearance.LIGHT_FG;
        String accent = dark ? Appearance.DARK_ACCENT : Appearance.LIGHT_ACCENT;
        r = colourRow(story, r, "Background", rows,
                Appearance.storyBackground, bg, c -> Appearance.storyBackground = c);
        r = colourRow(story, r, "Text", rows,
                Appearance.storyText, fg, c -> Appearance.storyText = c);
        colourRow(story, r, "Commands", rows,
                Appearance.commandColour, accent, c -> Appearance.commandColour = c);

        GridPane map = grid();
        r = 0;
        r = fontRow(map, r, "Font", rows,
                () -> Appearance.mapFont, Appearance::defaultMapFont, f -> Appearance.mapFont = f);
        r = colourRow(map, r, "Font color", rows,
                Appearance.mapText, fg, c -> Appearance.mapText = c);
        r = colourRow(map, r, "Background", rows,
                Appearance.mapBackground, bg, c -> Appearance.mapBackground = c);
        r = colourRow(map, r, "Note", rows,
                Appearance.mapNote, Appearance.NOTE_DEFAULT, c -> Appearance.mapNote = c);
        colourRow(map, r, "Active room", rows,
                Appearance.mapActiveRoom, accent, c -> Appearance.mapActiveRoom = c);

        Button resetAll = new Button("Reset All to Defaults");
        resetAll.setOnAction(e -> rows.forEach(Row::reset));
        HBox bottom = new HBox(resetAll);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8,
                section("Story"), story,
                new Separator(),
                section("Map"), map,
                new Separator(),
                bottom);
        content.setPadding(new Insets(12, 16, 8, 16));

        Dialog<ButtonType> dialog = Styles.dialog(new Dialog<>());
        dialog.initOwner(owner);
        dialog.setTitle("Preferences");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return false;
        rows.forEach(Row::commit);
        Appearance.save();
        return true;
    }

    private static Label section(String title) {
        Label l = new Label(title);
        l.getStyleClass().add("zm-pref-section");
        return l;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(6);
        g.setPadding(new Insets(0, 0, 4, 12));
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(220);
        ColumnConstraints control = new ColumnConstraints();
        control.setHgrow(Priority.ALWAYS);
        ColumnConstraints button = new ColumnConstraints();
        button.setHalignment(HPos.RIGHT);
        g.getColumnConstraints().addAll(label, control, button);
        return g;
    }

    // ------------------------------------------------------------------ rows

    /**
     * A colour: picker + Default. {@code saved} is the stored choice (null = follow the theme),
     * {@code themeDefault} the theme's colour that Default goes back to.
     */
    private static int colourRow(GridPane grid, int row, String text, List<Row> rows,
                                 Color saved, String themeDefault, Consumer<Color> store) {
        Color themeColour = Color.web(themeDefault);
        ColorPicker picker = new ColorPicker(saved != null ? saved : themeColour);
        picker.setMaxWidth(Double.MAX_VALUE);
        Color[] choice = {saved};
        picker.setOnAction(e -> choice[0] = picker.getValue());
        Button def = new Button("Default");
        Row rowObj = new Row() {
            @Override public void reset() {
                choice[0] = null;
                picker.setValue(themeColour);
            }

            @Override public void commit() { store.accept(choice[0]); }
        };
        def.setOnAction(e -> rowObj.reset());
        rows.add(rowObj);
        grid.addRow(row, new Label(text), picker, def);
        return row + 1;
    }

    /** A font: family list + size + Default. */
    private static int fontRow(GridPane grid, int row, String text, List<Row> rows,
                               Supplier<Appearance.FontChoice> current, Supplier<Appearance.FontChoice> fallback,
                               Consumer<Appearance.FontChoice> store) {
        Appearance.FontChoice start = current.get() != null ? current.get() : fallback.get();
        ComboBox<String> family = new ComboBox<>();
        family.getItems().addAll(Font.getFamilies());
        if (!family.getItems().contains(start.family())) family.getItems().add(0, start.family());
        family.setValue(start.family());
        family.setVisibleRowCount(16);
        family.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(family, Priority.ALWAYS);
        Spinner<Integer> size = new Spinner<>(6, 48, (int) Math.round(start.size()));
        size.setEditable(true);
        size.setPrefWidth(80);
        HBox controls = new HBox(6, family, size);
        controls.setAlignment(Pos.CENTER_LEFT);

        Appearance.FontChoice[] choice = {current.get()};
        boolean[] quiet = {false};
        Runnable changed = () -> {
            if (!quiet[0] && family.getValue() != null) {
                choice[0] = new Appearance.FontChoice(family.getValue(), size.getValue());
            }
        };
        family.valueProperty().addListener((o, a, b) -> changed.run());
        size.valueProperty().addListener((o, a, b) -> changed.run());

        Button def = new Button("Default");
        Row rowObj = new Row() {
            @Override public void reset() {
                Appearance.FontChoice f = fallback.get();
                quiet[0] = true;
                if (!family.getItems().contains(f.family())) family.getItems().add(0, f.family());
                family.setValue(f.family());
                size.getValueFactory().setValue((int) Math.round(f.size()));
                quiet[0] = false;
                choice[0] = null;
            }

            @Override public void commit() { store.accept(choice[0]); }
        };
        def.setOnAction(e -> rowObj.reset());
        rows.add(rowObj);
        grid.addRow(row, new Label(text), controls, def);
        return row + 1;
    }
}
