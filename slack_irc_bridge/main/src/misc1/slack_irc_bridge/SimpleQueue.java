package misc1.slack_irc_bridge;

import com.google.common.collect.Lists;
import java.util.Deque;
import misc1.commons.Maybe;

public final class SimpleQueue<T> {
    private final Deque<Maybe<T>> queue = Lists.newLinkedList();

    public synchronized void put(T e) {
        queue.addLast(Maybe.of(e));
        notifyAll();
    }

    public synchronized void close() {
        queue.addLast(Maybe.not());
        notifyAll();
    }

    public synchronized T take() throws InterruptedException {
        while(true) {
            Maybe<T> ret = queue.pollFirst();
            if(ret != null) {
                return ret.get(null);
            }
            wait();
        }
    }
}
