package love.crysh.mixinta.analysis.container;

import love.crysh.mixinta.model.MethodKey;

/** 抽象容器存储的位置身份。 */
public record StorageId(MethodKey method, int instruction, String descriptor) {
}
