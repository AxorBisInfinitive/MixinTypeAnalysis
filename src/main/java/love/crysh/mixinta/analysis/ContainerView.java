package love.crysh.mixinta.analysis;

import love.crysh.mixinta.model.ProgramPoint;

import java.util.LinkedHashSet;
import java.util.Set;

/** 派生对象与根容器共享元素存储，迭代位置及视图区间继续由原 JDK 对象维护。 */
enum ContainerView {
    ALIAS, ITERATOR, LIST_ITERATOR, LIST, SEQUENCE, KEYS, VALUES, ENTRIES, ENTRY;

    StorageReferences derive(StorageReferences source, ProgramPoint point, int declaredChannel) {
        Set<ContainerReference> references = new LinkedHashSet<>();
        Set<ProgramPoint> unknown = new LinkedHashSet<>(source.unknownOrigins());
        for (ContainerReference parent : source.references()) {
            int channel = parent.channel() >= 0 ? parent.channel() : declaredChannel;
            ContainerReference.Kind kind = switch (this) {
                case ALIAS -> ContainerReference.Kind.CUSTOM;
                case ITERATOR -> parent.kind() == ContainerReference.Kind.ENTRIES
                        ? ContainerReference.Kind.ENTRY_ITERATOR : ContainerReference.Kind.ITERATOR;
                case LIST_ITERATOR -> ContainerReference.Kind.LIST_ITERATOR;
                case LIST -> ContainerReference.Kind.LIST;
                case SEQUENCE -> ContainerReference.Kind.SEQUENCE;
                case KEYS -> ContainerReference.Kind.KEYS;
                case VALUES -> ContainerReference.Kind.VALUES;
                case ENTRIES -> ContainerReference.Kind.ENTRIES;
                case ENTRY -> ContainerReference.Kind.ENTRY;
            };
            if (kind == ContainerReference.Kind.ENTRY || kind == ContainerReference.Kind.ENTRIES
                    || kind == ContainerReference.Kind.ENTRY_ITERATOR) channel = -1;
            if (this == ENTRY && parent.kind() != ContainerReference.Kind.ENTRY_ITERATOR
                    && parent.kind() != ContainerReference.Kind.ROOT && parent.kind() != ContainerReference.Kind.CUSTOM) {
                unknown.add(point);
            } else {
                references.add(new ContainerReference(parent.storage(), channel, kind));
            }
        }
        return new StorageReferences(references, unknown, false);
    }
}
