package misc1.slack_irc_bridge;

import java.io.PrintWriter;
import java.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ClientWriter extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientWriter.class);

    private final Socket s;

    public ClientWriter(Socket s, String id) {
        super("ClientWriter(" + id + ")");
        this.s = s;
    }

    private final SimpleQueue<String> queue = new SimpleQueue<>();

    public void writeLine(String line) {
        queue.put(line);
    }

    public void terminate() {
        queue.close();
    }

    @Override
    public void run() {
        Throwable err = null;
        try(PrintWriter pw = new PrintWriter(s.getOutputStream())) {
            while(true) {
                String line = queue.take();
                if(line == null) {
                    return;
                }
                pw.println(line);
                pw.flush();
                LOGGER.debug("Wrote: " + line);
            }
        }
        catch(Throwable t) {
            err = t;
        }
        finally {
            onTerminate(err);
        }
    }

    abstract protected void onTerminate(Throwable err);
}
