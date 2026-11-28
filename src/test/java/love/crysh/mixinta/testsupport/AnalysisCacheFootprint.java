package love.crysh.mixinta.testsupport;

import love.crysh.mixinta.analysis.container.ContainerAnalysis;
import love.crysh.mixinta.analysis.flow.MethodFlow;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 仅测量缓存专属结构的浅大小，共享 ASM 节点、来源值和引用事实由分析结果继续持有。 */
public final class AnalysisCacheFootprint {
    private static Instrumentation instrumentation;

    private AnalysisCacheFootprint() { }

    public static void premain(String arguments, Instrumentation value) { instrumentation = value; }

    static Map<String, Long> measure(Set<MethodFlow> sources, Collection<ContainerAnalysis> analyses) throws IllegalAccessException {
        if (instrumentation == null) return Map.of();
        long indices = 0;
        for (var source : sources) indices += mapSize(field(source, "indexSnapshot"), false);
        long aliases = 0;
        long unknowns = 0;
        for (var analysis : analyses) {
            for (var flow : analysis.flows()) {
                Object cache = flow.aliases();
                aliases += instrumentation.getObjectSize(cache);
                aliases += mapSize(field(cache, "locations"), true);
                aliases += mapSize(field(cache, "values"), false);
            }
            unknowns += mapSize(field(analysis.callGraph(), "unknowns"), false);
        }
        return Map.of("indexCacheBytes", indices, "aliasCacheBytes", aliases, "unknownCacheBytes", unknowns);
    }

    private static long mapSize(Object value, boolean includeKeys) throws IllegalAccessException {
        if (value == null) return 0;
        Map<?, ?> map = (Map<?, ?>) value;
        long bytes = instrumentation.getObjectSize(map);
        Object table = field(map, "table");
        if (table != null) bytes += instrumentation.getObjectSize(table);
        if (includeKeys) for (Object key : map.keySet()) bytes += instrumentation.getObjectSize(key);
        if (map instanceof HashMap<?, ?>) {
            for (Object entry : map.entrySet()) bytes += instrumentation.getObjectSize(entry);
        }
        return bytes;
    }

    private static Object field(Object object, String name) throws IllegalAccessException {
        if (object == null) return null;
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException absent) {
                continue;
            }
        }
        return null;
    }
}
