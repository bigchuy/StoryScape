package storyscape;

/** Fatal error raised by the interpreter (bad story file, illegal operation, ...). */
public class ZMachineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ZMachineException(String message) {
        super(message);
    }
}
