package storyscape;

import java.util.Arrays;

/**
 * The Z-machine memory map (Standard section 1). Dynamic memory (below the static
 * base) is writable; everything else is read-only. A pristine copy of the story file
 * is kept for restart, verify and Quetzal compression.
 */
public final class Memory {
    private final byte[] story;
    private final byte[] data;
    private final int staticBase;

    public Memory(byte[] storyFile) {
        if (storyFile.length < 64) {
            throw new ZMachineException("File too short to be a Z-code story file");
        }
        this.story = storyFile.clone();
        this.data = storyFile.clone();
        this.staticBase = ((story[0x0E] & 0xFF) << 8) | (story[0x0F] & 0xFF);
        if (staticBase < 64 || staticBase > story.length) {
            throw new ZMachineException("Corrupt header: bad static memory base");
        }
    }

    public int size() { return data.length; }

    public int staticBase() { return staticBase; }

    public int u8(int addr) {
        if (addr < 0 || addr >= data.length) throw outOfRange(addr);
        return data[addr] & 0xFF;
    }

    public int u16(int addr) {
        if (addr < 0 || addr + 1 >= data.length) throw outOfRange(addr);
        return ((data[addr] & 0xFF) << 8) | (data[addr + 1] & 0xFF);
    }

    public void set8(int addr, int value) {
        checkWritable(addr);
        data[addr] = (byte) value;
    }

    public void set16(int addr, int value) {
        checkWritable(addr);
        checkWritable(addr + 1);
        data[addr] = (byte) (value >> 8);
        data[addr + 1] = (byte) value;
    }

    private void checkWritable(int addr) {
        if (addr < 0 || addr >= staticBase) {
            throw new ZMachineException(
                    String.format("Illegal write to address %05x (outside dynamic memory)", addr));
        }
    }

    private static ZMachineException outOfRange(int addr) {
        return new ZMachineException(String.format("Memory access out of range: %05x", addr));
    }

    /** The unmodified story file image. */
    public byte[] original() { return story; }

    public int original(int addr) { return story[addr] & 0xFF; }

    public byte[] dynamicCopy() { return Arrays.copyOf(data, staticBase); }

    public void restoreDynamic(byte[] dynamic) {
        System.arraycopy(dynamic, 0, data, 0, Math.min(dynamic.length, staticBase));
    }

    /** Reload the whole image from the story file (restart). */
    public void reset() {
        System.arraycopy(story, 0, data, 0, story.length);
    }
}
