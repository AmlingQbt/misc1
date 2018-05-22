package misc1.slack_irc_bridge;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.tuple.Pair;

public class IrcMessage {
    public final String prefix;
    public final String command;
    public final ImmutableList<String> args;

    public IrcMessage(String prefix, String command, Iterable<String> args) {
        this.prefix = prefix;
        this.command = command;
        this.args = ImmutableList.copyOf(args);
    }

    public static IrcMessage parse(String line0) {
        String line = line0;
        if(line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String prefix = null;
        if(line.startsWith(":")) {
            line = line.substring(1);
            Pair<String, String> prefixSplit = splitArg(line0, line, false);
            prefix = prefixSplit.getLeft();
            line = prefixSplit.getRight();
            if(line == null) {
                throw new IllegalArgumentException("Cannot parse: " + line0);
            }
        }
        Pair<String, String> commandSplit = splitArg(line0, line, false);
        String command = commandSplit.getLeft();
        line = commandSplit.getRight();
        ImmutableList.Builder<String> args = ImmutableList.builder();
        while(line != null) {
            Pair<String, String> argSplit = splitArg(line0, line, true);
            args.add(argSplit.getLeft());
            line = argSplit.getRight();
        }

        return new IrcMessage(prefix, command, args.build());
    }

    private static Pair<String, String> splitArg(String line0, String line, boolean allowTrailing) {
        if(line0.isEmpty() || line.charAt(0) == ' ') {
            throw new IllegalArgumentException("Cannot parse: " + line0);
        }

        if(allowTrailing && line.charAt(0) == ':') {
            return Pair.of(line.substring(1), null);
        }
        int index = line.indexOf(' ');
        if(index == -1) {
            return Pair.of(line, null);
        }
        String arg = line.substring(0, index);
        while(index + 1 < line.length() && line.charAt(index + 1) == ' ') {
            ++index;
        }
        line = line.substring(index + 1);
        return Pair.of(arg, line);
    }

    public String deparse() {
        StringBuilder sb = new StringBuilder();
        if(prefix != null) {
            sb.append(":");
            sb.append(prefix);
        }
        sb.append(" ");
        sb.append(command);
        for(int i = 0; i < args.size(); ++i) {
            String arg = args.get(i);
            if(i < args.size() - 1) {
                if(arg.contains(" ") || arg.startsWith(":")) {
                    throw new IllegalArgumentException("Bad non-final argument: " + arg);
                }
                sb.append(" ");
                sb.append(arg);
            }
            else {
                sb.append(" :");
                sb.append(arg);
            }
        }
        return sb.toString();
    }
}
