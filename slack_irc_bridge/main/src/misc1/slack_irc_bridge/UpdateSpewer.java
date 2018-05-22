package misc1.slack_irc_bridge;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class UpdateSpewer<K, V> {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateSpewer.class);

    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    private final Map<K, ImmutableList.Builder<V>> pending = Maps.newLinkedHashMap();
    private boolean idle = true;

    public synchronized void add(K k, V v) {
        ImmutableList.Builder<V> pending2 = pending.get(k);
        if(pending2 == null) {
            pending.put(k, pending2 = ImmutableList.builder());
        }
        pending2.add(v);
        schedule();
    }

    private synchronized void schedule() {
        if(!idle) {
            return;
        }

        Iterator<Map.Entry<K, ImmutableList.Builder<V>>> i = pending.entrySet().iterator();
        if(!i.hasNext()) {
            return;
        }
        Map.Entry<K, ImmutableList.Builder<V>> e = i.next();
        i.remove();

        idle = false;
        ex.submit(() -> {
            try {
                fire(e.getKey(), e.getValue().build());
            }
            catch(Throwable t) {
                LOGGER.error("UpdateSpewer.fire() threw", t);
            }

            synchronized(UpdateSpewer.this) {
                idle = true;
            }

            schedule();
        });
    }

    protected abstract void fire(K k, ImmutableList<V> vs);
}
