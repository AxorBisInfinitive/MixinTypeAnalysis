package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 在固定分量上分别观察无增长、包含及不同幅度增长的合流成本。 */
public final class ReferenceJoinerProbe {
    private static volatile Object retained;

    private ReferenceJoinerProbe() { }

    public static void main(String[] arguments) {
        String workload = arguments[0];
        var method = new MethodKey("sample/Subject", "run", "()V");
        Set<ProgramPoint> base = new LinkedHashSet<>();
        Set<ProgramPoint> additions = new LinkedHashSet<>();
        for (int index = 0; index < 64; index++) base.add(new ProgramPoint(method, index, "value"));
        switch (workload) {
            case "same" -> additions.addAll(base);
            case "contained" -> additions.add(new ProgramPoint(method, 0, "value"));
            case "slight" -> {
                additions.addAll(base);
                additions.remove(new ProgramPoint(method, 0, "value"));
                additions.add(new ProgramPoint(method, 64, "value"));
            }
            case "broad" -> {
                for (int index = 32; index < 96; index++) additions.add(new ProgramPoint(method, index, "value"));
            }
            default -> throw new IllegalArgumentException(workload);
        }
        var first = new StorageReferences(Set.of(), base, false);
        var second = new StorageReferences(Set.of(), additions, false);
        var joiner = new ReferenceJoiner();
        var joined = joiner.join(first, second);
        if ((workload.equals("slight") || workload.equals("broad"))
                && (joined == first || joined == second)) throw new IllegalStateException("Join workload must grow both inputs");
        for (int index = 0; index < 20_000; index++) retained = joiner.join(first, second);
        var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long beforeBytes = allocations.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        for (int index = 0; index < 200_000; index++) retained = joiner.join(first, second);
        long elapsed = System.nanoTime() - started;
        long bytes = allocations.getThreadAllocatedBytes(thread) - beforeBytes;
        System.out.printf(Locale.ROOT, "{\"nsPerJoin\":%.3f,\"bytesPerJoin\":%.3f}%n", elapsed / 200_000.0, bytes / 200_000.0);
    }
}
