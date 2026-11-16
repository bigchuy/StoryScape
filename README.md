# StoryScape

StoryScape is an interactive fiction player: a Z-machine interpreter in Java 25 with a JavaFX
window and a console mode, implemented from the
[Z-Machine Standards Document 1.1](https://inform-fiction.org/zmachine/standards/z1point1/index.html).
Beside the story it keeps an automatic map, your notes, and the game's cover art, manual and hints.

## Open in Visual Studio Code

1. Install **JDK 25** (the current LTS; JavaFX 25 requires it) and the **Extension Pack for Java**
   (`vscjava.vscode-java-pack`; VS Code offers it from `.vscode/extensions.json`). It bundles Maven support.
2. `File > Open Folder...` and choose this project folder. Wait for the Java project to import
   (Maven downloads JavaFX and PDFBox automatically).
3. Press **F5** and pick a launch configuration:
   * **StoryScape GUI (JavaFX, empty window)** - then use *File > Open Story...* or drag a story file onto the window.
   * **StoryScape GUI (JavaFX, prompt for story file)** - starts a story straight away.
   * **StoryScape Console (prompt for story file)** / **(plain mode)** - the console version, run in the integrated terminal.

Command line:

```
mvn javafx:run                                   # GUI, empty window
mvn javafx:run -Djavafx.args="games/story.z5"    # GUI, open a story
mvn package
java -jar target/storyscape-1.0.0.jar games/story.z5            # console version (no JavaFX needed)
java -jar target/storyscape-1.0.0.jar --plain --width=100 games/story.z5
```

Run the unit tests with `mvn test` (or the Testing view in VS Code).

## The JavaFX window

* Menus: *File* (open, restart, save transcript, quit), *View* (theme, text size, proportional text), *Help*.
* Light and dark themes; "Follow System" tracks the OS colour scheme live (`Platform.getPreferences()`).
* Drag-and-drop a `.z3` / `.z5` / `.z8` / `.zblorb` onto the window to play it.
* Command line with history (Up/Down); `read_char` games take keys directly, including cursor and function keys.
* The upper window and the v3 status line are drawn natively (status bar + a character-cell canvas with colours,
  bold, italic and reverse video); the story text scrolls in a wrapped, restyleable lower window.
* Save/restore use native file dialogs (Quetzal `.sav` files). Settings persist between runs.
* Ctrl/Cmd +, -, 0 change the text size.

### The side panel

Tabs beside the story, left to right:

* **Cover Art** - `story.jpg` (or `.jpeg`) from the story file's folder, e.g. `zork1.jpg` for `zork1.z3`.
* **Game Manual** - `story.pdf` from the same folder, shown as a scrolling column of pages fitted to the tab.
* **Hints** - `hints.pdf` from the same folder, shown the same way.
* **Notes** - free text, saved inside your saved games.
* **Map** - rooms are added as you visit them (switch off with *Auto Map*), joined by lines labelled
  with the direction taken. Rooms, lines and labels can be dragged; drag a box to select several rooms.
  Double-click a room to start a new map tab from it; right-click rooms for *Notes...* and *Move*.
  Maps and room notes are saved inside your saved games too (as extra Quetzal chunks other interpreters ignore).

JavaFX version: `pom.xml` pins `javafx.version` to 25.0.2. Change that property to move to a newer JavaFX
(and set `maven.compiler.release` to a matching JDK).

## Layout

| File | Standard section | Purpose |
|------|------------------|---------|
| `Memory` | 1 | Memory map, dynamic/static protection, pristine copy for restart/verify |
| `ZText` | 3 | Z-characters, abbreviations, alphabets, ZSCII/Unicode, dictionary encoding |
| `Interpreter` | 4-7, 10, 14, 15 | Instruction decoding, calls, streams, input, every opcode of v1-5/7/8 |
| `ObjectTable` | 12 | Object tree, attributes, properties |
| `Lexer` | 13 | Tokenising and dictionary lookup (also user dictionaries) |
| `Quetzal` | (Quetzal 1.4) | Standard saved-game format |
| `ZIO` / `ConsoleIO` | 7-8 | I/O interface and ANSI terminal implementation |
| `fx/FxApp`, `fx/FxIO`, `fx/GameView`, `fx/UpperGrid` | 7-8 | JavaFX window: menus, theming, canvas upper window, dialogs, interpreter thread |
| `fx/SidePanel`, `fx/MapView`, `fx/CoverArtView`, `fx/ManualView` | | Side panel: map, notes, cover art, manual and hints |
| `Main` | | CLI, Blorb (`ZCOD`) unwrapping |

To embed the machine in another UI, implement `ZIO` and call
`new Interpreter(bytes, io, path).run()`.

## Supported

* Versions 1, 2, 3, 4, 5, 7 and 8 (all opcodes of those versions, including `save_undo`/`restore_undo`,
  extended save/restore, `catch`/`throw`, `print_unicode`, custom alphabets and Unicode tables).
* Output streams 1, 2 (transcript file), 3 (memory tables); split-window status line and upper window.
* Save/restore in Quetzal format; `verify` checksum; `restart`.
* Colours, bold, italic and reverse video (ANSI in the console; native in the JavaFX window).

## Not supported (yet)

* **Version 6** (graphics, mouse, menus, pictures) is rejected with a clear error.
* Timed input (`time`/`routine` operands of `read`/`read_char` are ignored; the header bit is left clear).
* In the console version `read_char` is line-buffered by the terminal (type the key, then Enter); the JavaFX window reads keys directly.
* Input stream 1 (command-file playback) and output stream 4; sound other than the terminal bell.
* Terminating-character tables.
* In the JavaFX window, reverse video in the *lower* window is shown in the accent colour (the upper window does true reverse video), and background colours apply to the upper window only.
