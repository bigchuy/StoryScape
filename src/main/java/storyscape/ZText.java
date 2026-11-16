package storyscape;

/**
 * Z-character and ZSCII handling (Standard section 3): decoding strings, abbreviations,
 * alphabets (including custom ones), the Unicode translation table, and dictionary encoding.
 */
public final class ZText {
    private static final String A0 = "abcdefghijklmnopqrstuvwxyz";
    private static final String A1 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** Z-characters 7..31 of alphabet 2. */
    private static final String A2_V1 = "0123456789.,!?_#'\"/\\<-:()";
    private static final String A2_STD = "\n0123456789.,!?_#'\"/\\-:()";

    /** Default ZSCII 155..223 extra characters (Standard 3.8.7). */
    private static final String DEFAULT_UNICODE =
            "\u00e4\u00f6\u00fc\u00c4\u00d6\u00dc\u00df\u00bb\u00ab\u00eb\u00ef\u00ff\u00cb\u00cf"
          + "\u00e1\u00e9\u00ed\u00f3\u00fa\u00fd\u00c1\u00c9\u00cd\u00d3\u00da\u00dd"
          + "\u00e0\u00e8\u00ec\u00f2\u00f9\u00c0\u00c8\u00cc\u00d2\u00d9"
          + "\u00e2\u00ea\u00ee\u00f4\u00fb\u00c2\u00ca\u00ce\u00d4\u00db"
          + "\u00e5\u00c5\u00f8\u00d8\u00e3\u00f1\u00f5\u00c3\u00d1\u00d5"
          + "\u00e6\u00c6\u00e7\u00c7\u00fe\u00f0\u00de\u00d0\u00a3\u0153\u0152\u00a1\u00bf";

    private final Memory m;
    private final int version;
    /** alph[a].charAt(z - 6) is the character for Z-character z in alphabet a. */
    private final String[] alph = new String[3];
    private char[] unicode;

    public ZText(Memory m, int version) {
        this.m = m;
        this.version = version;
        refresh();
    }

    /** Re-read custom alphabet / Unicode tables from the header (after restart or restore). */
    public void refresh() {
        alph[0] = A0;
        alph[1] = A1;
        alph[2] = " " + (version == 1 ? A2_V1 : A2_STD);
        unicode = DEFAULT_UNICODE.toCharArray();
        if (version >= 5) {
            int table = m.u16(0x34);
            if (table != 0 && table + 78 <= m.size()) {
                for (int a = 0; a < 3; a++) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 26; i++) {
                        int z = m.u8(table + a * 26 + i);
                        char c = z >= 32 && z <= 126 ? (char) z : zsciiToChar(z);
                        sb.append(c == 0 ? ' ' : c);
                    }
                    alph[a] = sb.toString();
                }
                alph[2] = " \n" + alph[2].substring(2);
            }
            int ext = m.u16(0x36);
            if (ext != 0 && m.u16(ext) >= 3) {
                int uni = m.u16(ext + 6);
                if (uni != 0) {
                    int n = m.u8(uni);
                    char[] t = new char[n];
                    for (int i = 0; i < n; i++) t[i] = (char) m.u16(uni + 1 + 2 * i);
                    unicode = t;
                }
            }
        }
    }

    // ---------------------------------------------------------------- ZSCII <-> Unicode

    /** ZSCII output code to a Java char; 0 means "print nothing". */
    public char zsciiToChar(int z) {
        if (z == 13 || z == 10) return '\n';
        if (z >= 32 && z <= 126) return (char) z;
        if (z >= 155 && z <= 251) {
            int i = z - 155;
            return i < unicode.length && unicode[i] != 0 ? unicode[i] : '?';
        }
        return 0;
    }

    /** Java char to ZSCII ('?' if there is no equivalent). */
    public int charToZscii(char c) {
        if (c == '\n' || c == '\r') return 13;
        if (c >= 32 && c <= 126) return c;
        for (int i = 0; i < unicode.length; i++) {
            if (unicode[i] == c) return 155 + i;
        }
        return '?';
    }

    // ---------------------------------------------------------------- decoding

    /** Decode the Z-string at addr into out; returns the address just after it. */
    public int decode(int addr, StringBuilder out) {
        return decode(addr, out, true);
    }

    private int decode(int addr, StringBuilder out, boolean abbreviationsAllowed) {
        int alphabet = 0, lock = 0, abbrev = 0, esc = 0, escHigh = 0;
        boolean done = false;
        while (!done) {
            int w = m.u16(addr);
            addr += 2;
            done = (w & 0x8000) != 0;
            for (int shift = 10; shift >= 0; shift -= 5) {
                int z = (w >> shift) & 31;
                if (esc == 1) { escHigh = z; esc = 2; continue; }
                if (esc == 2) {
                    char c = zsciiToChar((escHigh << 5) | z);
                    if (c != 0) out.append(c);
                    esc = 0;
                    alphabet = lock;
                    continue;
                }
                if (abbrev != 0) {
                    if (abbreviationsAllowed) {
                        int index = 32 * (abbrev - 1) + z;
                        int wordAddr = m.u16(m.u16(0x18) + 2 * index);
                        decode(wordAddr * 2, out, false);
                    }
                    abbrev = 0;
                    alphabet = lock;
                    continue;
                }
                if (z == 0) { out.append(' '); alphabet = lock; continue; }
                if (z == 1 && version == 1) { out.append('\n'); alphabet = lock; continue; }
                if ((z >= 1 && z <= 3 && version >= 3) || (z == 1 && version == 2)) {
                    abbrev = z;
                    continue;
                }
                if (z >= 2 && z <= 5) {            // shift characters
                    if (version <= 2) {
                        switch (z) {
                            case 2: alphabet = (lock + 1) % 3; break;
                            case 3: alphabet = (lock + 2) % 3; break;
                            case 4: lock = (lock + 1) % 3; alphabet = lock; break;
                            default: lock = (lock + 2) % 3; alphabet = lock; break;
                        }
                    } else {
                        alphabet = z == 4 ? 1 : 2;
                    }
                    continue;
                }
                if (alphabet == 2 && z == 6) { esc = 1; continue; }   // 10-bit ZSCII escape
                out.append(alph[alphabet].charAt(z - 6));
                alphabet = lock;
            }
        }
        return addr;
    }

    // ---------------------------------------------------------------- encoding

    /**
     * Encode ZSCII text as a dictionary entry: 6 Z-characters (4 bytes) in versions 1-3,
     * 9 Z-characters (6 bytes) from version 4.
     */
    public byte[] encode(int[] zscii, int length) {
        int resolution = version >= 4 ? 9 : 6;
        int[] zc = new int[resolution + 8];
        int n = 0;
        for (int i = 0; i < length && n < resolution; i++) {
            int code = zscii[i];
            char c = zsciiToChar(code);
            boolean found = false;
            for (int a = 0; a < 3 && !found; a++) {
                int min = a == 2 ? (version == 1 ? 1 : 2) : 0;
                int idx = c == 0 ? -1 : alph[a].indexOf(c);
                if (idx >= min) {
                    if (a > 0) zc[n++] = version >= 3 ? 3 + a : 1 + a;   // v3+: 4/5, v1-2: 2/3
                    zc[n++] = idx + 6;
                    found = true;
                }
            }
            if (!found) {
                zc[n++] = version >= 3 ? 5 : 3;
                zc[n++] = 6;
                zc[n++] = (code >> 5) & 31;
                zc[n++] = code & 31;
            }
        }
        while (n < resolution) zc[n++] = 5;
        int words = resolution / 3;
        byte[] out = new byte[words * 2];
        for (int w = 0; w < words; w++) {
            int v = (zc[w * 3] << 10) | (zc[w * 3 + 1] << 5) | zc[w * 3 + 2];
            if (w == words - 1) v |= 0x8000;
            out[w * 2] = (byte) (v >> 8);
            out[w * 2 + 1] = (byte) v;
        }
        return out;
    }
}
