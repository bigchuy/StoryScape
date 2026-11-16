package storyscape;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InterpreterTest {

    @Test
    void printsHelloAndQuits() {
        // print "Hello" ; quit
        byte[] s = TestSupport.story(3, 0xB2, 0x11, 0xAA, 0xC6, 0x34, 0xBA);
        assertEquals("Hello", TestSupport.run(s));
    }

    @Test
    void arithmeticStoresToGlobal() {
        // add #2 #3 -> g0 ; print_num g0 ; quit
        byte[] s = TestSupport.story(3, 0x14, 0x02, 0x03, 0x10, 0xE6, 0xBF, 0x10, 0xBA);
        assertEquals("5", TestSupport.run(s));
    }

    @Test
    void signedDivisionAndModulo() {
        // div #-7 #2 -> g0 (large constants, VAR form 2OP:23), print_num g0, mod #-7 #2 -> g0, print_num g0
        byte[] s = TestSupport.story(3,
                0xD7, 0x0F, 0xFF, 0xF9, 0x00, 0x02, 0x10,
                0xE6, 0xBF, 0x10,
                0xD8, 0x0F, 0xFF, 0xF9, 0x00, 0x02, 0x10,
                0xE6, 0xBF, 0x10,
                0xBA);
        assertEquals("-3-1", TestSupport.run(s));
    }

    @Test
    void callsRoutineWithArgument() {
        byte[] s = TestSupport.story(3,
                0xE0, 0x1F, 0x00, 0x40, 0x05, 0x10,   // call routine@0x80 (packed 0x40) with 5 -> g0
                0xE6, 0xBF, 0x10,                     // print_num g0
                0xBA);                                // quit
        // routine: 1 local; add local1 #10 -> sp ; ret_popped
        TestSupport.put(s, 0x80, 0x01, 0x00, 0x00, 0x54, 0x01, 0x0A, 0x00, 0xB8);
        assertEquals("15", TestSupport.run(s));
    }

    @Test
    void branchesAndJumpsForALoop() {
        byte[] s = TestSupport.story(3,
                0x0D, 0x10, 0x00,                     // store g0 = 0
                0xE6, 0xBF, 0x10,                     // loop: print_num g0
                0x05, 0x10, 0x02, 0xC5,               // inc_chk g0 2 ?(quit)
                0x8C, 0xFF, 0xF8,                     // jump loop
                0xBA);                                // quit
        assertEquals("012", TestSupport.run(s));
    }

    @Test
    void readTokenisesAgainstDictionary() {
        byte[] s = TestSupport.story(3,
                0xE4, 0x0F, 0x02, 0x00, 0x02, 0x40,   // sread text@0x200 parse@0x240
                0xD0, 0x1F, 0x02, 0x40, 0x01, 0x10,   // loadb 0x240 1 -> g0  (word count)
                0xE6, 0xBF, 0x10,                     // print_num g0
                0xCF, 0x1F, 0x02, 0x40, 0x01, 0x10,   // loadw 0x240 1 -> g0  (dictionary address of word 1)
                0xE6, 0xBF, 0x10,
                0xCF, 0x1F, 0x02, 0x40, 0x03, 0x10,   // loadw 0x240 3 -> g0  (word 2: unknown -> 0)
                0xE6, 0xBF, 0x10,
                0xBA);
        // dictionary: no separators, entry length 7, one entry "hello" (4 bytes Z-text + 3 data bytes)
        TestSupport.put(s, 0x100, 0x00, 0x07, 0x00, 0x01, 0x35, 0x51, 0xC6, 0x85, 0, 0, 0);
        TestSupport.put(s, 0x200, 40);                // text buffer capacity
        TestSupport.put(s, 0x240, 5);                 // parse buffer capacity
        // 2 words; "hello" found at 0x104 = 260; "there" not found = 0
        assertEquals("22600", TestSupport.run(s, "Hello there"));
    }
}
