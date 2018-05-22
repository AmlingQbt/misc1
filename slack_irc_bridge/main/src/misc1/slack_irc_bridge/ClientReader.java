package misc1.slack_irc_bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

public abstract class ClientReader extends Thread {
    private final Socket s;

    public ClientReader(Socket s, String id) {
        super("ClientReader(" + id + ")");
        this.s = s;
    }

    @Override
    public void run() {
        Throwable err = null;
        try(BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            while(true) {
                String line = br.readLine();
                if(line == null) {
                    break;
                }
                onLine(line);
            }
        }
        catch(Throwable t) {
            err = t;
        }
        finally {
            onTerminate(err);
        }
    }

    abstract protected void onLine(String line);
    abstract protected void onTerminate(Throwable err);
}
