package storyscape;

/** The object tree, attributes and properties (Standard section 12). */
final class ObjectTable {
    private final Memory m;
    private final boolean wide;          // version 4+ layout
    private final int objBase;
    private final int tree;
    private final int entrySize;
    private final int parentOff, siblingOff, childOff, propOff;

    ObjectTable(Memory m, int version) {
        this.m = m;
        this.wide = version >= 4;
        this.objBase = m.u16(0x0A);
        this.tree = objBase + 2 * (wide ? 63 : 31);
        this.entrySize = wide ? 14 : 9;
        this.parentOff = wide ? 6 : 4;
        this.siblingOff = wide ? 8 : 5;
        this.childOff = wide ? 10 : 6;
        this.propOff = wide ? 12 : 7;
    }

    private int entry(int obj) {
        if (obj <= 0) throw new ZMachineException("Illegal object number " + obj);
        return tree + (obj - 1) * entrySize;
    }

    private int link(int obj, int off) {
        if (obj == 0) return 0;
        int e = entry(obj) + off;
        return wide ? m.u16(e) : m.u8(e);
    }

    private void setLink(int obj, int off, int value) {
        int e = entry(obj) + off;
        if (wide) m.set16(e, value); else m.set8(e, value);
    }

    /** True if {@code obj} could be a real object: its entry and property table lie in dynamic memory. */
    boolean plausible(int obj) {
        if (obj <= 0) return false;
        int e = entry(obj);
        if (e + entrySize > m.staticBase()) return false;
        int pt = propTable(obj);
        return pt > 0 && pt < m.staticBase();
    }

    int parent(int obj) { return link(obj, parentOff); }
    int sibling(int obj) { return link(obj, siblingOff); }
    int child(int obj) { return link(obj, childOff); }

    boolean testAttr(int obj, int attr) {
        if (obj == 0) return false;
        return (m.u8(entry(obj) + attr / 8) & (0x80 >> (attr % 8))) != 0;
    }

    void setAttr(int obj, int attr, boolean on) {
        int a = entry(obj) + attr / 8;
        int mask = 0x80 >> (attr % 8);
        int v = m.u8(a);
        m.set8(a, on ? (v | mask) : (v & ~mask));
    }

    void remove(int obj) {
        if (obj == 0) return;
        int p = parent(obj);
        if (p == 0) return;
        if (child(p) == obj) {
            setLink(p, childOff, sibling(obj));
        } else {
            int c = child(p);
            int guard = 0;
            while (c != 0 && sibling(c) != obj && guard++ < 70000) c = sibling(c);
            if (c != 0) setLink(c, siblingOff, sibling(obj));
        }
        setLink(obj, parentOff, 0);
        setLink(obj, siblingOff, 0);
    }

    void insert(int obj, int dest) {
        remove(obj);
        setLink(obj, parentOff, dest);
        setLink(obj, siblingOff, child(dest));
        setLink(dest, childOff, obj);
    }

    // ---------------------------------------------------------------- properties

    private int propTable(int obj) { return m.u16(entry(obj) + propOff); }

    /** Address of the object's short name (a length byte precedes it; the Z-string follows). */
    int shortNameAddr(int obj) { return propTable(obj) + 1; }

    private int firstBlock(int obj) {
        int pt = propTable(obj);
        return pt + 1 + 2 * m.u8(pt);
    }

    /** Decode a property header: {number, length, dataAddr, nextBlockAddr}, or null at the end. */
    private int[] block(int p) {
        int b = m.u8(p);
        if (b == 0) return null;
        if (!wide) {
            int len = (b >> 5) + 1;
            return new int[] {b & 31, len, p + 1, p + 1 + len};
        }
        int num = b & 63;
        if ((b & 0x80) != 0) {
            int len = m.u8(p + 1) & 63;
            if (len == 0) len = 64;
            return new int[] {num, len, p + 2, p + 2 + len};
        }
        int len = (b & 0x40) != 0 ? 2 : 1;
        return new int[] {num, len, p + 1, p + 1 + len};
    }

    private int[] find(int obj, int prop) {
        int p = firstBlock(obj);
        int[] blk;
        while ((blk = block(p)) != null) {
            if (blk[0] == prop) return blk;
            if (blk[0] < prop) return null;      // properties are in descending order
            p = blk[3];
        }
        return null;
    }

    int getProp(int obj, int prop) {
        int[] blk = find(obj, prop);
        if (blk == null) return m.u16(objBase + 2 * (prop - 1));
        return blk[1] == 1 ? m.u8(blk[2]) : m.u16(blk[2]);
    }

    int getPropAddr(int obj, int prop) {
        int[] blk = find(obj, prop);
        return blk == null ? 0 : blk[2];
    }

    int getNextProp(int obj, int prop) {
        if (prop == 0) {
            int[] first = block(firstBlock(obj));
            return first == null ? 0 : first[0];
        }
        int[] blk = find(obj, prop);
        if (blk == null) throw new ZMachineException("get_next_prop on nonexistent property " + prop);
        int[] next = block(blk[3]);
        return next == null ? 0 : next[0];
    }

    int propLen(int dataAddr) {
        if (dataAddr == 0) return 0;
        int b = m.u8(dataAddr - 1);
        if (!wide) return (b >> 5) + 1;
        if ((b & 0x80) != 0) {
            int len = b & 63;
            return len == 0 ? 64 : len;
        }
        return (b & 0x40) != 0 ? 2 : 1;
    }

    void putProp(int obj, int prop, int value) {
        int[] blk = find(obj, prop);
        if (blk == null) throw new ZMachineException("put_prop on nonexistent property " + prop);
        if (blk[1] == 1) m.set8(blk[2], value & 0xFF);
        else m.set16(blk[2], value);
    }
}
