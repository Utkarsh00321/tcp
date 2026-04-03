public class Main {
    public static void main(String[] args) {
        try{
            Client client = new Client();
            client.startClient();
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}