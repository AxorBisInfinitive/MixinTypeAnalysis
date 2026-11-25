package love.crysh.mixinta.runtime.agent;

import com.sun.tools.attach.VirtualMachine;

public final class AgentAttacher {
    private AgentAttacher() {
    }

    public static void main(String[] arguments) throws Exception {
        VirtualMachine target = VirtualMachine.attach(arguments[0]);
        try {
            target.loadAgent(arguments[1]);
        } finally {
            target.detach();
        }
    }
}
