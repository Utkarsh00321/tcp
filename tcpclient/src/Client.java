import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Client {

    private static final String HOST = "localhost";
    private static final int PORT = 8000;

    private static final Set<Byte> versions;
    private static final Map<Byte, String> messageTypes;

    private static final byte TYPE_HEARTBEAT = 9;
    private static final byte TYPE_SERVER_SHUTDOWN = 10;
    private static final byte TYPE_CLOSE_ACK = 8;
    private static final byte CLIENT_EXIT = 0;

    volatile boolean running = true;

    static {
        versions = new HashSet<>(Arrays.asList((byte)1,(byte)2,(byte)3));

        Map<Byte, String> temp = new HashMap<>();
        temp.put((byte)1,"PING");
        temp.put((byte)2,"PONG");
        temp.put((byte)4,"RESPONSE");
        temp.put((byte)5,"ERROR");
        temp.put(TYPE_HEARTBEAT,"HEARTBEAT");
        temp.put(TYPE_SERVER_SHUTDOWN,"SERVER_SHUTDOWN");

        messageTypes = Collections.unmodifiableMap(temp);
    }

    public void startClient() {
        System.out.println("[CLIENT] Starting client...");

        try (Socket socket = new Socket(HOST, PORT)) {

            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            Thread receiver = new Thread(() -> {
                while (running) {
                    try {
                        Message msg = ProtocolDecoder.decode(input, versions, messageTypes);
                        handleMessage(msg, output, socket);

                    } catch (Exception e) {
                        System.out.println("[CLIENT] Connection closed.");
                        break;
                    }
                }
            });

            receiver.start();

            Scanner scanner = new Scanner(System.in);

            System.out.println("[CLIENT] Enter type: ");

            while (running) {

                if (System.in.available() > 0) {
                    byte type = scanner.nextByte();
                    if (type==CLIENT_EXIT) {
                        running = false;
                        socket.close();
                        break;
                    }
                    System.out.println("[CLIENT] Enter version: ");
                    byte version = scanner.nextByte();
                    scanner.nextLine();

                    String message = "";

                    if (type == 3) {
                        System.out.println("[CLIENT] Enter message: ");
                        message = scanner.nextLine();
                    }

                    if (!running) break;

                    if (socket.isClosed()) {
                        System.out.println("[CLIENT] Server dropped the connection.");
                        break;
                    }

                    byte[] payload = message.getBytes(StandardCharsets.UTF_8);

                    synchronized (output) {
                        ProtocolEncoder.encode(output,
                                new Message(payload.length, version, type, payload));
                    }

                    System.out.println("[CLIENT] Sent message: " + message);

                } else {
                    Thread.sleep(100);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(Message msg, DataOutputStream output, Socket socket) throws IOException {

        byte type = msg.getType();

        if(type == TYPE_HEARTBEAT){
            System.out.println("[CLIENT] Heartbeat received");

            ProtocolEncoder.encode(output,
                    new Message(0,(byte)1,TYPE_HEARTBEAT,new byte[]{}));
        }

        else if(type == TYPE_SERVER_SHUTDOWN){
            System.out.println("[CLIENT] Server shutting down. Closing client gracefully.");

            running = false;

            try {
                socket.close();
            } catch (IOException ignored) {}
        }

        else{
            System.out.println("[CLIENT] Received message type: " + type + " version: " + msg.getVersion() + " length: " + msg.getLength() + " message: " + new String(msg.getPayload(), StandardCharsets.UTF_8));
        }
    }
}