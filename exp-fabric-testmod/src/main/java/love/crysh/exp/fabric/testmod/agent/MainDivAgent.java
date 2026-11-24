package love.crysh.exp.fabric.testmod.agent;

import java.lang.instrument.Instrumentation;

public final class MainDivAgent {
    private MainDivAgent() {
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        instrumentation.addTransformer(new MainDivTransformer());
        System.out.println("ASM agent registered for class loading");
    }
}
