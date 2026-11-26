package love.crysh.mixinta.analysis;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

/** 标准容器操作的元素流摘要。 */
record ContainerSummary(ContainerOperation operation, int channel, int elementParameter,
        int keyParameter, int observedParameter) {
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
        if (mapOwner(owner)) {
            return resolveMap(name, descriptor, owner);
        }
        boolean queue = owner.equals("java/util/Queue") || owner.equals("java/util/Deque")
                || owner.equals("java/util/ArrayDeque") || owner.equals("java/util/LinkedList");
        boolean list = owner.equals("java/util/List") || owner.equals("java/util/ArrayList")
                || owner.equals("java/util/LinkedList");
        boolean collection = owner.equals("java/util/Collection") || list;
        boolean container = queue || collection;
        boolean deque = owner.equals("java/util/Deque") || owner.equals("java/util/ArrayDeque")
                || owner.equals("java/util/LinkedList");
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
            default -> null;
        };
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
