import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

public class ProtocolDecoder {

    private static final int MAX_LENGTH = 1_000_000;

    public static Message decode(DataInputStream input,
                                 Set<Byte> versions,
                                 Map<Byte, String> messageTypes)
            throws IOException, ProtocolException {

        int length = input.readInt();
        byte version = input.readByte();
        byte type = input.readByte();

        if (length < 0 || length > MAX_LENGTH) {
            throw new ProtocolException("Invalid payload length", true);
        }

        if (!versions.contains(version)) {
            throw new ProtocolException("Invalid protocol version", true);
        }

        byte[] payload = new byte[length];
        input.readFully(payload);

        if (!messageTypes.containsKey(type)) {
            throw new ProtocolException("Invalid message type", false);
        }

        return new Message(length, version, type, payload);
    }
}