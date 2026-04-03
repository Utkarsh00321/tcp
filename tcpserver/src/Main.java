public class Main {
    public static void main(String[] args){
        try{
            Server server = new Server();
            server.startServer();
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}
