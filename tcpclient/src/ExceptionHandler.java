import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ExceptionHandler {

    private static final byte TYPE_ERROR = 5;

    public static void handle(Exception e, DataOutputStream output) {
        try {
            System.out.println("[ERROR] " + e.getMessage());

            byte[] payload = e.getMessage().getBytes(StandardCharsets.UTF_8);

            Message errorMsg = new Message(
                    payload.length,
                    (byte) 1,
                    TYPE_ERROR,
                    payload
            );

            ProtocolEncoder.encode(output, errorMsg);

        } catch (IOException ioException) {
            System.out.println("[ERROR] Failed to send error response.");
        }
    }
}