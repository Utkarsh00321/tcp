import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public class Server {
    private static final int PORT = 8000;
    private static Set<Byte> versions;
    private static Map<Byte, String> messageTypes;
    private ExecutorService pool;

    private static final byte TYPE_PING = 1;
    private static final byte TYPE_PONG = 2;
    private static final byte TYPE_REQUEST = 3;
    private static final byte TYPE_RESPONSE = 4;
    private static final byte TYPE_SERVER_CLOSE = 6;
    private static final byte TYPE_CLOSE_ACK = 8;
    private static final byte TYPE_HEARTBEAT = 9;
    private static final byte TYPE_SERVER_SHUTDOWN = 10;
    private static final byte TYPE_ERROR = 5;

    int getPort(){
        return this.PORT;
    }

    static {
        versions = new HashSet<>(Arrays.asList((byte)1,(byte)2,(byte)3));
        messageTypes = new HashMap<>();
        messageTypes.put(TYPE_PING, "PING");
        messageTypes.put(TYPE_REQUEST, "REQUEST");
        messageTypes.put(TYPE_SERVER_CLOSE, "CLOSE");
        messageTypes.put(TYPE_CLOSE_ACK, "CLOSE_ACK");
        messageTypes.put(TYPE_HEARTBEAT, "HEARTBEAT");
        messageTypes = Collections.unmodifiableMap(messageTypes);
    }

    public void startServer() {
        System.out.println("Starting the server on the port : " + getPort());
        try(ServerSocket severSocket = new ServerSocket(PORT)){
            System.out.println("Server listening on the port : " + getPort());

            int cores = Runtime.getRuntime().availableProcessors();
            pool = Executors.newFixedThreadPool(cores * 2);

            while(true){
                try{
                    Socket clientSocket = severSocket.accept();
                    pool.submit(() -> {
                        try {
                            handleClient(clientSocket);
                        } catch (TimeoutException | InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }catch (IOException e){
                    e.printStackTrace();
                }
            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    public void handleClient(Socket clientSocket) throws TimeoutException, IOException, InterruptedException {
        try(clientSocket){
            clientSocket.setSoTimeout(90000);

            DataInputStream input = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));

            while(true){
                try{
                    Message message = readAndValidateMessage(input, output);
                    if(message==null){
                        continue;
                    }

                    System.out.println("Received - length: " + message.getLength() + " version: " + message.getVersion() + " type: " + message.getType());
                    boolean isAlive = processMessage(message, output);

                    if(!isAlive){
                        break;
                    }

                } catch (SocketTimeoutException e) {
                    System.out.println("Client too slow, sending heartbeat");

                    int heartBeatCount = 0;
                    boolean isAlive = false;

                    while(heartBeatCount < 3){
                        System.out.println("Sending heartbeat #" + (heartBeatCount + 1) + " at " + System.currentTimeMillis());

                        sendMessage(output, 0, (byte)1, TYPE_HEARTBEAT, new byte[]{});

                        clientSocket.setSoTimeout(30000);

                        try{
                            Message message = readAndValidateMessage(input, output);
                            if(message==null){
                                continue;
                            }

                            System.out.println("Received - length: " + message.getLength() + " version: " + message.getVersion() + " type: " + message.getType());

                            if(processMessage(message, output)){
                                isAlive = true;
                                clientSocket.setSoTimeout(90000);
                                break;
                            }

                        }catch (SocketTimeoutException ex){
                            heartBeatCount++;
                        }
                    }

                    System.out.println("Client not responding. Closing the connection...");

                    if(!isAlive){
                        break;
                    }

                }catch (EOFException e){
                    System.out.println("Client closed the connection!");
                    throw new EOFException(e.getMessage());

                }catch (IOException e){
                    throw new IOException(e.getMessage());
                }
            }
        }
    }

    public void sendMessage(DataOutputStream output, int length, byte version, byte type, byte[] payload) throws IOException {
        try{
            output.writeInt(length);
            output.writeByte(version);
            output.writeByte(type);
            output.write(payload);
            output.flush();
        }catch (IOException e){
            System.out.println("Failed to write response.");
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }
    }

    private Message readAndValidateMessage(DataInputStream input, DataOutputStream output) throws IOException {
        int length = input.readInt();
        byte version = input.readByte();
        byte type = input.readByte();

        if (length < 0 || length > 1000000) {
            throw new IOException("Invalid payload length");
        }

        if (!versions.contains(version)) {
            throw new IOException("Invalid protocol version");
        }

        byte[] payload = new byte[length];
        input.readFully(payload);

        if (!messageTypes.containsKey(type)) {
            sendMessage(output, 0, (byte)1, TYPE_ERROR, "Invalid message type".getBytes(StandardCharsets.UTF_8));
            return null;
        }

        return new Message(length, version, type, payload);
    }

    public boolean processMessage(Message message, DataOutputStream output) throws IOException {
        int length = message.getLength();
        byte type = message.getType();
        byte version = message.getVersion();
        byte[] payload = message.getPayload();

        int respLength = 0;
        byte respType = 0;
        byte respVersion = version;
        byte[] respPayload = new byte[]{};

        switch(type){

            case TYPE_PING:
                respType = TYPE_PONG;
                break;

            case TYPE_REQUEST:
                respType = TYPE_RESPONSE;
                respLength = length;
                respPayload = payload;
                break;

            case TYPE_SERVER_CLOSE:
                respType = TYPE_CLOSE_ACK;
                break;

            case TYPE_CLOSE_ACK:
                System.out.println("Received CLOSE_ACK from client");
                System.out.println("Closing the connection...");
                break;

            default:
                respType = type;
        }

        sendMessage(output, respLength, respVersion, respType, respPayload);

        if(type == TYPE_SERVER_CLOSE || type == TYPE_CLOSE_ACK){
            return false;
        }

        return true;
    }
}