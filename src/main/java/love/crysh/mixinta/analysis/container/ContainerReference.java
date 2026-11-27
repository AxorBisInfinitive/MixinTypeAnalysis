package love.crysh.mixinta.analysis.container;

/** 派生引用保留根分配位置，通道区分 Map 的 key、value 与 Entry。 */
public record ContainerReference(StorageId storage, int channel, Kind kind) {
    public enum Kind { ROOT, CUSTOM, LIST, SEQUENCE, KEYS, VALUES, ENTRIES, ITERATOR, LIST_ITERATOR, ENTRY_ITERATOR, ENTRY }

    public StorageSlot slot(int operationChannel) {
        return new StorageSlot(storage, channel >= 0 ? channel : operationChannel);
    }

    public boolean entries() {
        return kind == Kind.ENTRIES || kind == Kind.ENTRY_ITERATOR || kind == Kind.ENTRY;
    }
}
