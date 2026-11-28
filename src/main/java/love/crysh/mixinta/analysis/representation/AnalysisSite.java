package love.crysh.mixinta.analysis.representation;

import love.crysh.mixinta.analysis.container.ContainerFlow;
import love.crysh.mixinta.model.MethodKey;
import love.crysh.mixinta.model.ProgramPoint;

record AnalysisSite(ContainerFlow flow, int index) {
    MethodKey method() {
        return flow.source().key();
    }

    String describe() {
        return method() + " instruction=" + index;
    }

    String symbol() {
        return flow.source().point(index).symbol();
    }

    ProgramPoint point() {
        return flow.source().point(index);
    }

    ProgramPoint point(String detail) {
        return new ProgramPoint(method(), index, symbol(), detail);
    }
}
