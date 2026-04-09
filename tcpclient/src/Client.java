import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Client {

    private static final String HOST = "localhost";
    private static final int PORT = 8000;

    // Supported protocol versions
    private static final Set<Byte> versions;

    // Mapping of message type -> readable name
    private static final Map<Byte, String> messageTypes;

    // Message type constants
    private static final byte TYPE_PONG = 2;
    private static final byte TYPE_MESSAGE = 3;
    private static final byte TYPE_SERVER_CLOSE = 7;
    private static final byte TYPE_CLOSE_ACK = 8;
    private static final byte TYPE_HEARTBEAT = 9;
    private static final byte TYPE_SERVER_SHUTDOWN = 10;
    private static final byte TYPE_PING = 1;

    // Controls client lifecycle across threads
    volatile boolean running = true;

    static {
        versions = new HashSet<>(Arrays.asList((byte) 1, (byte) 2, (byte) 3));

        Map<Byte, String> temp = new HashMap<>();
        temp.put(TYPE_PING,"PING");
        temp.put(TYPE_PONG, "PONG");
        temp.put((byte) 4, "RESPONSE");
        temp.put((byte) 5, "ERROR");
        temp.put(TYPE_SERVER_CLOSE, "SERVER_CLOSE");
        temp.put(TYPE_CLOSE_ACK, "CLOSE_ACK");
        temp.put(TYPE_HEARTBEAT, "HEARTBEAT");
        temp.put(TYPE_SERVER_SHUTDOWN, "SERVER_SHUTDOWN");
        messageTypes = Collections.unmodifiableMap(temp);
    }

    public void startClient() {
        System.out.println("[CLIENT] Starting the client...");

        try (Socket clientSocket = new Socket(HOST, PORT)) {

            DataInputStream input =
                    new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));

            DataOutputStream output =
                    new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));

            Scanner scanner = new Scanner(System.in);

            // Receiver thread continuously reads messages from server
            Thread receiver = new Thread(() -> {
                while (running) {
                    try {
                        // Read message framing
                        int respLength = input.readInt();

                        if (respLength > 1_000_000) {
                            throw new IOException("Invalid payload length");
                        }

                        byte respVersion = input.readByte();
                        if (!versions.contains(respVersion)) {
                            throw new IOException("Invalid version");
                        }

                        byte respType = input.readByte();
                        if (!messageTypes.containsKey(respType)) {
                            throw new IOException("Illegal message type");
                        }

                        // Read payload
                        byte[] respPayload = new byte[respLength];
                        input.readFully(respPayload);

                        // Delegate handling
                        handleMessage(respType, respVersion, respPayload, output, clientSocket);

                    } catch (EOFException e) {
                        System.out.println("[CLIENT] Server closed the connection!");
                        break;
                    } catch (IOException e) {
                        System.out.println("[CLIENT] Error: " + e.getMessage());
                        break;
                    }
                }
            });

            receiver.start();

            // Sender loop: takes user input and sends messages
            System.out.println("[CLIENT] Enter type: ");
            while (running) {
                if (System.in.available() > 0) {
                    byte type = scanner.nextByte();

                    System.out.println("[CLIENT] Enter version: ");
                    byte version = scanner.nextByte();
                    scanner.nextLine();

                    String message = "";

                    // Only MESSAGE type carries payload
                    if (type == TYPE_MESSAGE) {
                        System.out.println("[CLIENT] Enter message: ");
                        message = scanner.nextLine();
                    }

                    if (clientSocket.isClosed()) {
                        System.out.println("[CLIENT] Server dropped the connection.");
                        break;
                    }

                    // Exit condition
                    if (message.equalsIgnoreCase("exit")) {
                        running = false;
                        clientSocket.close();
                        break;
                    }

                    sendMessage(output, version, type, message);

                    System.out.println("[CLIENT] Sent message: " + message);
                } else {
                    Thread.sleep(100); // avoid busy loop
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // Handles all incoming server messages based on type
    private void handleMessage(byte type, byte version, byte[] payload,
                               DataOutputStream output, Socket socket) throws IOException {

        String typeName = messageTypes.get(type);

        // Convert payload to string if present
        String message = payload.length > 0
                ? new String(payload, StandardCharsets.UTF_8)
                : "";

        switch (type) {

            case TYPE_HEARTBEAT:
                System.out.println("[CLIENT] Heartbeat received");

                // Respond back to keep connection alive
                sendMessage(output, version, TYPE_HEARTBEAT, "Connection is alive...");
                break;

            case TYPE_SERVER_CLOSE:
                System.out.println("[CLIENT] Server requested close");

                // Acknowledge close
                sendMessage(output, version, TYPE_CLOSE_ACK, "ACK");

                running = false;
                socket.close();
                break;

            case TYPE_SERVER_SHUTDOWN:
                System.out.println("[CLIENT] Server shutting down");

                running = false;
                socket.close();
                break;

            default:
                System.out.println("[CLIENT] Received: {Type=" + typeName +
                        ", Version=" + version +
                        ", Message=" + message + "}");
        }
    }

    // Serializes and sends a message to server
    private void sendMessage(DataOutputStream output, byte version, byte type, String msg)
            throws IOException {

        byte[] payload = msg.getBytes(StandardCharsets.UTF_8);

        // Synchronization ensures thread-safe writes
        synchronized (output) {
            output.writeInt(payload.length);
            output.writeByte(version);
            output.writeByte(type);
            output.write(payload);
            output.flush();
        }
    }
}