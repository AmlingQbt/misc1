package misc1.slack_irc_bridge;

import java.net.ServerSocket;
import java.net.Socket;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;

public class Main extends QbtCommand<Main.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    @QbtCommandName("main")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final OptionsFragment<Options, Integer> port = o.oneArg("port", "p").transform(o.singleton("12345")).transform(o.parseInt());
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.ARCANE;
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public int run(OptionsResults<? extends Options> options) throws Exception {
        int port = options.get(Options.port);
        ServerSocket ss = new ServerSocket(port);
        LOGGER.info("Ready...");
        while(true) {
            Socket cs = ss.accept();
            LOGGER.info("Starting a client...");
            Client c = new Client(cs);
            c.start();
        }
    }
}
