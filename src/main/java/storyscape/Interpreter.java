package storyscape;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The Z-machine CPU: instruction decoding (Standard section 4), routine calls (5, 6),
 * output streams (7), and the opcode set (14, 15) for versions 1-5, 7 and 8.
 */
public final class Interpreter {
    private static final int OP2 = 0, OP1 = 1, OP0 = 2, VAR = 3, EXT = 4;
    private static final String[] TYPE_NAMES = {"2OP", "1OP", "0OP", "VAR", "EXT"};

    private final Memory m;
    private final ZText text;
    private final ObjectTable objs;
    private final Lexer lexer;
    private final ZIO io;
    private final int version;
    private final String baseName;
    private final boolean fancy;
    private volatile Path startSave;          // saved game to restore as the story starts, or null

    private int pc;
    private int instrStart;
    private int globalsBase;
    private final ArrayList<Frame> frames = new ArrayList<>();
    private Frame cur;
    private volatile boolean running;

    private final int[] ops = new int[8];
    private int nops;

    private Random rng = new Random();
    private int currentFont = 1;

    private boolean screenOn = true;
    private PrintWriter transcript;
    private final ArrayDeque<MemTable> memStreams = new ArrayDeque<>();

    private int lastLocation = -1;
    private boolean jumped = true;        // next location change came from restart/restore/undo

    private record Undo(byte[] dynamic, List<Frame> frames, int pc) {}
    private Undo undo;

    private static final class MemTable {
        final int addr;
        int count;
        MemTable(int addr) { this.addr = addr; }
    }

    public Interpreter(byte[] story, ZIO io, Path storyPath) {
        int v = story.length > 0 ? story[0] & 0xFF : 0;
        if (v < 1 || v > 8) throw new ZMachineException("Not a Z-code story file (version byte " + v + ")");
        if (v == 6) throw new ZMachineException("Version 6 (graphical) story files are not supported by this interpreter");
        this.version = v;
        this.io = io;
        this.m = new Memory(story);
        this.text = new ZText(m, v);
        this.objs = new ObjectTable(m, v);
        this.lexer = new Lexer(m, text, v);
        this.fancy = io.supportsStyles();
        String name = storyPath == null ? "story" : storyPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        Path parent = storyPath == null ? null : storyPath.toAbsolutePath().getParent();
        this.baseName = parent == null ? name : parent.resolve(name).toString();
    }

    /** True if {@code save} is a readable saved game made from this story (call before {@link #run()}). */
    public boolean isSaveFor(Path save) {
        try {
            Quetzal.read(Files.readAllBytes(save), m);   // checks release, serial and checksum; changes nothing
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Restore {@code save} (a saved game for this story) once the story has loaded: it runs its
     * opening (banner, title, screen set-up) as usual, and the save is restored in place of the
     * first input it asks for.
     */
    public void restoreOnStart(Path save) {
        this.startSave = save;
    }

    // ================================================================ lifecycle

    /** Ask a running interpreter to stop after the current instruction (thread-safe). */
    public void stop() {
        running = false;
    }

    public void run() {
        io.init(version);
        resetMachine(false);
        running = true;
        try {
            while (running) step();
        } catch (ZMachineException e) {
            throw new ZMachineException(String.format("%s (instruction at %05x)", e.getMessage(), instrStart));
        } finally {
            closeTranscript();
            io.flush();
        }
    }

    private void resetMachine(boolean preserveFlags) {
        int keep = preserveFlags ? m.u16(0x10) & 3 : 0;
        m.reset();
        text.refresh();
        configureHeader();
        m.set16(0x10, (m.u16(0x10) & ~3) | keep);
        globalsBase = m.u16(0x0C);
        frames.clear();
        Frame base = new Frame();
        frames.add(base);
        cur = base;
        pc = m.u16(0x06);
        memStreams.clear();
        screenOn = true;
        lastLocation = -1;
        jumped = true;
    }

    /** Fill in the header fields owned by the interpreter (Standard section 11). */
    private void configureHeader() {
        int f1 = m.u8(0x01);
        if (version <= 3) {
            f1 &= ~(0x08 | 0x10 | 0x40);
            if (fancy) f1 |= 0x20; else f1 &= ~0x20;
        } else {
            f1 &= ~(0x01 | 0x02 | 0x04 | 0x08 | 0x10 | 0x20 | 0x80);
            if (version >= 5 && fancy) f1 |= 0x01;
            if (fancy) f1 |= 0x04 | 0x08;
            f1 |= 0x10;
        }
        m.set8(0x01, f1);
        if (version >= 4) {
            m.set8(0x1E, 6);
            m.set8(0x1F, 'A');
            m.set8(0x20, Math.min(255, io.height()));
            m.set8(0x21, Math.min(255, io.width()));
        }
        if (version >= 5) {
            m.set16(0x22, io.width());
            m.set16(0x24, io.height());
            m.set8(0x26, 1);
            m.set8(0x27, 1);
            m.set8(0x2C, 2);
            m.set8(0x2D, 9);
            m.set8(0x32, 1);
            m.set8(0x33, 1);
            m.set16(0x10, m.u16(0x10) & ~(0x08 | 0x20 | 0x80 | 0x100));
        }
    }

    // ================================================================ fetch / variables

    private int fetch8() { return m.u8(pc++); }

    private int fetch16() {
        int v = m.u16(pc);
        pc += 2;
        return v;
    }

    private static int s(int v) { return (short) v; }

    private int readVar(int v) {
        if (v == 0) return cur.pop();
        if (v < 16) return local(v);
        return m.u16(globalsBase + 2 * (v - 16));
    }

    private int local(int v) {
        if (v - 1 >= cur.locals.length) throw new ZMachineException("Reference to nonexistent local variable " + v);
        return cur.locals[v - 1];
    }

    private void writeVar(int v, int value) {
        value &= 0xFFFF;
        if (v == 0) cur.push(value);
        else if (v < 16) {
            local(v);
            cur.locals[v - 1] = value;
        } else m.set16(globalsBase + 2 * (v - 16), value);
    }

    /** Read a variable "by reference": the stack is peeked, not popped. */
    private int peekVar(int v) { return v == 0 ? cur.peek() : readVar(v); }

    private void pokeVar(int v, int value) {
        if (v == 0) cur.poke(value); else writeVar(v, value);
    }

    private void store(int value) { writeVar(fetch8(), value); }

    // ================================================================ branches, calls

    private void branch(boolean condition) {
        int b = fetch8();
        boolean onTrue = (b & 0x80) != 0;
        int offset;
        if ((b & 0x40) != 0) {
            offset = b & 0x3F;
        } else {
            int o = ((b & 0x3F) << 8) | fetch8();
            offset = (o & 0x2000) != 0 ? o - 0x4000 : o;
        }
        if (condition == onTrue) {
            if (offset == 0) doReturn(0);
            else if (offset == 1) doReturn(1);
            else pc = pc + offset - 2;
        }
    }

    private int unpackRoutine(int p) {
        switch (version) {
            case 1: case 2: case 3: return 2 * p;
            case 4: case 5: return 4 * p;
            case 7: return 4 * p + 8 * m.u16(0x28);
            default: return 8 * p;
        }
    }

    private int unpackString(int p) {
        switch (version) {
            case 1: case 2: case 3: return 2 * p;
            case 4: case 5: return 4 * p;
            case 7: return 4 * p + 8 * m.u16(0x2A);
            default: return 8 * p;
        }
    }

    /** Call ops[0] with ops[1..nops-1] as arguments; storeVar < 0 discards the result. */
    private void doCall(int storeVar) {
        int packed = ops[0];
        if (packed == 0) {
            if (storeVar >= 0) writeVar(storeVar, 0);
            return;
        }
        if (frames.size() > 20000) throw new ZMachineException("Stack overflow (call depth)");
        int addr = unpackRoutine(packed);
        int nargs = nops - 1;
        Frame f = new Frame();
        f.returnPC = pc;
        f.storeVar = storeVar;
        f.argCount = nargs;
        int n = m.u8(addr++);
        if (n > 15) throw new ZMachineException("Routine with " + n + " locals at " + Integer.toHexString(addr - 1));
        f.locals = new int[n];
        if (version <= 4) {
            for (int i = 0; i < n; i++) {
                f.locals[i] = m.u16(addr);
                addr += 2;
            }
        }
        for (int i = 0; i < Math.min(nargs, n); i++) f.locals[i] = ops[i + 1];
        frames.add(f);
        cur = f;
        pc = addr;
    }

    private void doReturn(int value) {
        if (frames.size() <= 1) throw new ZMachineException("Return from the main routine");
        Frame f = frames.remove(frames.size() - 1);
        cur = frames.get(frames.size() - 1);
        pc = f.returnPC;
        if (f.storeVar >= 0) writeVar(f.storeVar, value);
    }

    // ================================================================ output

    private void out(CharSequence cs) {
        if (!memStreams.isEmpty()) {
            MemTable t = memStreams.peek();
            for (int i = 0; i < cs.length(); i++) {
                m.set8(t.addr + 2 + t.count, text.charToZscii(cs.charAt(i)));
                t.count++;
            }
            return;
        }
        String str = cs.toString();
        if (screenOn) io.print(str);
        if (transcript != null) transcript.print(str);
    }

    private void printZString(int addr) {
        StringBuilder sb = new StringBuilder();
        text.decode(addr, sb);
        out(sb);
    }

    private void openTranscript() {
        if (transcript != null) return;
        try {
            transcript = new PrintWriter(Files.newBufferedWriter(Paths.get(baseName + ".transcript.txt"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            m.set16(0x10, m.u16(0x10) | 1);
        } catch (IOException | RuntimeException e) {
            transcript = null;
            m.set16(0x10, m.u16(0x10) & ~1);
        }
    }

    private void closeTranscript() {
        if (transcript != null) {
            transcript.close();
            transcript = null;
        }
        m.set16(0x10, m.u16(0x10) & ~1);
    }

    private void syncTranscript() {
        boolean want = (m.u16(0x10) & 1) != 0;
        if (want && transcript == null) openTranscript();
        else if (!want && transcript != null) closeTranscript();
        if (transcript != null) transcript.flush();
    }

    private void outputStream(int n) {
        switch (Math.abs(n)) {
            case 1: screenOn = n > 0; break;
            case 2: if (n > 0) openTranscript(); else closeTranscript(); break;
            case 3:
                if (n > 0) {
                    if (memStreams.size() >= 16) throw new ZMachineException("Too many nested memory streams");
                    memStreams.push(new MemTable(ops[1]));
                } else if (!memStreams.isEmpty()) {
                    MemTable t = memStreams.pop();
                    m.set16(t.addr, t.count);
                }
                break;
            default: break;                    // stream 4 (command script) is not supported
        }
    }

    // ================================================================ input

    private void showStatusLine() {
        int loc = m.u16(globalsBase);
        StringBuilder name = new StringBuilder();
        if (loc != 0) text.decode(objs.shortNameAddr(loc), name);
        int a = m.u16(globalsBase + 2);
        int b = m.u16(globalsBase + 4);
        String right;
        if (version == 3 && (m.u8(0x01) & 0x02) != 0) {
            int h = a % 24;
            right = String.format("Time: %d:%02d %s ", h % 12 == 0 ? 12 : h % 12, b, h < 12 ? "AM" : "PM");
        } else {
            right = String.format("Score: %d  Moves: %d ", s(a), s(b));
        }
        io.showStatus(" " + name, right);
    }

    /**
     * Tell the front-end where the player is. Global 0 holds the location in every v1-3 game and,
     * by library convention, in most Inform games of later versions; anything that doesn't look
     * like a named object is ignored.
     */
    private void reportLocation() {
        boolean travelled = !jumped;
        jumped = false;
        int loc = m.u16(globalsBase);
        if (loc == lastLocation) return;
        lastLocation = loc;
        String name = objectName(loc);
        if (name != null) io.locationChanged(loc, name, travelled);
    }

    private String objectName(int obj) {
        try {
            if (!objs.plausible(obj)) return null;
            StringBuilder sb = new StringBuilder();
            text.decode(objs.shortNameAddr(obj), sb);
            String name = sb.toString().strip();
            if (name.isEmpty() || name.length() > 80) return null;
            for (int i = 0; i < name.length(); i++) {
                if (Character.isISOControl(name.charAt(i))) return null;
            }
            return name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void doRead() {
        if (restorePending()) return;
        int textBuf = ops[0];
        int parseBuf = nops > 1 ? ops[1] : 0;
        int storeVar = version >= 5 ? fetch8() : -1;
        if (version <= 3) showStatusLine();
        reportLocation();
        syncTranscript();
        int cap = m.u8(textBuf);
        if (version <= 4) cap -= 1;
        int pre = version >= 5 ? m.u8(textBuf + 1) : 0;
        String line = io.readLine();
        if (line == null) {
            running = false;
            return;
        }
        line = line.toLowerCase(Locale.ROOT);
        if (transcript != null) transcript.println(line);
        int n = Math.max(0, Math.min(line.length(), cap - pre));
        int base = version >= 5 ? textBuf + 2 + pre : textBuf + 1;
        for (int i = 0; i < n; i++) m.set8(base + i, text.charToZscii(line.charAt(i)));
        if (version >= 5) m.set8(textBuf + 1, pre + n);
        else m.set8(base + n, 0);
        if (parseBuf != 0) lexer.tokenise(textBuf, parseBuf, 0, false);
        if (storeVar >= 0) writeVar(storeVar, 13);
    }

    private void doReadChar() {
        if (restorePending()) return;
        int storeVar = fetch8();
        syncTranscript();
        int c = io.readChar();
        if (c < 0) {
            running = false;
            return;
        }
        // 8, 27 and 129-154 (cursor and function keys) are already ZSCII control codes
        int z = (c == 13 || c == 8 || c == 27 || (c >= 129 && c <= 154)) ? c
                : (c >= 32 && c <= 126) ? c : text.charToZscii((char) c);
        writeVar(storeVar, z);
    }

    // ================================================================ save / restore / undo

    private String askFilename(String suggested, boolean prompt, boolean forSave) {
        String def = suggested != null ? suggested : baseName + ".sav";
        if (!prompt && suggested != null) return def;
        return io.chooseFile(forSave, def);
    }

    private String readNameOperand(int addr) {
        int len = m.u8(addr);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append((char) m.u8(addr + 1 + i));
        return sb.toString();
    }

    private boolean saveGame(String suggested, boolean prompt) {
        String file = askFilename(suggested, prompt, true);
        if (file == null) return false;
        try {
            Files.write(Paths.get(file), Quetzal.write(m, frames, pc, io.extraSaveChunks()));
            return true;
        } catch (IOException | RuntimeException e) {
            io.print("\n[Save failed: " + e.getMessage() + "]\n");
            return false;
        }
    }

    private boolean restoreGame(String suggested, boolean prompt) {
        String file = askFilename(suggested, prompt, false);
        if (file == null) return false;
        try {
            Quetzal.Saved sv = Quetzal.read(Files.readAllBytes(Paths.get(file)), m);
            applyState(sv.dynamic(), sv.frames(), sv.pc());
            io.restoreExtraChunks(sv.extras());
            return true;
        } catch (IOException | RuntimeException e) {
            io.print("\n[Restore failed: " + e.getMessage() + "]\n");
            return false;
        }
    }

    /**
     * Called as the story first asks for input: if a save is waiting ({@link #restoreOnStart}),
     * restore it instead and return true, abandoning the read. The saved PC points just past the
     * save instruction's operands, at its branch or store byte, so finishing it the way a
     * successful restore does lets the game carry on (usually printing "Ok."). If the file can't
     * be restored, returns false and the read goes ahead, so the game continues from its start.
     */
    private boolean restorePending() {
        Path save = startSave;
        if (save == null) return false;
        startSave = null;
        try {
            Quetzal.Saved sv = Quetzal.read(Files.readAllBytes(save), m);
            applyState(sv.dynamic(), sv.frames(), sv.pc());
            io.restoreExtraChunks(sv.extras());
        } catch (ZMachineHalt halt) {
            throw halt;
        } catch (IOException | RuntimeException e) {
            io.print("\n[Restore of " + save.getFileName() + " failed: " + e.getMessage() + "]\n");
            return false;
        }
        io.print("\n[Restored " + save.getFileName() + "]\n\n");
        if (version <= 3) branch(true);
        else store(2);
        return true;
    }

    private void applyState(byte[] dynamic, List<Frame> saved, int newPc) {
        int keep = m.u16(0x10) & 3;
        m.restoreDynamic(dynamic);
        text.refresh();
        frames.clear();
        for (Frame f : saved) frames.add(f.copy());
        cur = frames.get(frames.size() - 1);
        pc = newPc;
        configureHeader();
        m.set16(0x10, (m.u16(0x10) & ~3) | keep);
        lastLocation = -1;
        jumped = true;
    }

    private List<Frame> copyFrames() {
        List<Frame> r = new ArrayList<>();
        for (Frame f : frames) r.add(f.copy());
        return r;
    }

    private void opSave() {
        if (version <= 3) branch(saveGame(null, true));
        else if (version == 4) store(saveGame(null, true) ? 1 : 0);
        else unsupported(OP0, 5);
    }

    private void opRestore() {
        if (version <= 3) branch(restoreGame(null, true));
        else if (version == 4) store(restoreGame(null, true) ? 2 : 0);
        else unsupported(OP0, 6);
    }

    private void extSave() {
        String name = nops > 2 && ops[2] != 0 ? readNameOperand(ops[2]) : null;
        boolean prompt = nops > 3 ? ops[3] != 0 : true;
        if (nops == 0) {
            store(saveGame(null, true) ? (1) : 0);
            return;
        }
        String file = askFilename(name, prompt, true);
        int result = 0;
        if (file != null) {
            try {
                byte[] b = new byte[ops[1]];
                for (int i = 0; i < b.length; i++) b[i] = (byte) m.u8(ops[0] + i);
                Files.write(Paths.get(file), b);
                result = 1;
            } catch (IOException | RuntimeException e) {
                io.print("\n[Save failed: " + e.getMessage() + "]\n");
            }
        }
        store(result);
    }

    private void extRestore() {
        if (nops == 0) {
            store(restoreGame(null, true) ? 2 : 0);
            return;
        }
        String name = nops > 2 && ops[2] != 0 ? readNameOperand(ops[2]) : null;
        boolean prompt = nops > 3 ? ops[3] != 0 : true;
        String file = askFilename(name, prompt, false);
        int result = 0;
        if (file != null) {
            try {
                byte[] b = Files.readAllBytes(Paths.get(file));
                int n = Math.min(b.length, ops[1]);
                for (int i = 0; i < n; i++) m.set8(ops[0] + i, b[i] & 0xFF);
                result = n;
            } catch (IOException | RuntimeException e) {
                io.print("\n[Restore failed: " + e.getMessage() + "]\n");
            }
        }
        store(result);
    }

    private void extSaveUndo() {
        int at = pc;
        int sv = fetch8();
        undo = new Undo(m.dynamicCopy(), copyFrames(), at);
        writeVar(sv, 1);
    }

    private void extRestoreUndo() {
        int sv = fetch8();
        if (undo == null) {
            writeVar(sv, 0);
            return;
        }
        applyState(undo.dynamic(), undo.frames(), undo.pc());
        store(2);
    }

    private void opRestart() {
        resetMachine(true);
    }

    private void opVerify() {
        int len = m.u16(0x1A) * (version <= 3 ? 2 : version <= 5 ? 4 : 8);
        byte[] story = m.original();
        if (len == 0 || len > story.length) len = story.length;
        int sum = 0;
        for (int i = 0x40; i < len; i++) sum = (sum + (story[i] & 0xFF)) & 0xFFFF;
        branch(sum == m.u16(0x1C));
    }

    // ================================================================ misc helpers

    private void copyTable() {
        int first = ops[0], second = ops[1], size = s(ops[2]);
        int n = Math.abs(size);
        if (second == 0) {
            for (int i = 0; i < n; i++) m.set8(first + i, 0);
        } else if (size > 0 && first < second) {
            for (int i = n - 1; i >= 0; i--) m.set8(second + i, m.u8(first + i));
        } else {
            for (int i = 0; i < n; i++) m.set8(second + i, m.u8(first + i));
        }
    }

    private void scanTable() {
        int x = ops[0], table = ops[1], len = ops[2];
        int form = nops > 3 ? ops[3] : 0x82;
        boolean words = (form & 0x80) != 0;
        int field = Math.max(1, form & 0x7F);
        int found = 0;
        for (int i = 0; i < len; i++) {
            int a = table + i * field;
            if ((words ? m.u16(a) : m.u8(a)) == x) {
                found = a;
                break;
            }
        }
        writeVar(fetch8(), found);
        branch(found != 0);
    }

    private void printTable() {
        int addr = ops[0], w = ops[1];
        int h = nops > 2 ? ops[2] : 1;
        int skip = nops > 3 ? ops[3] : 0;
        int[] rc = io.getCursor();
        for (int r = 0; r < h; r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < w; c++) {
                char ch = text.zsciiToChar(m.u8(addr++));
                if (ch != 0) sb.append(ch);
            }
            addr += skip;
            out(sb);
            if (r < h - 1) {
                if (io.currentWindow() == 1) io.setCursor(rc[0] + r + 1, rc[1]);
                else out("\n" + " ".repeat(Math.max(0, rc[1] - 1)));
            }
        }
    }

    private int random(int range) {
        if (range > 0) return rng.nextInt(range) + 1;
        rng = range < 0 ? new Random(range) : new Random();
        return 0;
    }

    private void setFont(int font) {
        if (font == 0) store(currentFont);
        else if (font == 1 || font == 4) {
            int prev = currentFont;
            currentFont = font;
            store(prev);
        } else store(0);
    }

    private void unsupported(int type, int num) {
        throw new ZMachineException(String.format("Unsupported or illegal opcode %s:%d for version %d",
                TYPE_NAMES[type], num, version));
    }

    // ================================================================ decode & dispatch

    private int readOperand(int type) {
        switch (type) {
            case 0: return fetch16();
            case 1: return fetch8();
            default: return readVar(fetch8());
        }
    }

    private void readTyped(int types1, int types2) {
        for (int i = 0; i < 4; i++) {
            int t = (types1 >> (6 - 2 * i)) & 3;
            if (t == 3) return;
            ops[nops++] = readOperand(t);
        }
        if (types2 < 0) return;
        for (int i = 0; i < 4; i++) {
            int t = (types2 >> (6 - 2 * i)) & 3;
            if (t == 3) return;
            ops[nops++] = readOperand(t);
        }
    }

    private void step() {
        instrStart = pc;
        int op = fetch8();
        nops = 0;
        int type, num;
        if (op == 0xBE && version >= 5) {
            type = EXT;
            num = fetch8();
            readTyped(fetch8(), -1);
        } else if (op >= 0xC0) {
            num = op & 0x1F;
            type = (op & 0x20) != 0 ? VAR : OP2;
            int t1 = fetch8();
            int t2 = (op == 0xEC || op == 0xFA) ? fetch8() : -1;
            readTyped(t1, t2);
        } else if (op >= 0x80) {
            num = op & 0x0F;
            int t = (op >> 4) & 3;
            if (t == 3) {
                type = OP0;
            } else {
                type = OP1;
                ops[nops++] = readOperand(t);
            }
        } else {
            num = op & 0x1F;
            type = OP2;
            ops[nops++] = readOperand((op & 0x40) != 0 ? 2 : 1);
            ops[nops++] = readOperand((op & 0x20) != 0 ? 2 : 1);
        }
        for (int i = nops; i < 8; i++) ops[i] = 0;
        switch (type) {
            case OP2: exec2OP(num); break;
            case OP1: exec1OP(num); break;
            case OP0: exec0OP(num); break;
            case VAR: execVAR(num); break;
            default: execEXT(num); break;
        }
    }

    private void exec2OP(int n) {
        switch (n) {
            case 1: {
                boolean eq = false;
                for (int i = 1; i < nops; i++) if (ops[0] == ops[i]) { eq = true; break; }
                branch(eq);
                break;
            }
            case 2: branch(s(ops[0]) < s(ops[1])); break;
            case 3: branch(s(ops[0]) > s(ops[1])); break;
            case 4: {
                int v = ops[0];
                int val = (short) (peekVar(v) - 1);
                pokeVar(v, val);
                branch(val < s(ops[1]));
                break;
            }
            case 5: {
                int v = ops[0];
                int val = (short) (peekVar(v) + 1);
                pokeVar(v, val);
                branch(val > s(ops[1]));
                break;
            }
            case 6: branch(objs.parent(ops[0]) == ops[1]); break;
            case 7: branch((ops[0] & ops[1]) == ops[1]); break;
            case 8: store(ops[0] | ops[1]); break;
            case 9: store(ops[0] & ops[1]); break;
            case 10: branch(objs.testAttr(ops[0], ops[1])); break;
            case 11: objs.setAttr(ops[0], ops[1], true); break;
            case 12: objs.setAttr(ops[0], ops[1], false); break;
            case 13: pokeVar(ops[0], ops[1]); break;
            case 14: objs.insert(ops[0], ops[1]); break;
            case 15: store(m.u16((ops[0] + 2 * ops[1]) & 0xFFFF)); break;
            case 16: store(m.u8((ops[0] + ops[1]) & 0xFFFF)); break;
            case 17: store(objs.getProp(ops[0], ops[1])); break;
            case 18: store(objs.getPropAddr(ops[0], ops[1])); break;
            case 19: store(objs.getNextProp(ops[0], ops[1])); break;
            case 20: store(s(ops[0]) + s(ops[1])); break;
            case 21: store(s(ops[0]) - s(ops[1])); break;
            case 22: store(s(ops[0]) * s(ops[1])); break;
            case 23: {
                int d = s(ops[1]);
                if (d == 0) throw new ZMachineException("Division by zero");
                store(s(ops[0]) / d);
                break;
            }
            case 24: {
                int d = s(ops[1]);
                if (d == 0) throw new ZMachineException("Division by zero");
                store(s(ops[0]) % d);
                break;
            }
            case 25: if (version < 4) unsupported(OP2, n); doCall(fetch8()); break;
            case 26: if (version < 5) unsupported(OP2, n); doCall(-1); break;
            case 27: if (version < 5) unsupported(OP2, n); io.setColour(ops[0], ops[1]); break;
            case 28: {
                if (version < 5) unsupported(OP2, n);
                while (frames.size() > ops[1] && frames.size() > 1) frames.remove(frames.size() - 1);
                cur = frames.get(frames.size() - 1);
                doReturn(ops[0]);
                break;
            }
            default: unsupported(OP2, n);
        }
    }

    private void exec1OP(int n) {
        switch (n) {
            case 0: branch(ops[0] == 0); break;
            case 1: {
                int v = objs.sibling(ops[0]);
                writeVar(fetch8(), v);
                branch(v != 0);
                break;
            }
            case 2: {
                int v = objs.child(ops[0]);
                writeVar(fetch8(), v);
                branch(v != 0);
                break;
            }
            case 3: store(objs.parent(ops[0])); break;
            case 4: store(objs.propLen(ops[0])); break;
            case 5: pokeVar(ops[0], peekVar(ops[0]) + 1); break;
            case 6: pokeVar(ops[0], peekVar(ops[0]) - 1); break;
            case 7: printZString(ops[0]); break;
            case 8: if (version < 4) unsupported(OP1, n); doCall(fetch8()); break;
            case 9: objs.remove(ops[0]); break;
            case 10: printZString(objs.shortNameAddr(ops[0])); break;
            case 11: doReturn(ops[0]); break;
            case 12: pc = pc + s(ops[0]) - 2; break;
            case 13: printZString(unpackString(ops[0])); break;
            case 14: store(peekVar(ops[0])); break;
            case 15:
                if (version <= 4) store(~ops[0] & 0xFFFF);
                else doCall(-1);
                break;
            default: unsupported(OP1, n);
        }
    }

    private void exec0OP(int n) {
        switch (n) {
            case 0: doReturn(1); break;
            case 1: doReturn(0); break;
            case 2: {
                StringBuilder sb = new StringBuilder();
                pc = text.decode(pc, sb);
                out(sb);
                break;
            }
            case 3: {
                StringBuilder sb = new StringBuilder();
                pc = text.decode(pc, sb);
                out(sb);
                out("\n");
                doReturn(1);
                break;
            }
            case 4: break;
            case 5: opSave(); break;
            case 6: opRestore(); break;
            case 7: opRestart(); break;
            case 8: doReturn(cur.pop()); break;
            case 9:
                if (version <= 4) cur.pop();
                else store(frames.size());
                break;
            case 10: running = false; break;
            case 11: out("\n"); break;
            case 12: if (version <= 3) showStatusLine(); break;
            case 13: if (version < 3) unsupported(OP0, n); opVerify(); break;
            case 15: if (version < 5) unsupported(OP0, n); branch(true); break;
            default: unsupported(OP0, n);
        }
    }

    private void execVAR(int n) {
        switch (n) {
            case 0: doCall(fetch8()); break;
            case 1: m.set16((ops[0] + 2 * ops[1]) & 0xFFFF, ops[2]); break;
            case 2: m.set8((ops[0] + ops[1]) & 0xFFFF, ops[2] & 0xFF); break;
            case 3: objs.putProp(ops[0], ops[1], ops[2]); break;
            case 4: doRead(); break;
            case 5: {
                char c = text.zsciiToChar(ops[0]);
                if (c != 0) out(String.valueOf(c));
                break;
            }
            case 6: out(Integer.toString(s(ops[0]))); break;
            case 7: store(random(s(ops[0]))); break;
            case 8: cur.push(ops[0]); break;
            case 9: pokeVar(ops[0], cur.pop()); break;
            case 10:
                if (version < 3) unsupported(VAR, n);
                io.splitWindow(s(ops[0]));
                if (version == 3) io.eraseWindow(1);
                break;
            case 11: if (version < 3) unsupported(VAR, n); io.setWindow(s(ops[0])); break;
            case 12: if (version < 4) unsupported(VAR, n); doCall(fetch8()); break;
            case 13: if (version < 4) unsupported(VAR, n); io.eraseWindow(s(ops[0])); break;
            case 14: if (version < 4) unsupported(VAR, n); if (ops[0] == 1) io.eraseLine(); break;
            case 15: if (version < 4) unsupported(VAR, n); io.setCursor(s(ops[0]), s(ops[1])); break;
            case 16: {
                if (version < 4) unsupported(VAR, n);
                int[] rc = io.getCursor();
                m.set16(ops[0], rc[0]);
                m.set16(ops[0] + 2, rc[1]);
                break;
            }
            case 17: if (version < 4) unsupported(VAR, n); io.setTextStyle(ops[0]); break;
            case 18: if (version < 4) unsupported(VAR, n); io.setBuffering(ops[0] != 0); break;
            case 19: outputStream(s(ops[0])); break;
            case 20: break;                                   // input_stream: keyboard only
            case 21: if (nops == 0 || ops[0] == 1 || ops[0] == 2) io.bell(); break;
            case 22: if (version < 4) unsupported(VAR, n); doReadChar(); break;
            case 23: if (version < 4) unsupported(VAR, n); scanTable(); break;
            case 24: if (version < 5) unsupported(VAR, n); store(~ops[0] & 0xFFFF); break;
            case 25: if (version < 5) unsupported(VAR, n); doCall(-1); break;
            case 26: if (version < 5) unsupported(VAR, n); doCall(-1); break;
            case 27: {
                if (version < 5) unsupported(VAR, n);
                lexer.tokenise(ops[0], ops[1], nops > 2 ? ops[2] : 0, nops > 3 && ops[3] != 0);
                break;
            }
            case 28: {
                if (version < 5) unsupported(VAR, n);
                int len = ops[1];
                int[] z = new int[len];
                for (int i = 0; i < len; i++) z[i] = m.u8(ops[0] + ops[2] + i);
                byte[] enc = text.encode(z, len);
                for (int i = 0; i < enc.length; i++) m.set8(ops[3] + i, enc[i] & 0xFF);
                break;
            }
            case 29: if (version < 5) unsupported(VAR, n); copyTable(); break;
            case 30: if (version < 5) unsupported(VAR, n); printTable(); break;
            case 31: if (version < 5) unsupported(VAR, n); branch(ops[0] <= cur.argCount); break;
            default: unsupported(VAR, n);
        }
    }

    private void execEXT(int n) {
        switch (n) {
            case 0: extSave(); break;
            case 1: extRestore(); break;
            case 2: {
                int places = s(ops[1]);
                store((places >= 0 ? ops[0] << places : ops[0] >>> -places) & 0xFFFF);
                break;
            }
            case 3: {
                int places = s(ops[1]);
                store(places >= 0 ? s(ops[0]) << places : s(ops[0]) >> -places);
                break;
            }
            case 4: setFont(ops[0]); break;
            case 9: extSaveUndo(); break;
            case 10: extRestoreUndo(); break;
            case 11: {
                int cp = ops[0];
                if (cp == 10 || cp == 13) out("\n");
                else if (cp >= 32) out(new String(Character.toChars(cp)));
                break;
            }
            case 12: {
                int cp = ops[0];
                boolean ok = cp >= 32 && !(cp >= 127 && cp < 160);
                store(ok ? 3 : 0);
                break;
            }
            case 13: break;                                   // set_true_colour: ignored
            default:
                if (n >= 29) break;                           // Standard 14.2.1: ignore high EXT codes
                unsupported(EXT, n);
        }
    }
}
