package storyscape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextAndObjectTest {

    @Test
    void decodesZString() {
        byte[] s = TestSupport.story(3);
        TestSupport.put(s, 0x200, 0x11, 0xAA, 0xC6, 0x34);       // "Hello"
        Memory m = new Memory(s);
        StringBuilder sb = new StringBuilder();
        int end = new ZText(m, 3).decode(0x200, sb);
        assertEquals("Hello", sb.toString());
        assertEquals(0x204, end);
    }

    @Test
    void encodesDictionaryWords() {
        Memory m = new Memory(TestSupport.story(3));
        ZText t = new ZText(m, 3);
        int[] z = {'h', 'e', 'l', 'l', 'o'};
        byte[] enc = t.encode(z, 5);
        assertArrayEquals(new byte[] {0x35, 0x51, (byte) 0xC6, (byte) 0x85}, enc);
        // v5 uses 9 Z-characters = 6 bytes
        Memory m5 = new Memory(TestSupport.story(5));
        assertEquals(6, new ZText(m5, 5).encode(z, 5).length);
    }

    @Test
    void objectTreeAndProperties() {
        byte[] s = TestSupport.story(3);
        int tree = 0x140 + 62;
        TestSupport.put16(s, tree + 7, 0x200);          // object 1 property table
        TestSupport.put16(s, tree + 9 + 7, 0x220);      // object 2 property table
        TestSupport.put(s, 0x200, 0x00, 0x25, 0x12, 0x34, 0x02, 0x07, 0x00);
        TestSupport.put(s, 0x220, 0x00, 0x00);
        TestSupport.put16(s, 0x140, 0x0BEE);            // default for property 1
        Memory m = new Memory(s);
        ObjectTable o = new ObjectTable(m, 3);

        o.insert(2, 1);
        assertEquals(1, o.parent(2));
        assertEquals(2, o.child(1));
        o.setAttr(1, 9, true);
        assertTrue(o.testAttr(1, 9));
        o.setAttr(1, 9, false);
        assertEquals(false, o.testAttr(1, 9));

        assertEquals(0x1234, o.getProp(1, 5));
        assertEquals(7, o.getProp(1, 2));
        assertEquals(0x0BEE, o.getProp(1, 1));
        assertEquals(5, o.getNextProp(1, 0));
        assertEquals(2, o.getNextProp(1, 5));
        assertEquals(0, o.getNextProp(1, 2));
        assertEquals(2, o.propLen(o.getPropAddr(1, 5)));
        o.putProp(1, 2, 0x1FF);                          // 1-byte property keeps the low byte
        assertEquals(0xFF, o.getProp(1, 2));

        o.remove(2);
        assertEquals(0, o.parent(2));
        assertEquals(0, o.child(1));
    }

    @Test
    void quetzalRoundTrip() {
        byte[] s = TestSupport.story(3);
        Memory m = new Memory(s);
        m.set8(0x300, 0x42);
        m.set16(0x310, 0xBEEF);
        List<Frame> frames = new ArrayList<>();
        Frame base = new Frame();
        base.push(7);
        frames.add(base);
        Frame f = new Frame();
        f.returnPC = 0x1234;
        f.storeVar = 0x10;
        f.argCount = 2;
        f.locals = new int[] {1, 2, 3};
        f.push(99);
        frames.add(f);

        byte[] file = Quetzal.write(m, frames, 0x55);
        Memory fresh = new Memory(s);
        Quetzal.Saved saved = Quetzal.read(file, fresh);
        assertEquals(0x55, saved.pc());
        assertEquals(2, saved.frames().size());
        Frame g = saved.frames().get(1);
        assertEquals(0x1234, g.returnPC);
        assertEquals(0x10, g.storeVar);
        assertEquals(2, g.argCount);
        assertArrayEquals(new int[] {1, 2, 3}, g.locals);
        assertEquals(99, g.peek());
        fresh.restoreDynamic(saved.dynamic());
        assertEquals(0x42, fresh.u8(0x300));
        assertEquals(0xBEEF, fresh.u16(0x310));
        assertTrue(saved.extras().isEmpty());
    }

    @Test
    void quetzalKeepsExtraChunks() {
        byte[] s = TestSupport.story(3);
        Memory m = new Memory(s);
        List<Frame> frames = new ArrayList<>();
        frames.add(new Frame());
        byte[] odd = "Note é".getBytes(java.nio.charset.StandardCharsets.UTF_8);   // odd length: padded
        byte[] file = Quetzal.write(m, frames, 0x55,
                java.util.Map.of("JZnt", odd, "JZmp", new byte[] {1, 2}, "Stks", new byte[] {9}));
        Quetzal.Saved saved = Quetzal.read(file, new Memory(s));
        assertArrayEquals(odd, saved.extras().get("JZnt"));
        assertArrayEquals(new byte[] {1, 2}, saved.extras().get("JZmp"));
        assertEquals(1, saved.frames().size());          // a reserved id in extras is never written
        assertEquals(2, saved.extras().size());
    }
}
