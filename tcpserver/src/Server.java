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

    private static Set<Byte> versions;
    private static Map<Byte, String> messageTypes;

    private ExecutorService pool;
    private volatile boolean shutdown = false;

    private ConcurrentHashMap<Socket, DataOutputStream> connections;

    private static final byte TYPE_PING = 1;
    private static final byte TYPE_PONG = 2;
    private static final byte TYPE_REQUEST = 3;
    private static final byte TYPE_RESPONSE = 4;
    private static final byte TYPE_SERVER_CLOSE = 6;
    private static final byte TYPE_CLOSE_ACK = 8;
    private static final byte TYPE_HEARTBEAT = 9;
    private static final byte TYPE_SERVER_SHUTDOWN = 10;
    private static final byte TYPE_ERROR = 5;

    static {
        versions = new HashSet<>(Arrays.asList((byte)1,(byte)2,(byte)3));

        Map<Byte, String> temp = new HashMap<>();
        temp.put(TYPE_PING, "PING");
        temp.put(TYPE_REQUEST, "REQUEST");
        temp.put(TYPE_SERVER_CLOSE, "CLOSE");
        temp.put(TYPE_CLOSE_ACK, "CLOSE_ACK");
        temp.put(TYPE_HEARTBEAT, "HEARTBEAT");

        messageTypes = Collections.unmodifiableMap(temp);
    }

    public void startServer() {
        System.out.println("[SERVER] Starting the server on port: " + PORT);

        try(ServerSocket severSocket = new ServerSocket(PORT)){

            System.out.println("[SERVER] Listening on port: " + PORT);

            int cores = Runtime.getRuntime().availableProcessors();
            pool = Executors.newFixedThreadPool(cores * 2);
            connections = new ConcurrentHashMap<>();

            Thread shutDownListener = new Thread(() -> {
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
                                sendMessage(output, 0, (byte)1, TYPE_SERVER_SHUTDOWN , new byte[]{});
                            }catch (IOException e){
                                e.printStackTrace();
                            }
                        }

                        // Graceful shutdown
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
                        throw new RuntimeException(e);
                    }
                }catch (IOException e){
                    e.printStackTrace();
                }
            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    public void handleClient(Socket clientSocket) throws Exception {
        try(clientSocket){
            clientSocket.setSoTimeout(90000);

            DataInputStream input = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()));

            connections.put(clientSocket,output);

            while(true){
                try{
                    Message message = ProtocolDecoder.decode(input, versions, messageTypes);

                    boolean isAlive = processMessage(message, output);

                    if(!isAlive){
                        break;
                    }

                } catch (SocketTimeoutException e) {

                    System.out.println("[WORKER] Client inactive. Sending heartbeat...");

                    sendMessage(output, 0, (byte)1, TYPE_HEARTBEAT, new byte[]{});

                } catch (EOFException e){
                    System.out.println("[WORKER] Client closed connection!");
                    break;

                } catch (ProtocolException e){

                    ExceptionHandler.handle(e, output);

                    if(e.isFatal()){
                        System.out.println("[WORKER] Fatal protocol error. Closing connection.");
                        break;
                    }

                } catch (SocketException e){
                    if(shutdown){
                        System.out.println("[WORKER] Socket closed due to server shutdown.");
                        break;
                    }else{
                        throw new RuntimeException(e);
                    }

                } catch (IOException e){
                    throw new RuntimeException(e);
                }
            }
        } finally {
            connections.remove(clientSocket);
        }
    }

    public void sendMessage(DataOutputStream output, int length, byte version, byte type, byte[] payload) throws IOException {
        Message message = new Message(length, version, type, payload);
        ProtocolEncoder.encode(output, message);
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

            case TYPE_HEARTBEAT:
                System.out.println("[WORKER] Heartbeat received. Connection alive.");
                break;

            case TYPE_SERVER_CLOSE:
                respType = TYPE_CLOSE_ACK;
                break;

            case TYPE_CLOSE_ACK:
                System.out.println("[WORKER] Received CLOSE_ACK. Closing connection.");
                return false;
        }

        if(type!=TYPE_HEARTBEAT){
            sendMessage(output, respLength, respVersion, respType, respPayload);
        }

        if(type == TYPE_SERVER_CLOSE || type == TYPE_CLOSE_ACK){
            return false;
        }

        return true;
    }

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