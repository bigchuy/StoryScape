package storyscape;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes Quetzal (IFZS) saved-game files: IFhd, CMem and Stks chunks, plus any extra
 * chunks the front-end supplies (other interpreters skip chunk types they do not know).
 */
final class Quetzal {
    record Saved(byte[] dynamic, List<Frame> frames, int pc, Map<String, byte[]> extras) {}

    private static final Set<String> CORE = Set.of("IFhd", "CMem", "UMem", "Stks");

    private Quetzal() {}

    // ---------------------------------------------------------------- writing

    static byte[] write(Memory m, List<Frame> frames, int pc) {
        return write(m, frames, pc, Map.of());
    }

    static byte[] write(Memory m, List<Frame> frames, int pc, Map<String, byte[]> extras) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] ifhd = new byte[13];
        int release = m.u16(0x02);
        ifhd[0] = (byte) (release >> 8);
        ifhd[1] = (byte) release;
        for (int i = 0; i < 6; i++) ifhd[2 + i] = (byte) m.u8(0x12 + i);
        int checksum = m.u16(0x1C);
        ifhd[8] = (byte) (checksum >> 8);
        ifhd[9] = (byte) checksum;
        ifhd[10] = (byte) (pc >> 16);
        ifhd[11] = (byte) (pc >> 8);
        ifhd[12] = (byte) pc;
        chunk(body, "IFhd", ifhd);
        chunk(body, "CMem", compress(m));
        chunk(body, "Stks", stacks(frames));
        for (Map.Entry<String, byte[]> e : extras.entrySet()) {
            String id = e.getKey();
            if (id.length() != 4 || CORE.contains(id) || e.getValue() == null) continue;
            chunk(body, id, e.getValue());
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes("FORM".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeInt(file, body.size() + 4);
        file.writeBytes("IFZS".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        file.writeBytes(body.toByteArray());
        return file.toByteArray();
    }

    private static byte[] compress(Memory m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int run = 0;
        for (int i = 0; i < m.staticBase(); i++) {
            int x = m.u8(i) ^ m.original(i);
            if (x == 0) {
                run++;
            } else {
                while (run > 0) {
                    int n = Math.min(run, 256);
                    out.write(0);
                    out.write(n - 1);
                    run -= n;
                }
                out.write(x);
            }
        }
        return out.toByteArray();     // trailing zero runs are simply omitted
    }

    private static byte[] stacks(List<Frame> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Frame f : frames) {
            out.write(f.returnPC >> 16);
            out.write(f.returnPC >> 8);
            out.write(f.returnPC);
            out.write((f.locals.length & 0x0F) | (f.storeVar < 0 ? 0x10 : 0));
            out.write(f.storeVar < 0 ? 0 : f.storeVar);
            out.write((1 << Math.min(f.argCount, 7)) - 1);
            out.write(f.sp >> 8);
            out.write(f.sp);
            for (int v : f.locals) { out.write(v >> 8); out.write(v); }
            for (int i = 0; i < f.sp; i++) { out.write(f.stack[i] >> 8); out.write(f.stack[i]); }
        }
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String id, byte[] data) {
        out.writeBytes(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeInt(out, data.length);
        out.writeBytes(data);
        if ((data.length & 1) != 0) out.write(0);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >> 24);
        out.write(v >> 16);
        out.write(v >> 8);
        out.write(v);
    }

    // ---------------------------------------------------------------- reading

    static Saved read(byte[] d, Memory m) {
        if (d.length < 12 || !id(d, 0).equals("FORM") || !id(d, 8).equals("IFZS")) {
            throw new ZMachineException("Not a Quetzal save file");
        }
        byte[] ifhd = null, cmem = null, umem = null, stks = null;
        Map<String, byte[]> extras = new LinkedHashMap<>();
        int pos = 12;
        while (pos + 8 <= d.length) {
            String cid = id(d, pos);
            int len = int32(d, pos + 4);
            int start = pos + 8;
            if (len < 0 || start + len > d.length) throw new ZMachineException("Corrupt save file");
            byte[] data = Arrays.copyOfRange(d, start, start + len);
            switch (cid) {
                case "IFhd": ifhd = data; break;
                case "CMem": cmem = data; break;
                case "UMem": umem = data; break;
                case "Stks": stks = data; break;
                default: extras.put(cid, data); break;
            }
            pos = start + len + (len & 1);
        }
        if (ifhd == null || ifhd.length < 13 || stks == null || (cmem == null && umem == null)) {
            throw new ZMachineException("Save file is missing required chunks");
        }
        boolean same = word(ifhd, 0) == m.u16(0x02) && word(ifhd, 8) == m.u16(0x1C);
        for (int i = 0; i < 6 && same; i++) same = (ifhd[2 + i] & 0xFF) == m.u8(0x12 + i);
        if (!same) throw new ZMachineException("Save file belongs to a different story");
        int pc = ((ifhd[10] & 0xFF) << 16) | ((ifhd[11] & 0xFF) << 8) | (ifhd[12] & 0xFF);

        int dyn = m.staticBase();
        byte[] mem = Arrays.copyOf(m.original(), dyn);
        if (cmem != null) {
            int p = 0;
            for (int i = 0; i < cmem.length; i++) {
                int b = cmem[i] & 0xFF;
                if (b == 0) {
                    if (++i >= cmem.length) break;
                    p += (cmem[i] & 0xFF) + 1;
                } else {
                    if (p >= dyn) throw new ZMachineException("Corrupt save file (memory overrun)");
                    mem[p] = (byte) (mem[p] ^ b);
                    p++;
                }
            }
        } else {
            if (umem.length != dyn) throw new ZMachineException("Save file memory size mismatch");
            mem = umem;
        }

        List<Frame> frames = new ArrayList<>();
        int p = 0;
        while (p < stks.length) {
            if (p + 8 > stks.length) throw new ZMachineException("Corrupt stack chunk");
            Frame f = new Frame();
            f.returnPC = ((stks[p] & 0xFF) << 16) | ((stks[p + 1] & 0xFF) << 8) | (stks[p + 2] & 0xFF);
            int flags = stks[p + 3] & 0xFF;
            int var = stks[p + 4] & 0xFF;
            int args = stks[p + 5] & 0xFF;
            int sw = word(stks, p + 6);
            p += 8;
            int nl = flags & 15;
            if (p + 2 * (nl + sw) > stks.length) throw new ZMachineException("Corrupt stack chunk");
            f.locals = new int[nl];
            for (int i = 0; i < nl; i++, p += 2) f.locals[i] = word(stks, p);
            f.stack = new int[Math.max(16, sw)];
            f.sp = sw;
            for (int i = 0; i < sw; i++, p += 2) f.stack[i] = word(stks, p);
            f.storeVar = (flags & 0x10) != 0 ? -1 : var;
            f.argCount = Integer.bitCount(args);
            frames.add(f);
        }
        if (frames.isEmpty()) throw new ZMachineException("Save file has no stack frames");
        return new Saved(mem, frames, pc, extras);
    }

    private static String id(byte[] d, int p) {
        return new String(d, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int int32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static int word(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }
}
