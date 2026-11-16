package storyscape;

/**
 * Thrown by a front-end from inside a blocking I/O call to unwind the interpreter thread
 * when the user closes the window or opens another story. Not an error.
 */
public final class ZMachineHalt extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ZMachineHalt() {
        super("halted", null, false, false);
    }
}
