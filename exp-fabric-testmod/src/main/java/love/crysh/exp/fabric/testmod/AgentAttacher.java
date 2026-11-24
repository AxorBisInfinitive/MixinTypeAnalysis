package love.crysh.exp.fabric.testmod;

import com.sun.tools.attach.VirtualMachine;

import java.nio.file.Path;

public final class AgentAttacher {
    private AgentAttacher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected the target PID and agent JAR path");
        }

        String processId = args[0];
        Path agentJar = Path.of(args[1]);
        VirtualMachine target = VirtualMachine.attach(processId);
        try {
            target.loadAgent(agentJar.toString());
        } finally {
            target.detach();
        }
    }
}
