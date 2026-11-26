package love.crysh.mixinta.analysis;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

/** 标准容器操作的元素流摘要。 */
record ContainerSummary(ContainerOperation operation, int channel, int elementParameter,
        int keyParameter, int observedParameter, ContainerView view) {
    ContainerSummary(ContainerOperation operation, int channel, int elementParameter, int keyParameter, int observedParameter) {
        this(operation, channel, elementParameter, keyParameter, observedParameter, null);
    }

    ContainerSummary(ContainerOperation operation, int channel, int elementParameter) {
        this(operation, channel, elementParameter, -1, -1);
    }

    static ContainerSummary resolve(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            return null;
        }
        String owner = call.owner;
        String name = call.name;
        String descriptor = call.desc;
        if (owner.equals("java/util/Iterator") || owner.equals("java/util/ListIterator")) {
            return switch (name + descriptor) {
                case "next()Ljava/lang/Object;", "previous()Ljava/lang/Object;" ->
                        new ContainerSummary(ContainerOperation.READ, 0, -1);
                case "hasNext()Z", "hasPrevious()Z", "nextIndex()I", "previousIndex()I", "remove()V" ->
                        new ContainerSummary(ContainerOperation.OBSERVE, 0, -1);
                case "set(Ljava/lang/Object;)V", "add(Ljava/lang/Object;)V" ->
                        owner.equals("java/util/ListIterator") ? new ContainerSummary(ContainerOperation.WRITE, 0, 0) : null;
                default -> null;
            };
        }
        if (owner.equals("java/util/Map$Entry")) {
            return switch (name + descriptor) {
                case "getValue()Ljava/lang/Object;" -> new ContainerSummary(ContainerOperation.READ, 1, -1);
                case "getKey()Ljava/lang/Object;" -> new ContainerSummary(ContainerOperation.READ, 0, -1);
                case "setValue(Ljava/lang/Object;)Ljava/lang/Object;" -> new ContainerSummary(ContainerOperation.READ_WRITE, 1, 0);
                default -> null;
            };
        }
        if (mapOwner(owner)) {
            return resolveMap(name, descriptor, owner);
        }
        boolean queue = owner.equals("java/util/Queue") || owner.equals("java/util/Deque")
                || owner.equals("java/util/ArrayDeque") || owner.equals("java/util/LinkedList");
        boolean list = owner.equals("java/util/List") || owner.equals("java/util/ArrayList")
                || owner.equals("java/util/LinkedList");
        boolean collection = owner.equals("java/util/Collection") || owner.equals("java/util/Set")
                || owner.equals("java/util/SequencedCollection") || list;
        boolean container = queue || collection;
        boolean deque = owner.equals("java/util/Deque") || owner.equals("java/util/ArrayDeque")
                || owner.equals("java/util/LinkedList");
        if ((container || owner.equals("java/lang/Iterable")) && name.equals("iterator") && descriptor.equals("()Ljava/util/Iterator;")) {
            return view(ContainerView.ITERATOR);
        }
        if (deque && name.equals("descendingIterator") && descriptor.equals("()Ljava/util/Iterator;")) {
            return view(ContainerView.ITERATOR);
        }
        if (list && name.equals("listIterator") && (descriptor.equals("()Ljava/util/ListIterator;")
                || descriptor.equals("(I)Ljava/util/ListIterator;"))) return view(ContainerView.LIST_ITERATOR);
        if (list && name.equals("subList") && descriptor.equals("(II)Ljava/util/List;")) return view(ContainerView.LIST);
        if (container && name.equals("reversed") && (descriptor.equals("()Ljava/util/List;")
                || descriptor.equals("()Ljava/util/Deque;") || descriptor.equals("()Ljava/util/SequencedCollection;"))) {
            return view(list ? ContainerView.LIST : ContainerView.SEQUENCE);
        }
        if (name.equals("<init>") && supportsAllocation(owner)
                && (descriptor.equals("()V") || descriptor.equals("(I)V") && !owner.equals("java/util/LinkedList"))) {
            return new ContainerSummary(ContainerOperation.ALLOCATE, 0, -1);
        }
        if (queue && (name.equals("add") || name.equals("offer"))
                && descriptor.equals("(Ljava/lang/Object;)Z")) {
            return new ContainerSummary(ContainerOperation.WRITE, 0, 0);
        }
        if (deque && (name.equals("push") || name.equals("addFirst") || name.equals("addLast"))
                && descriptor.equals("(Ljava/lang/Object;)V")) {
            return new ContainerSummary(ContainerOperation.WRITE, 0, 0);
        }
        if (deque && (name.equals("offerFirst") || name.equals("offerLast"))
                && descriptor.equals("(Ljava/lang/Object;)Z")) {
            return new ContainerSummary(ContainerOperation.WRITE, 0, 0);
        }
        if (collection && name.equals("add") && descriptor.equals("(Ljava/lang/Object;)Z")) {
            return new ContainerSummary(ContainerOperation.WRITE, 0, 0);
        }
        if (list && name.equals("add")
                && descriptor.equals("(ILjava/lang/Object;)V")) {
            return new ContainerSummary(ContainerOperation.WRITE, 0, 1);
        }
        if (list && name.equals("set")
                && descriptor.equals("(ILjava/lang/Object;)Ljava/lang/Object;")) {
            return new ContainerSummary(ContainerOperation.READ_WRITE, 0, 1);
        }
        if (queue && (name.equals("poll") || name.equals("remove") || name.equals("pop"))
                && descriptor.equals("()Ljava/lang/Object;")) {
            return new ContainerSummary(ContainerOperation.READ_REMOVE, 0, -1);
        }
        if (queue && (name.equals("peek") || name.equals("element"))
                && descriptor.equals("()Ljava/lang/Object;")) {
            return new ContainerSummary(ContainerOperation.READ, 0, -1);
        }
        if (deque && (name.equals("pollFirst") || name.equals("pollLast")
                || name.equals("removeFirst") || name.equals("removeLast"))
                && descriptor.equals("()Ljava/lang/Object;")) {
            return new ContainerSummary(ContainerOperation.READ_REMOVE, 0, -1);
        }
        if ((deque || list) && (name.equals("getFirst") || name.equals("getLast")
                || deque && (name.equals("peekFirst") || name.equals("peekLast")))
                && descriptor.equals("()Ljava/lang/Object;")) {
            return new ContainerSummary(ContainerOperation.READ, 0, -1);
        }
        if (list && name.equals("get")
                && descriptor.equals("(I)Ljava/lang/Object;")) {
            return new ContainerSummary(ContainerOperation.READ, 0, -1);
        }
        if (list && name.equals("remove")
                && descriptor.equals("(I)Ljava/lang/Object;")) {
            return new ContainerSummary(ContainerOperation.READ_REMOVE, 0, -1);
        }
        if (container && ((name.equals("size") && descriptor.equals("()I"))
                || (name.equals("isEmpty") && descriptor.equals("()Z")))) {
            return new ContainerSummary(ContainerOperation.OBSERVE, 0, -1);
        }
        if (container && name.equals("clear") && descriptor.equals("()V")) {
            return new ContainerSummary(ContainerOperation.CLEAR, 0, -1);
        }
        return null;
    }

    private static ContainerSummary resolveMap(String name, String descriptor, String owner) {
        return switch (name + descriptor) {
            case "<init>()V", "<init>(I)V", "<init>(IF)V" -> supportsAllocation(owner)
                    ? new ContainerSummary(ContainerOperation.ALLOCATE, 1, -1) : null;
            case "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" ->
                    new ContainerSummary(ContainerOperation.READ_WRITE, 1, 1, 0, -1);
            case "get(Ljava/lang/Object;)Ljava/lang/Object;" ->
                    new ContainerSummary(ContainerOperation.READ, 1, -1, 0, -1);
            case "remove(Ljava/lang/Object;)Ljava/lang/Object;" ->
                    new ContainerSummary(ContainerOperation.READ_REMOVE, 1, -1, 0, -1);
            case "containsKey(Ljava/lang/Object;)Z" ->
                    new ContainerSummary(ContainerOperation.OBSERVE, 0, -1, 0, -1);
            case "containsValue(Ljava/lang/Object;)Z" ->
                    new ContainerSummary(ContainerOperation.OBSERVE, 1, -1, -1, 0);
            case "size()I", "isEmpty()Z" -> new ContainerSummary(ContainerOperation.OBSERVE, 1, -1);
            case "clear()V" -> new ContainerSummary(ContainerOperation.CLEAR, 1, -1);
            case "values()Ljava/util/Collection;" -> view(ContainerView.VALUES);
            case "keySet()Ljava/util/Set;" -> view(ContainerView.KEYS);
            case "entrySet()Ljava/util/Set;" -> view(ContainerView.ENTRIES);
            default -> null;
        };
    }

    static ContainerSummary resolve(MethodInsnNode call, StorageReferences receiver) {
        ContainerSummary summary = resolve(call);
        if (summary == null && call.getOpcode() != Opcodes.INVOKESTATIC
                && call.desc.equals("(Ljava/lang/Object;)Z") && (call.name.equals("contains") || call.name.equals("remove"))
                && (call.owner.equals("java/util/Collection") || call.owner.equals("java/util/Set")
                || call.owner.equals("java/util/List") || call.owner.equals("java/util/Deque")
                || call.owner.equals("java/util/SequencedCollection"))) {
            summary = new ContainerSummary(call.name.equals("remove") ? ContainerOperation.REMOVE_MATCH
                    : ContainerOperation.OBSERVE, 0, -1, -1, 0);
        }
        if (summary != null && summary.observedParameter() >= 0 && !mapOwner(call.owner)
                && receiver.references().stream().anyMatch(reference -> reference.kind() == ContainerReference.Kind.ROOT
                || reference.entries())) return null;
        if (summary != null && call.owner.equals("java/util/Iterator") && call.name.equals("next")) {
            boolean entries = receiver.references().stream().anyMatch(reference -> reference.kind() == ContainerReference.Kind.ENTRY_ITERATOR);
            boolean elements = receiver.references().stream().anyMatch(reference -> reference.kind() != ContainerReference.Kind.ENTRY_ITERATOR);
            if (entries && elements) return null;
            if (entries) return view(ContainerView.ENTRY);
        }
        if (summary != null && summary.writesElement()
                && receiver.references().stream().anyMatch(reference -> reference.kind() == ContainerReference.Kind.KEYS
                || reference.kind() == ContainerReference.Kind.VALUES || reference.kind() == ContainerReference.Kind.ENTRIES)) return null;
        return summary;
    }

    private static ContainerSummary view(ContainerView view) {
        return new ContainerSummary(ContainerOperation.VIEW, 0, -1, -1, -1, view);
    }

    static boolean mapOwner(String owner) {
        return owner.equals("java/util/Map") || owner.equals("java/util/HashMap")
                || owner.equals("java/util/LinkedHashMap");
    }

    boolean readsElement() {
        return operation == ContainerOperation.READ || operation == ContainerOperation.READ_REMOVE
                || operation == ContainerOperation.READ_WRITE;
    }

    boolean writesElement() {
        return operation == ContainerOperation.WRITE || operation == ContainerOperation.READ_WRITE;
    }

    static boolean supportsAllocation(String owner) {
        return owner.equals("java/util/ArrayDeque") || owner.equals("java/util/LinkedList")
                || owner.equals("java/util/ArrayList") || owner.equals("java/util/HashMap")
                || owner.equals("java/util/LinkedHashMap");
    }
}
