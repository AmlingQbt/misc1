package misc1.slack_irc_bridge;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ClientSlack {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSlack.class);

    public static final int RETRIES = 5;

    final String token;
    public final String myUserId;
    private final WebSocketClient client;
    private final MySocket socket;

    public ClientSlack(String token) {
        this.token = token;

        JsonObject authTest = ClientSlack.req(token, RETRIES, "auth.test", ImmutableMap.of());
        this.myUserId = authTest.get("user_id").getAsString();

        JsonObject res = req(token, RETRIES, "rtm.connect", ImmutableMap.of());
        String url = res.get("url").getAsString();

        SslContextFactory sslContextFactory = new SslContextFactory();
        this.client = new WebSocketClient(sslContextFactory);
        this.socket = new MySocket();

        try {
            client.start();

            ClientUpgradeRequest request = new ClientUpgradeRequest();
            client.connect(socket, new URI(url), request);
        }
        catch(Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private final AtomicInteger nextRequestID = new AtomicInteger(1);
    private Set<String> singularityState = null;

    public void poke() {
        Session session = socket.session;
        if(session != null) {
            JsonObject ping = new JsonObject();
            ping.addProperty("id", nextRequestID.getAndIncrement());
            ping.addProperty("type", "ping");
            try {
                session.getRemote().sendString(ping.toString());
            }
            catch(IOException e) {
                throw Throwables.propagate(e);
            }
        }

        Set<String> currentUsers = getUsersList().keySet();

        if(singularityState == null) {
            singularityState = ImmutableSet.copyOf(currentUsers);
            return;
        }

        Set<String> removed = Sets.newHashSet(singularityState);
        List<String> added = Lists.newArrayList();
        ImmutableSet.Builder<String> next = ImmutableSet.builder();

        for(String name : currentUsers) {
            if(!removed.remove(name)) {
                added.add(name);
            }
            next.add(name);
        }

        singularityState = next.build();

        Collections.sort(added);
        for(String name : added) {
            onAddUser(name);
        }

        List<String> removedSorted = Lists.newArrayList(removed);
        Collections.sort(removedSorted);
        for(String name : removedSorted) {
            onDelUser(name);
        }
    }

    public void terminate() {
        try {
            client.stop();
        }
        catch(Exception e) {
            throw Throwables.propagate(e);
        }
    }

    protected abstract void onEvent(JsonObject obj);
    protected abstract void onAddUser(String user);
    protected abstract void onDelUser(String user);
    protected abstract void onError(Throwable t);
    protected abstract void onClose(int statusCode, String reason);

    @WebSocket
    public class MySocket {
        // JFC, this API sucks...
        public volatile Session session;

        @OnWebSocketConnect
        public void onConnect(Session session) {
            this.session = session;
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            LOGGER.error("ClientSlack.onClose: " + statusCode + ", " + reason);
            ClientSlack.this.onClose(statusCode, reason);
        }

        @OnWebSocketMessage
        public void onMessage(String msg) {
            LOGGER.debug("ClientSlack.onMessage: " + msg);

            try {
                JsonElement e = new JsonParser().parse(msg);
                onEvent(e.getAsJsonObject());
            }
            catch(Throwable t) {
                onError(t);
            }
        }
    }

    private static abstract class Lookup<K, V> {
        private final Map<K, V> forward = Maps.newHashMap();
        private final Map<V, K> reverse = Maps.newHashMap();

        public V requireForward(K in) {
            V out = findForward(in);
            if(out != null) {
                return out;
            }

            throw new RuntimeException("Unknown: " + in);
        }

        public V findForward(K in) {
            {
                V out = forward.get(in);
                if(out != null) {
                    return out;
                }
            }

            refreshForward(in);

            {
                V out = forward.get(in);
                if(out != null) {
                    return out;
                }
            }

            return null;
        }

        public K requireReverse(V out) {
            K in = findReverse(out);
            if(in != null) {
                return in;
            }

            throw new RuntimeException("Unknown: " + out);
        }

        public K findReverse(V out) {
            {
                K in = reverse.get(out);
                if(in != null) {
                    return in;
                }
            }

            refreshReverse(out);

            {
                K in = reverse.get(out);
                if(in != null) {
                    return in;
                }
            }

            return null;
        }

        protected void put(K in, V out) {
            forward.put(in, out);
            reverse.put(out, in);
        }

        protected void refreshForward(K in) {
            refreshAll();
        }

        protected void refreshReverse(V out) {
            refreshAll();
        }

        protected abstract void refreshAll();
    }

    private Map<String, String> getUsersList() {
        ImmutableMap.Builder<String, String> b = ImmutableMap.builder();

        JsonObject usersList = req(RETRIES, "users.list", ImmutableMap.of());
        for(JsonElement e : usersList.get("members").getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if(o.get("deleted").getAsBoolean()) {
                continue;
            }
            String id = o.get("id").getAsString();
            String name = o.get("name").getAsString();
            b.put(name, id);
        }

        return b.build();
    }

    private final Lookup<String, String> nameToUserId = new Lookup<String, String>() {
        @Override
        protected void refreshAll() {
            for(Map.Entry<String, String> e : getUsersList().entrySet()) {
                put(e.getKey(), e.getValue());
            }
        }
    };

    private final Lookup<String, String> userIdToImId = new Lookup<String, String>() {
        @Override
        protected void refreshForward(String userId) {
            JsonObject imOpen = req(RETRIES, "im.open", ImmutableMap.of("user", userId));
            String imId = imOpen.get("channel").getAsJsonObject().get("id").getAsString();
            put(userId, imId);
        }

        @Override
        protected void refreshAll() {
            JsonObject imList = req(RETRIES, "im.list", ImmutableMap.of());
            for(JsonElement e : imList.get("ims").getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                String imId = o.get("id").getAsString();
                String userId = o.get("user").getAsString();
                put(userId, imId);
            }
        }
    };

    private final Lookup<String, String> otherChannelNameToId = new Lookup<String, String>() {
        @Override
        protected void refreshAll() {
            JsonObject groupsList = req(RETRIES, "groups.list", ImmutableMap.of());
            for(JsonElement e : groupsList.get("groups").getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                String name = o.get("name").getAsString();
                put(name, id);
            }

            JsonObject channelsList = req(RETRIES, "channels.list", ImmutableMap.of());
            for(JsonElement e : channelsList.get("channels").getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                String name = o.get("name").getAsString();
                put(name, id);
            }
        }
    };

    public String requireImByName(String name) {
        String userId = nameToUserId.requireForward(name);
        return userIdToImId.requireForward(userId);
    }

    public String requireNameByUserId(String userId) {
        return nameToUserId.requireReverse(userId);
    }

    public String requireUserIdByName(String name) {
        return nameToUserId.requireForward(name);
    }

    public Pair<ChannelType, String> channelInfo(String channelId) {
        String userId = userIdToImId.findReverse(channelId);
        if(userId != null) {
            return Pair.of(ChannelType.IM, nameToUserId.requireReverse(userId));
        }

        String name = otherChannelNameToId.findReverse(channelId);
        if(name != null) {
            return Pair.of(ChannelType.CHANNEL_OR_GROUP, name);
        }

        return Pair.of(ChannelType.UNKNOWN, channelId);
    }

    public String guessUpChannelId(String ircChannelName) {
        return otherChannelNameToId.requireForward(ircChannelName);
    }

    public String findNameByUserId(String userId) {
        return nameToUserId.findReverse(userId);
    }

    private final UpdateSpewer<String, String> marker = new UpdateSpewer<String, String>() {
        @Override
        protected void fire(String channelId, ImmutableList<String> tss) {
            String tsMax = null;
            for(String ts : tss) {
                if(tsMax == null || ts.compareTo(tsMax) > 0) {
                    tsMax = ts;
                }
            }
            String type = null;
            switch(channelId.charAt(0)) {
                case 'D':
                    type = "im";
                    break;

                case 'C':
                    type = "channels";
                    break;

                case 'G':
                    type = "groups";
                    break;
            }
            req(RETRIES, type + ".mark", ImmutableMap.of("channel", channelId, "ts", tsMax));

            try {
                Thread.sleep(5000);
            }
            catch(InterruptedException e) {
                throw Throwables.propagate(e);
            }
        }
    };

    public void mark(String channelId, String ts) {
        marker.add(channelId, ts);
    }

    public JsonObject req(int retries, String method, ImmutableMap<String, String> args) {
        return req(token, retries, method, args);
    }

    public JsonObject req(String method, ImmutableMap<String, String> args) {
        return req(token, method, args);
    }

    public static JsonObject req(String token, int retries, String method, ImmutableMap<String, String> args) {
        while(true) {
            try {
                return req(token, method, args);
            }
            catch(RuntimeException e) {
                if(retries-- > 0) {
                    continue;
                }
                throw e;
            }
        }
    }

    private static JsonObject req(String token, String method, ImmutableMap<String, String> args) {
        try(CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpUriRequest req;
            if(args.isEmpty()) {
                req = new HttpGet("https://palantir.slack.com/api/" + method + "?token=" + token);
            }
            else {
                HttpPost req1 = new HttpPost("https://palantir.slack.com/api/" + method + "?token=" + token);
                ImmutableList.Builder<String> pieces = ImmutableList.builder();
                for(Map.Entry<String, String> e : args.entrySet()) {
                    pieces.add(e.getKey() + "=" + URLEncoder.encode(e.getValue()));
                }
                req1.setEntity(new StringEntity(Joiner.on("&").join(pieces.build()), ContentType.APPLICATION_FORM_URLENCODED));
                req = req1;
            }
            HttpResponse res = httpClient.execute(req);
            if(res.getStatusLine().getStatusCode() >= 300) {
                throw new RuntimeException(req.getRequestLine() + ": " + res.getStatusLine());
            }
            JsonElement e = new JsonParser().parse(new InputStreamReader(res.getEntity().getContent()));
            JsonObject o = e.getAsJsonObject();
            if(!o.get("ok").getAsBoolean()) {
                throw new RuntimeException("req(" + method + ", " + args + ") failed: " + o);
            }
            // shut your damned mouth, slack
            o.remove("ok");
            o.remove("response_metadata");
            o.remove("warning");
            return o;
        }
        catch(IOException e) {
            throw Throwables.propagate(e);
        }
    }
}
