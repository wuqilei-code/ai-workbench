package com.aiworkbench.service.opencode.process;

import com.aiworkbench.config.OpencodeProperties;
import org.springframework.stereotype.Component;

import java.util.BitSet;

/**
 * 每用户 opencode 进程端口分配器(BitSet 维护 [portMin,portMax])。线程安全。
 * 每活跃用户一个 opencode 进程,各监听 127.0.0.1:<port>。
 */
@Component
public class PortAllocator {

    private final BitSet used;
    private final int min;
    private final int capacity;

    public PortAllocator(OpencodeProperties props) {
        this.min = props.getPortMin();
        this.capacity = props.getPortMax() - props.getPortMin() + 1;
        if (this.capacity <= 0) {
            throw new IllegalArgumentException("portMax must be >= portMin");
        }
        this.used = new BitSet(capacity);
    }

    /** 分配一个空闲端口;无空闲则抛 IllegalStateException。 */
    public synchronized int allocate() {
        int idx = used.nextClearBit(0);
        if (idx >= capacity) {
            throw new IllegalStateException("no free port in [" + min + "," + (min + capacity - 1) + "]");
        }
        used.set(idx);
        return min + idx;
    }

    public synchronized void release(int port) {
        if (port < min || port >= min + capacity) return;
        used.clear(port - min);
    }

    public synchronized int activeCount() {
        return used.cardinality();
    }
}
