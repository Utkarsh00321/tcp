import java.io.DataOutputStream;
import java.io.IOException;

public class ProtocolEncoder {

    public static void encode(DataOutputStream output, Message message) throws IOException {
        output.writeInt(message.getLength());
        output.writeByte(message.getVersion());
        output.writeByte(message.getType());
        output.write(message.getPayload());
        output.flush();
    }
}