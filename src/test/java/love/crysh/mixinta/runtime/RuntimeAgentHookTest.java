package love.crysh.mixinta.runtime;

import love.crysh.mixinta.runtime.agent.RuntimeAgent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeAgentHookTest {
    @Test
    void verifiesHooksAgainstTheShippedMixinImplementation() throws Exception {
        var constructor = RuntimeAgent.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RuntimeAgent agent = constructor.newInstance();
        for (String name : new String[] {"MixinInfo", "MixinConfig", "MixinTransformer", "MixinInfo$State", "MixinApplicatorStandard"}) {
            String internal = "org/spongepowered/asm/mixin/transformer/" + name;
            byte[] input;
            try (var stream = RuntimeAgent.class.getResourceAsStream("/" + internal + ".class")) {
                assertNotNull(stream);
                input = stream.readAllBytes();
            }
            byte[] output = agent.transform(RuntimeAgent.class.getClassLoader(), internal, null, null, input);
            assertNotNull(output, name);
            StringWriter failures = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(output), false, new PrintWriter(failures));
            assertEquals("", failures.toString(), name);
        }
    }
}
