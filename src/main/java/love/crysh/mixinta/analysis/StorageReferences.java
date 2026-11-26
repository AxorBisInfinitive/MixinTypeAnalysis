package love.crysh.mixinta.analysis;

import java.util.Set;

/** 同一引用可能指向多个分配位置。未知来源和 null 独立保留，合流不能将其视为已知存储。 */
record StorageReferences(Set<StorageId> storages, Set<ProgramPoint> unknownOrigins, boolean nullable) {
    StorageReferences {
        storages = Set.copyOf(storages);
        unknownOrigins = Set.copyOf(unknownOrigins);
    }
}
