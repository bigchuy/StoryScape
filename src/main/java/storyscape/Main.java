package storyscape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Command-line entry point: java -jar storyscape.jar [options] story-file */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        boolean ansi = !"dumb".equals(System.getenv("TERM"));
        int width = 80, height = 25;
        String file = null;
        for (String a : args) {
            if (a.equals("--plain")) ansi = false;
            else if (a.startsWith("--width=")) width = Integer.parseInt(a.substring(8));
            else if (a.startsWith("--height=")) height = Integer.parseInt(a.substring(9));
            else if (a.equals("--gui")) { launchGui(args); return; }
            else if (a.equals("-h") || a.equals("--help")) { usage(); return; }
            else file = a;
        }
        if (file == null) {
            usage();
            System.exit(1);
        }
        Path path = Paths.get(file);
        byte[] data;
        try {
            data = unwrapBlorb(Files.readAllBytes(path));
        } catch (IOException | ZMachineException e) {
            System.err.println("Cannot load " + file + ": " + e.getMessage());
            System.exit(2);
            return;
        }

        ConsoleIO io = new ConsoleIO(ansi, width, height);
        int status = 0;
        try {
            Interpreter z = new Interpreter(data, io, path);
            z.run();
        } catch (ZMachineException e) {
            io.flush();
            System.err.println("\n[Z-machine error: " + e.getMessage() + "]");
            status = 2;
        } finally {
            io.close();
        }
        System.exit(status);
    }

    /** Start the JavaFX window (loaded reflectively so the console version needs no JavaFX). */
    private static void launchGui(String[] args) {
        String[] rest = java.util.Arrays.stream(args).filter(x -> !x.equals("--gui")).toArray(String[]::new);
        try {
            Class.forName("storyscape.fx.FxLauncher").getMethod("main", String[].class).invoke(null, (Object) rest);
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("The JavaFX window needs the JavaFX libraries on the class path.");
            System.err.println("Run it with: mvn javafx:run   (or the 'StoryScape GUI' launch configuration in VS Code)");
        }
    }

    private static void usage() {
        System.out.println("StoryScape - interactive fiction (Z-machine) player");
        System.out.println("Usage: java -jar storyscape.jar [--plain] [--width=N] [--height=N] story-file");
        System.out.println("  --plain   no ANSI escape codes (upper window is hidden)");
        System.out.println("  --gui     open the JavaFX window instead (needs JavaFX on the class path)");
    }

    /** If the file is a Blorb container, return its executable (ZCOD) chunk. */
    public static byte[] unwrapBlorb(byte[] d) {
        if (d.length < 12 || d[0] != 'F' || d[1] != 'O' || d[2] != 'R' || d[3] != 'M'
                || d[8] != 'I' || d[9] != 'F' || d[10] != 'R' || d[11] != 'S') {
            return d;
        }
        int pos = 12;
        while (pos + 8 <= d.length) {
            int len = ((d[pos + 4] & 0xFF) << 24) | ((d[pos + 5] & 0xFF) << 16)
                    | ((d[pos + 6] & 0xFF) << 8) | (d[pos + 7] & 0xFF);
            if (d[pos] == 'Z' && d[pos + 1] == 'C' && d[pos + 2] == 'O' && d[pos + 3] == 'D') {
                return java.util.Arrays.copyOfRange(d, pos + 8, Math.min(d.length, pos + 8 + len));
            }
            pos += 8 + len + (len & 1);
        }
        throw new ZMachineException("Blorb file contains no Z-code (ZCOD) chunk");
    }

    /**
     * The story's title from a Blorb file's iFiction metadata (IFmd chunk), or null when the file
     * isn't a Blorb or carries no title.
     */
    public static String blorbTitle(byte[] d) {
        if (d.length < 12 || d[0] != 'F' || d[1] != 'O' || d[2] != 'R' || d[3] != 'M'
                || d[8] != 'I' || d[9] != 'F' || d[10] != 'R' || d[11] != 'S') {
            return null;
        }
        int pos = 12;
        while (pos + 8 <= d.length) {
            int len = ((d[pos + 4] & 0xFF) << 24) | ((d[pos + 5] & 0xFF) << 16)
                    | ((d[pos + 6] & 0xFF) << 8) | (d[pos + 7] & 0xFF);
            if (len < 0) return null;
            if (d[pos] == 'I' && d[pos + 1] == 'F' && d[pos + 2] == 'm' && d[pos + 3] == 'd') {
                int end = (int) Math.min(d.length, (long) pos + 8 + len);
                String xml = new String(d, pos + 8, end - pos - 8, java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("<title>(.*?)</title>", java.util.regex.Pattern.DOTALL).matcher(xml);
                if (!m.find()) return null;
                String t = m.group(1).replaceAll("\\s+", " ").trim()
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&apos;", "'").replace("&amp;", "&");
                return t.isEmpty() ? null : t;
            }
            pos += 8 + len + (len & 1);
        }
        return null;
    }
}
