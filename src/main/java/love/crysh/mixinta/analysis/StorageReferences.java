package love.crysh.mixinta.analysis;

import java.util.Set;

/** 同一引用可能指向多个分配位置。未知来源和 null 独立保留，合流不能将其视为已知存储。 */
record StorageReferences(Set<ContainerReference> references, Set<ProgramPoint> unknownOrigins, boolean nullable) {
    StorageReferences {
        references = Set.copyOf(references);
        unknownOrigins = Set.copyOf(unknownOrigins);
    }

    Set<StorageId> storages() {
        return references.stream().map(ContainerReference::storage).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    Set<StorageSlot> slots(int channel) {
        return references.stream().map(reference -> reference.slot(channel)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
