public class ProtocolException extends Exception {

    private final boolean fatal;

    public ProtocolException(String message, boolean fatal) {
        super(message);
        this.fatal = fatal;
    }

    public boolean isFatal() {
        return fatal;
    }
}