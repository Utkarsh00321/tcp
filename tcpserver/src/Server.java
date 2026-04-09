import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class Server {
    private static final int PORT = 8000;

    // Supported protocol versions
    private static Set<Byte> versions;

    // Message type mapping
    private static Map<Byte, String> messageTypes;

    private ExecutorService pool;

    // Shutdown flag shared across threads
    private volatile boolean shutdown = false;

    // Active client connections
    private ConcurrentHashMap<Socket, DataOutputStream> connections;

    // Message type constants
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
        System.out.println("[SERVER] Starting the server on port: " + getPort());

        try(ServerSocket severSocket = new ServerSocket(PORT)){
            System.out.println("[SERVER] Listening on port: " + getPort());

            int cores = Runtime.getRuntime().availableProcessors();

            // Thread pool for handling clients
            pool = Executors.newFixedThreadPool(cores * 2);

            connections = new ConcurrentHashMap<>();

            // Thread to listen for shutdown command
            Thread shutDownListener = new Thread(()->{
                Scanner scanner = new Scanner(System.in);
                System.out.print("[SHUTDOWN] Enter server commands: ");

                String command = scanner.nextLine();

                if(command.trim().equalsIgnoreCase("shutdown")){
                    System.out.println("[SHUTDOWN] Shutdown command received. Closing server...");

                    shutdown = true;

                    try {
                        severSocket.close();

                        // Notify all clients
                        for(DataOutputStream output: connections.values()){
                            try{
                                sendMessage(output, 0, (byte)1,TYPE_SERVER_SHUTDOWN , new byte[]{});
                            }catch (IOException e){
                                e.printStackTrace();
                            }
                        }

                        // Gracefully shutdown thread pool
                        shutdownAndAwaitTermination(pool);

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                scanner.close();
            });

            shutDownListener.start();

            // Accept loop
            while(true){
                try{
                    Socket clientSocket = severSocket.accept();
                    System.out.println("[SERVER] Accepted connection: " + clientSocket);

                    // Assign client to worker thread
                    pool.submit(() -> {
                        try {
                            handleClient(clientSocket);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });

                }catch (SocketException e){
                    if(shutdown){
                        System.out.println("[SERVER] Server socket closed due to shutdown.");
                        break;
                    }else{
                        throw new SocketException();
                    }
                }catch (IOException e){
                    e.printStackTrace();
                }
            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    // Worker thread: handles one client
    public void handleClient(Socket clientSocket) throws TimeoutException, IOException, InterruptedException {
        try(clientSocket){
            clientSocket.setSoTimeout(90000);

            DataInputStream input = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));

            connections.put(clientSocket,output);

            while(true){
                try{
                    Message message = readAndValidateMessage(input, output);

                    if(message==null){
                        continue;
                    }

                    System.out.println("[WORKER] Received - length: " + message.getLength()
                            + " version: " + message.getVersion()
                            + " type: " + message.getType());

                    boolean isAlive = processMessage(message, output);

                    if(!isAlive){
                        break;
                    }

                } catch (SocketTimeoutException e) {

                    System.out.println("[WORKER] Client inactive. Sending heartbeat...");

                    int heartBeatCount = 0;
                    boolean isAlive = false;

                    while(heartBeatCount < 3){
                        System.out.println("[WORKER] Sending heartbeat #" + (heartBeatCount + 1));

                        sendMessage(output, 0, (byte)1, TYPE_HEARTBEAT, new byte[]{});

                        clientSocket.setSoTimeout(30000);

                        try{
                            Message message = readAndValidateMessage(input, output);

                            if(message==null){
                                continue;
                            }

                            System.out.println("[WORKER] Received response after heartbeat");

                            if(processMessage(message, output)){
                                isAlive = true;
                                clientSocket.setSoTimeout(90000);
                                break;
                            }

                        }catch (SocketTimeoutException ex){
                            heartBeatCount++;
                        }
                    }

                    if(!isAlive){
                        System.out.println("[WORKER] Client not responding. Closing connection.");
                        break;
                    }

                }catch (EOFException e){
                    System.out.println("[WORKER] Client closed connection!");
                    throw new EOFException(e.getMessage());

                }catch (IOException e){
                    throw new IOException(e.getMessage());
                }
            }
        }finally {
            connections.remove(clientSocket);
        }
    }

    // Sends a framed message to client
    public void sendMessage(DataOutputStream output, int length, byte version, byte type, byte[] payload) throws IOException {
        try{
            output.writeInt(length);
            output.writeByte(version);
            output.writeByte(type);
            output.write(payload);
            output.flush();
        }catch (IOException e){
            System.out.println("[WORKER] Failed to write response.");
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }
    }

    // Reads and validates incoming message
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

    // Business logic for message handling
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

            case TYPE_HEARTBEAT:
                System.out.println("[WORKER] Heartbeat received. Connection alive.");
                break;

            case TYPE_SERVER_CLOSE:
                respType = TYPE_CLOSE_ACK;
                break;

            case TYPE_CLOSE_ACK:
                System.out.println("[WORKER] Received CLOSE_ACK. Closing connection.");
                break;

            default:
                respType = type;
        }

        if(type!=TYPE_HEARTBEAT){
            sendMessage(output, respLength, respVersion, respType, respPayload);
        }

        if(type == TYPE_SERVER_CLOSE || type == TYPE_CLOSE_ACK){
            return false;
        }

        return true;
    }

    // Gracefully shutdown executor service
    void shutdownAndAwaitTermination(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("[SHUTDOWN] Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}