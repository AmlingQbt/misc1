package misc1.slack_irc_bridge;

import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.Socket;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import misc1.commons.ds.union.Union;
import misc1.commons.ds.union.UnionChoice;
import misc1.commons.ds.union.UnionKey;
import misc1.commons.ds.union.UnionType;
import misc1.commons.ds.union.UnionVisit;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);

    private static final String SERVER_NAME = "irc-slack-bridge";

    private final SimpleQueue<Event> queue = new SimpleQueue<>();
    private final ClientWriter cw;
    private final ClientReader cr;

    public Client(Socket s) {
        this(s, UUID.randomUUID().toString());
    }

    private Client(Socket s, String id) {
        super("Client(" + id + ")");

        this.cw = new ClientWriter(s, id) {
            @Override
            protected void onTerminate(Throwable err) {
                LOGGER.debug("Terminated write", err);
            }
        };
        this.cr = new ClientReader(s, id) {
            @Override
            protected void onLine(String line) {
                LOGGER.debug("Received: " + line);
                queue.put(Event.TYPE.of(Event.IRC_LINE, line));
            }

            @Override
            protected void onTerminate(Throwable err) {
                LOGGER.debug("Terminated read", err);
                queue.close();
            }
        };
    }

    private String nick;
    private ClientSlack slack;
    private final Set<String> joined = Sets.newHashSet();

    @Override
    public void run() {
        cw.start();
        cr.start();

        try {
            while(true) {
                Event event = queue.take();
                if(event == null) {
                    cw.terminate();
                    if(slack != null) {
                        try {
                            slack.terminate();
                        }
                        catch(Throwable t) {
                            LOGGER.error("ClientSlack.terminate() threw!", t);
                        }
                    }
                    break;
                }

                try {
                    UnionVisit<Event, Void> v = event.visit();
                    v = v.on(Event.IRC_LINE, this::onIrcLine);
                    v = v.on(Event.WEBSOCKET_EVENT, this::onWebSocketEvent);
                    v = v.on(Event.SINGULARITY, (pair) -> {
                        if(pair.getLeft() != slack) {
                            // old, drop!
                            return null;
                        }

                        imMessage("root", "singularity: " + pair.getRight());

                        return null;
                    });
                    v = v.on(Event.WEBSOCKET_CLOSE, this::onWebSocketClose);
                    v.complete();
                }
                catch(Throwable t) {
                    err("While handling: " + event, t);
                }
            }
        }
        catch(Throwable err) {
            LOGGER.error("Client threw!", err);
        }
    }

    private Void onIrcLine(String line) {
        LOGGER.debug("Processing: " + line);

        IrcMessage msg = IrcMessage.parse(line);

        switch(msg.command) {
            case "NICK": {
                nick = msg.args.get(0);
                put(SERVER_NAME, "001", nick, "Welcome to the IRC slack bridge v2.  $DEITY help you (again)...");
                break;
            }

            case "PRIVMSG": {
                String to = msg.args.get(0);
                String text = msg.args.get(1);
                if(to.startsWith("#")) {
                    String channelId = slack.guessUpChannelId(to.substring(1));
                    slack.req("chat.postMessage", ImmutableMap.of("channel", channelId, "text", text));
                }
                else {
                    if(to.equals("root")) {
                        command(text);
                    }
                    else {
                        if(slack == null) {
                            throw new RuntimeException("Not logged in!");
                        }
                        String channelId = slack.requireImByName(to);
                        slack.req("chat.postMessage", ImmutableMap.of("channel", channelId, "text", text));
                    }
                }
                break;
            }

            case "PING": {
                if(slack != null) {
                    try {
                        slack.poke();
                    }
                    catch(Throwable t) {
                        err("slack.poke threw!", t);
                    }
                }
                put(SERVER_NAME, "PONG", SERVER_NAME, SERVER_NAME);
                break;
            }

            case "AWAY": {
                String text = msg.args.get(0);
                if(text.isEmpty()) {
                    if(slack != null) {
                        slack.req(ClientSlack.RETRIES, "users.setPresence", ImmutableMap.of("presence", "auto"));
                    }
                    put(SERVER_NAME, "305");
                }
                else {
                    if(slack != null) {
                        slack.req(ClientSlack.RETRIES, "users.setPresence", ImmutableMap.of("presence", "away"));
                    }
                    put(SERVER_NAME, "306");
                }
                break;
            }

            case "JOIN": {
                String channels = msg.args.get(0);
                for(String channel : channels.split(",")) {
                    joined.add(channel);
                    put(nick, "JOIN", channel);
                }
                break;
            }

            case "PART": {
                String channels = msg.args.get(0);
                for(String channel : channels.split(",")) {
                    joined.remove(channel);
                    put(nick, "PART", channel);
                }
                break;
            }

            case "WHOIS": {
                if(msg.args.size() != 1) {
                    imMessage("root", "Unintelligible WHOIS: " + line);
                    break;
                }
                String name = msg.args.get(0);
                String userId = slack.requireUserIdByName(name);
                {
                    JsonObject res = slack.req(ClientSlack.RETRIES, "users.info", ImmutableMap.of("user", userId));
                    String realName = res.get("user").getAsJsonObject().get("profile").getAsJsonObject().get("real_name").getAsString();
                    put(SERVER_NAME, "311", nick, name, "slack", "slack", "*", realName);
                }
                {
                    JsonObject res = slack.req(ClientSlack.RETRIES, "users.getPresence", ImmutableMap.of("user", userId));
                    if(!res.get("presence").getAsString().equals("active")) {
                        put(SERVER_NAME, "301", nick, name, "Away: " + res);
                    }
                }
                put(SERVER_NAME, "318", nick, "End of WHOIS list");
                break;
            }

            case "USER":
            case "MODE":
                // yeah, whatever
                break;

            default: {
                imMessage("root", "Unexpected IRC line: " + line);
                break;
            }
        }

        return null;
    }

    private Void onWebSocketEvent(Pair<ClientSlack, JsonObject> pair) {
        if(pair.getLeft() != slack) {
            // old, drop!
            return null;
        }

        JsonObject obj = pair.getRight();

        switch(obj.get("type").getAsString()) {
            case "message": {
                onWsMessage(obj);
                break;
            }

            default: {
                // not an indiciation of error and already logged elsewhere
                break;
            }
        }

        return null;
    }

    private Void onWebSocketClose(Triple<ClientSlack, Integer, String> triple) {
        ClientSlack slackLocal = triple.getLeft();
        if(slackLocal != slack) {
            // old, drop!
            return null;
        }

        int statusCode = triple.getMiddle();
        String reason = triple.getRight();
        imMessage("root", "Slack websocket closed: " + statusCode + ", " + reason);

        // Null out first so if any part of reconnect crashes we
        // don't keep anything.
        slack = null;

        slackLocal.terminate();
        slack = newClientSlack(slackLocal.token);

        imMessage("root", "Reconnected.");

        return null;
    }

    private void onWsMessage(JsonObject obj) {
        if(obj.has("channel") && obj.has("ts")) {
            slack.mark(obj.get("channel").getAsString(), obj.get("ts").getAsString());
        }

        if(obj.has("bot_id") && !obj.get("bot_id").isJsonNull() && obj.get("bot_id").getAsString().equals("B0P9R4Y3U")) {
            if(obj.has("user") && obj.has("channel") && obj.has("text") && obj.get("text").getAsString().equals("")) {
                if(obj.has("attachments")) {
                    JsonArray attachments = obj.get("attachments").getAsJsonArray();
                    if(attachments.size() == 1) {
                        JsonObject attachment = attachments.get(0).getAsJsonObject();
                        if(attachment.has("fallback")) {
                            showSlackMessage(obj.get("channel").getAsString(), obj.get("user").getAsString(), attachment.get("fallback").getAsString());
                            return;
                        }
                    }
                }
            }
        }

        // this "view" is applicable, even for some things with subtype
        if(obj.has("channel") && obj.has("user") && obj.has("text")) {
            showSlackMessage(obj.get("channel").getAsString(), obj.get("user").getAsString(), obj.get("text").getAsString());
            return;
        }

        if(obj.has("subtype")) {
            switch(obj.get("subtype").getAsString()) {
                case "message_deleted": {
                    JsonObject obj2 = obj.get("previous_message").getAsJsonObject();
                    if(obj2.has("subtype")) {
                        switch(obj2.get("subtype").getAsString()) {
                            case "file_share": {
                                return;
                            }

                            case "file_comment": {
                                return;
                            }
                        }
                    }
                    showSlackMessage(obj.get("channel").getAsString(), obj2.get("user").getAsString(), "[deleted] " + obj2.get("text").getAsString());
                    return;
                }

                case "message_changed": {
                    JsonObject obj2 = obj.get("message").getAsJsonObject();
                    String newText = obj2.get("text").getAsString();
                    String oldText = obj.get("previous_message").getAsJsonObject().get("text").getAsString();
                    if(!newText.equals(oldText)) {
                        showSlackMessage(obj.get("channel").getAsString(), obj2.get("user").getAsString(), "[edited] " + newText);
                    }
                    else {
                        // e.g.  attachments added, blah blah blah, but we don't see it anyway
                    }
                    return;
                }

                case "message_replied": {
                    return;
                }

                case "bot_message": {
                    return;
                }

                case "file_comment": {
                    return;
                }
            }
        }

        imMessage("root", "Unexpected websocket message event: " + obj);
    }

    private void showSlackMessage(String channelId, String fromUserId, String text) {
        if(slack.myUserId.equals(fromUserId)) {
            return;
        }
        Pair<ChannelType, String> channelInfo = slack.channelInfo(channelId);
        Iterable<String> lines = mangleSlack(text);
        switch(channelInfo.getLeft()) {
            case IM: {
                for(String line : lines) {
                    imMessage(channelInfo.getRight(), line);
                }
                break;
            }

            case CHANNEL_OR_GROUP: {
                String channelName = channelInfo.getRight();
                String channel = "#" + channelName;
                if(joined.add(channel)) {
                    put(nick, "JOIN", channel);
                }
                for(String line : lines) {
                    put(slack.requireNameByUserId(fromUserId), "PRIVMSG", channel, line);
                }
                break;
            }

            case UNKNOWN:
            default:
                imMessage("root", "Unexpected websocket message event: [" + channelInfo.getLeft() + ":" + channelInfo.getRight() + "] " + text);
                break;
        }
    }

    private ClientSlack newClientSlack(String token) {
        return new ClientSlack(token) {
            @Override
            protected void onEvent(JsonObject obj) {
                queue.put(Event.TYPE.of(Event.WEBSOCKET_EVENT, Pair.of(this, obj)));
            }

            @Override
            protected void onAddUser(String user) {
                queue.put(Event.TYPE.of(Event.SINGULARITY, Pair.of(this, "+" + user)));
            }

            @Override
            protected void onDelUser(String user) {
                queue.put(Event.TYPE.of(Event.SINGULARITY, Pair.of(this, "-" + user)));
            }

            @Override
            protected void onError(Throwable t) {
                err("websocket error", t);
            }

            @Override
            protected void onClose(int statusCode, String reason) {
                queue.put(Event.TYPE.of(Event.WEBSOCKET_CLOSE, Triple.of(this, statusCode, reason)));
            }
        };
    }

    private void command(String line) {
        if(line.startsWith("login ")) {
            String token = line.substring(6);

            // TODO: backfill stuff "before" websocket (substantially more complex to get right)

            slack = newClientSlack(token);

            imMessage("root", "Token accepted.");

            return;
        }

        if(line.equals("logout")) {
            if(slack != null) {
                ClientSlack slackLocal = slack;
                slack = null;
                slackLocal.terminate();
            }

            return;
        }

        if(line.equals("slack.poke")) {
            if(slack != null) {
                slack.poke();
            }

            return;
        }

        imMessage("root", "?");
    }

    private static final Pattern USER_TOKEN_PATTERN = Pattern.compile("<@(U[0-9A-Z]*)(?:\\|[^>]*)?>");
    private static final Pattern HERE_TOKEN_PATTERN = Pattern.compile("<!here(?:\\|[^>]*)?>");
    private static final Pattern ENTITY_PATTERN = Pattern.compile("&(amp|lt|gt);");
    private static final ImmutableMap<String, String> ENTITY_MAP = ImmutableMap.of("amp", "&", "lt", "<", "gt", ">");

    private Iterable<String> mangleSlack(String text) {
        ImmutableList.Builder<String> b = ImmutableList.builder();
        for(String line : Splitter.on('\n').split(text)) {
            line = mangle(line, USER_TOKEN_PATTERN, (m) -> {
                String userId = m.group(1);
                String name = slack.findNameByUserId(userId);
                if(name == null) {
                    name = userId;
                }
                return "<@" + name + ">";
            });
            line = mangle(line, HERE_TOKEN_PATTERN, (m) -> "<@here>");
            line = mangle(line, ENTITY_PATTERN, (m) -> ENTITY_MAP.get(m.group(1)));
            b.add(line);
        }
        return b.build();
    }

    private String mangle(String in, Pattern pat, Function<Matcher, String> fn) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        Matcher m = pat.matcher(in);
        while(true) {
            if(!m.find(i)) {
                sb.append(in, i, in.length());
                return sb.toString();
            }
            sb.append(in, i, m.start());
            sb.append(fn.apply(m));
            i = m.end();
        }
    }

    private void err(String msg, Throwable t) {
        LOGGER.debug("Client.err(): " + msg, t);
        imMessage("root", msg + " -- " + t);
    }

    private void imMessage(String from, String msg) {
        if(nick == null) {
            LOGGER.error("imMessage() before we have a nick: <" + from + "> " + msg);
            return;
        }
        put(from, "PRIVMSG", nick, msg);
    }

    private void put(String prefix, String command, String... args) {
        put(prefix, command, ImmutableList.copyOf(args));
    }

    private void put(String prefix, String command, Iterable<String> args) {
        cw.writeLine(new IrcMessage(prefix, command, args).deparse());
    }

    public static final class Event extends Union<Event> {
        private Event(UnionChoice<Event, ?> choice) {
            super(TYPE, choice);
        }

        @Override
        protected Event self() {
            return this;
        }

        public static final UnionKey<Event, String> IRC_LINE;
        public static final UnionKey<Event, Pair<ClientSlack, JsonObject>> WEBSOCKET_EVENT;
        public static final UnionKey<Event, Pair<ClientSlack, String>> SINGULARITY;
        public static final UnionKey<Event, Triple<ClientSlack, Integer, String>> WEBSOCKET_CLOSE;
        public static final UnionType<Event> TYPE;
        static {
            ImmutableList.Builder<UnionKey<Event, ?>> b = ImmutableList.builder();

            b.add(IRC_LINE = new UnionKey<>("ircLine"));
            b.add(WEBSOCKET_EVENT = new UnionKey<>("websocketEvent"));
            b.add(WEBSOCKET_CLOSE = new UnionKey<>("websocketClose"));
            b.add(SINGULARITY = new UnionKey<>("singularity"));

            TYPE = new UnionType<>(b.build(), Event::new);
        }
    }
}
