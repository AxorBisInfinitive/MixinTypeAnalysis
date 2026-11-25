package love.crysh.exp;

import java.util.ArrayDeque;
import java.util.Queue;

public final class Main {
    private Main() {
    }

    private static final int X = 12;
    private static final int Y = 3;
    private static final int Z = 4;

    public static void main(String[] args) {
        int r1 = add(X, Y);
        int r2 = div(r1, Z);
        System.out.println(r2);
        BlockPos blockPos = new BlockPos(12L);
        long packed = blockPos.asLong();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(packed);
        long next = queue.poll();
        System.out.println(lookup(next));
    }

    private static int add(int a, int b) {
        return a + b;
    }

    private static int div(int a, int b) {
        return a / b;
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
