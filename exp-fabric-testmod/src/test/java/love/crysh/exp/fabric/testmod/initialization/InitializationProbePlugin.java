package love.crysh.exp.fabric.testmod.initialization;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.util.List;
import java.util.Set;

public final class InitializationProbePlugin implements IMixinConfigPlugin {
    public static int loads;
    public static int applications;
    public static int extensionsApplied;
    private Set<String> targetMappings;
    private static Set<String> selectedTargets;
    private static org.spongepowered.asm.mixin.extensibility.IMixinConfig selectedConfiguration;

    @Override
    public void onLoad(String mixinPackage) {
        loads++;
        System.out.println("PLUGIN_LOAD state=" + InitializationProbeRuntime.state());
        if (Boolean.getBoolean("probe.expandPreparation") && !Boolean.getBoolean("probe.existingRoot")) {
            net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher()
                    .addToClassPath(java.nio.file.Path.of(System.getProperty("probe.extraRoot")));
        }
        if (Boolean.getBoolean("probe.concurrentConfig")) requestCallerOnAnotherThread();
        if (Boolean.getBoolean("probe.reentrant")) {
            try {
                Class.forName("fixture.business.Subject", false, getClass().getClassLoader());
            } catch (Throwable failure) {
                System.out.println("PLUGIN_CAUGHT=" + failure);
            }
        }
        IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        ((Extensions) transformer.getExtensions()).add(new IExtension() {
            @Override
            public boolean checkActive(MixinEnvironment environment) {
                return true;
            }

            @Override
            public void preApply(ITargetClassContext context) { }

            @Override
            public void postApply(ITargetClassContext context) {
                if (context.getClassNode().name.equals("fixture/business/Subject")) extensionsApplied++;
            }

            @Override
            public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) { }
        });
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (System.getProperty("probe.discovery", "").startsWith("partial")
                && targetClassName.equals("fixture.business.OtherSubject")) return false;
        return !System.getProperty("probe.discovery", "").startsWith("excluded");
    }

    @Override
    public void acceptTargets(Set<String> targets, Set<String> others) {
        targetMappings = targets;
        selectedTargets = targets;
        if (System.getProperty("probe.discovery", "").equals("removed")) targets.clear();
    }

    @SuppressWarnings("unchecked")
    public static void mutateBindings(String mutation) throws Exception {
        if (mutation.equals("remove")) {
            selectedTargets.clear();
            return;
        }
        var method = selectedConfiguration.getClass().getDeclaredMethod("getMixinsFor", String.class);
        method.setAccessible(true);
        var bindings = (List<Object>) method.invoke(selectedConfiguration, "fixture.business.Subject");
        if (mutation.equals("reorder")) java.util.Collections.reverse(bindings);
        else bindings.set(0, bindings.get(1));
    }

    @Override
    public List<String> getMixins() {
        if (Boolean.getBoolean("probe.expandPreparation")) {
            if (Boolean.getBoolean("probe.existingRoot")) {
                var root = java.nio.file.Path.of(System.getProperty("probe.extraRoot"));
                try { java.nio.file.Files.move(root.resolve("fixture/expanded/Caller.class.staged"), root.resolve("fixture/expanded/Caller.class")); }
                catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
            org.spongepowered.asm.mixin.Mixins.addConfiguration("extra.mixins.json");
        }
        return System.getProperty("probe.discovery", "").equals("plugin") ? List.of("SubjectMixin") : null;
    }

    @Override
    public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) { }

    @Override
    public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {
        selectedConfiguration = info.getConfig();
        applications++;
        if (Boolean.getBoolean("probe.mutateBindings")) targetMappings.clear();
        if (Boolean.getBoolean("probe.mutateSources")) {
            net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher()
                    .addToClassPath(java.nio.file.Path.of(System.getProperty("probe.extraRoot")));
        }
        if (Boolean.getBoolean("probe.concurrentPlugin")) requestCallerOnAnotherThread();
        if (Boolean.getBoolean("probe.introducedCall")) {
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "introduced", "()I", null, null);
            method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 10));
            method.instructions.add(new InsnNode(Opcodes.ICONST_4));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "div", "(II)I", false));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 2;
            node.methods.add(method);
        }
        if (Boolean.getBoolean("probe.changeOriginal")) {
            node.methods.stream().filter(method -> method.name.equals("div") && method.desc.equals("(II)I"))
                    .findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
        }
    }

    private void requestCallerOnAnotherThread() {
        Thread thread = new Thread(() -> {
            try { Class.forName(System.getProperty("probe.concurrentClass", "fixture.business.Caller"), false, getClass().getClassLoader()); }
            catch (Throwable failure) { System.out.println("PLUGIN_CONCURRENT_CAUGHT=" + failure); }
        }, "plugin-participant-loader");
        thread.start();
        try { thread.join(5000); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
        if (thread.isAlive()) throw new AssertionError("Plugin participant request deadlocked");
    }
}
