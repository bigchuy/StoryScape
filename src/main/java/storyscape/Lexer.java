package storyscape;

/** Lexical analysis: splits an input buffer into words and looks them up (Standard section 13). */
final class Lexer {
    private final Memory m;
    private final ZText text;
    private final int version;

    Lexer(Memory m, ZText text, int version) {
        this.m = m;
        this.text = text;
        this.version = version;
    }

    /**
     * @param dictionary dictionary address, or 0 for the story's main dictionary
     * @param skipUnknown if true, slots for unrecognised words are left untouched
     */
    void tokenise(int textBuf, int parseBuf, int dictionary, boolean skipUnknown) {
        int dict = dictionary != 0 ? dictionary : m.u16(0x08);
        int nSep = m.u8(dict);
        boolean[] sep = new boolean[256];
        for (int i = 0; i < nSep; i++) sep[m.u8(dict + 1 + i)] = true;
        int entryLen = m.u8(dict + 1 + nSep);
        int signedCount = (short) m.u16(dict + 2 + nSep);
        int entries = Math.abs(signedCount);
        int entriesStart = dict + 4 + nSep;

        int base, len;
        if (version >= 5) {
            len = m.u8(textBuf + 1);
            base = textBuf + 2;
        } else {
            base = textBuf + 1;
            len = 0;
            while (m.u8(base + len) != 0) len++;
        }

        int maxWords = m.u8(parseBuf);
        int count = 0;
        int i = 0;
        while (i < len && count < maxWords) {
            int c = m.u8(base + i);
            if (c == ' ') { i++; continue; }
            int start = i;
            if (sep[c]) {
                i++;
            } else {
                while (i < len) {
                    c = m.u8(base + i);
                    if (c == ' ' || sep[c]) break;
                    i++;
                }
            }
            int wlen = i - start;
            int found = lookup(base + start, wlen, entriesStart, entryLen, entries);
            int slot = parseBuf + 2 + 4 * count;
            if (!(found == 0 && skipUnknown)) {
                m.set16(slot, found);
                m.set8(slot + 2, wlen);
                m.set8(slot + 3, base - textBuf + start);
            }
            count++;
        }
        m.set8(parseBuf + 1, count);
    }

    private int lookup(int wordAddr, int wlen, int entriesStart, int entryLen, int entries) {
        int[] z = new int[wlen];
        for (int k = 0; k < wlen; k++) z[k] = m.u8(wordAddr + k);
        byte[] enc = text.encode(z, wlen);
        for (int e = 0; e < entries; e++) {
            int a = entriesStart + e * entryLen;
            boolean same = true;
            for (int k = 0; k < enc.length; k++) {
                if (m.u8(a + k) != (enc[k] & 0xFF)) { same = false; break; }
            }
            if (same) return a;
        }
        return 0;
    }
}
