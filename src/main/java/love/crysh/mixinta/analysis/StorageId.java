package love.crysh.mixinta.analysis;

/** 抽象容器存储的位置身份。 */
record StorageId(MethodKey method, int instruction, String descriptor) {
}
