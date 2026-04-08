import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Client {

    private static final String HOST = "localhost";
    private static final int PORT = 8000;

    private static final Set<Byte> versions;
    private static final Map<Byte, String> messageTypes;

    private static final byte TYPE_PONG = 2;
    private static final byte TYPE_MESSAGE = 3;
    private static final byte TYPE_SERVER_CLOSE = 7;
    private static final byte TYPE_CLOSE_ACK = 8;
    private static final byte TYPE_HEARTBEAT = 9;
    private static final byte TYPE_SERVER_SHUTDOWN = 10;
    private static final byte TYPE_PING = 1;

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
        System.out.println("Starting the client...");

        try (Socket clientSocket = new Socket(HOST, PORT)) {

            DataInputStream input =
                    new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));

            DataOutputStream output =
                    new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));

            Scanner scanner = new Scanner(System.in);

            // Receiver thread
            Thread receiver = new Thread(() -> {
                while (running) {
                    try {
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

                        byte[] respPayload = new byte[respLength];
                        input.readFully(respPayload);

                        handleMessage(respType, respVersion, respPayload, output, clientSocket);

                    } catch (EOFException e) {
                        System.out.println("Server closed the connection!");
                        break;
                    } catch (IOException e) {
                        System.out.println("Error: " + e.getMessage());
                        break;
                    }
                }
            });

            receiver.start();

            // Sender loop (user input)
            while (running) {

                System.out.println("Enter type: ");
                byte type = scanner.nextByte();

                System.out.println("Enter version: ");
                byte version = scanner.nextByte();
                scanner.nextLine();

                String message = "";

                if (type == TYPE_MESSAGE) {
                    System.out.println("Enter message: ");
                    message = scanner.nextLine();
                }

                if (clientSocket.isClosed()) {
                    System.out.println("Server dropped the connection.");
                    break;
                }

                if (message.equalsIgnoreCase("exit")) {
                    running = false;
                    clientSocket.close();
                    break;
                }

                sendMessage(output, version, type, message);

                System.out.println("Sent message: " + message);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(byte type, byte version, byte[] payload,
                               DataOutputStream output, Socket socket) throws IOException {

        String typeName = messageTypes.get(type);
        String message = payload.length > 0
                ? new String(payload, StandardCharsets.UTF_8)
                : "";

        switch (type) {

            case TYPE_HEARTBEAT:
                System.out.println("Heartbeat received");

                sendMessage(output, version, TYPE_HEARTBEAT, "Connection is alive...");
                break;

            case TYPE_SERVER_CLOSE:
                System.out.println("Server requested close");

                sendMessage(output, version, TYPE_CLOSE_ACK, "ACK");

                running = false;
                socket.close();
                break;

            case TYPE_SERVER_SHUTDOWN:
                System.out.println("Server shutting down");

                running = false;
                socket.close();
                break;

            default:
                System.out.println("Received: {Type=" + typeName +
                        ", Version=" + version +
                        ", Message=" + message + "}");
        }
    }

    private void sendMessage(DataOutputStream output, byte version, byte type, String msg)
            throws IOException {

        byte[] payload = msg.getBytes(StandardCharsets.UTF_8);

        synchronized (output) {
            output.writeInt(payload.length);
            output.writeByte(version);
            output.writeByte(type);
            output.write(payload);
            output.flush();
        }
    }

}