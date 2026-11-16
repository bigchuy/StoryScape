package storyscape.fx;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * The Cover Art tab: shows the .jpg (or .jpeg) beside the story file with the same base name,
 * e.g. zork1.jpg for zork1.z3, at its original size: centred when it is smaller than the tab,
 * scrollable (or draggable) when it is larger. Without one, explains how to add it. FX thread only.
 */
final class CoverArtView extends StackPane {
    private static final double PAD = 8;

    private final ImageView image = new ImageView();
    private final Label message = new Label();
    private final MissingFilePanel missing = new MissingFilePanel();   // how to add a cover

    CoverArtView() {
        getStyleClass().add("zm-cover");
        message.getStyleClass().add("zm-placeholder");
        message.setWrapText(true);
        image.setPreserveRatio(true);
        image.setSmooth(true);

        // the holder is never smaller than the picture (so a large one scrolls) and otherwise
        // fills the viewport (so a small one sits in the middle)
        StackPane holder = new StackPane(image);
        holder.getStyleClass().add("zm-cover");
        holder.setPadding(new javafx.geometry.Insets(PAD));
        holder.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        ScrollPane scroll = new ScrollPane(holder);
        scroll.getStyleClass().add("zm-scroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        setMinSize(0, 0);
        getChildren().addAll(scroll, message, missing);
        show(null);
    }

    /** Look for the cover beside {@code story}; null clears the tab. */
    void show(Path story) {
        image.setImage(null);
        missing.setVisible(false);
        if (story == null) {
            message.setText("Open a story to see its cover art.");
            message.setVisible(true);
            return;
        }
        Path file = StoryFiles.sibling(story, "jpg", "jpeg");
        if (file == null) {
            message.setVisible(false);
            missing.show(help(story), story, () -> show(story));
            missing.setVisible(true);
            return;
        }
        message.setVisible(false);
        Image img = new Image(file.toUri().toString(), true);   // loads in the background
        img.errorProperty().addListener((obs, was, failed) -> {
            if (failed && image.getImage() == img) {
                image.setImage(null);
                message.setText("Cannot read " + file.getFileName() + ".");
                message.setVisible(true);
            }
        });
        image.setImage(img);
    }

    private static MissingFilePanel.Help help(Path story) {
        String base = StoryFiles.baseName(story);
        return new MissingFilePanel.Help(
                "No cover art yet",
                "This tab shows a picture for the story, such as its original box art, when there is "
                        + "a JPEG image with the same name in the story's folder.",
                "Find or make a cover picture as a JPEG image (.jpg or .jpeg). Any size works: it is "
                        + "shown at its original size, and a large one can be scrolled or dragged.",
                base + ".jpg",
                "The picture's name must match the story file's name: " + story.getFileName()
                        + " goes with " + base + ".jpg (or " + base + ".jpeg).");
    }

    /** Small helpers for finding files that sit beside the story. */
    static final class StoryFiles {
        private StoryFiles() {}

        /** "zork1" for ".../zork1.z3". */
        static String baseName(Path story) {
            String name = story.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        /** The file called {@code name} in the story's folder (also tried in upper case), or null. */
        static Path named(Path story, String name) {
            Path dir = story.toAbsolutePath().getParent();
            if (dir == null) return null;
            for (String n : new String[] {name, name.toUpperCase(java.util.Locale.ROOT)}) {
                Path p = dir.resolve(n);
                if (Files.isRegularFile(p)) return p;
            }
            return null;
        }

        /** The first existing file beside {@code story} with its base name and one of these extensions. */
        static Path sibling(Path story, String... extensions) {
            Path dir = story.toAbsolutePath().getParent();
            if (dir == null) return null;
            String base = baseName(story);
            for (String ext : extensions) {
                for (String e : new String[] {ext, ext.toUpperCase(java.util.Locale.ROOT)}) {
                    Path p = dir.resolve(base + "." + e);
                    if (Files.isRegularFile(p)) return p;
                }
            }
            return null;
        }
    }
}
