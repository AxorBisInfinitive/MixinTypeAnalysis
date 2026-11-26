package love.crysh.exp;

import java.util.ArrayDeque;
import java.util.Queue;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        method1();
        method2();
    }

    private static void method1() {
        BlockPos blockPos = new BlockPos(12L);
        long packed = blockPos.asLong();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(packed);
        long next = queue.poll();
        System.out.println(lookup(next));
    }

    private static void method2() {
        BlockPos blockPos = new BlockPos(16L);
        long packed = blockPos.asLong();
        System.out.println(lookup(packed));
    }

    private static int lookup(long packed) {
        return (int) (packed ^ (packed >>> 32));
    }

    public record BlockPos(long packed) {
        public long asLong() {
            return packed;
        }
    }
}
