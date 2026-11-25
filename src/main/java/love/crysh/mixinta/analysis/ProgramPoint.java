package love.crysh.mixinta.analysis;

import java.util.Objects;

/** 字节码传播图中的一个方法位置。 */
public record ProgramPoint(MethodKey method, int instruction, String symbol, String detail) {
    public ProgramPoint {
        symbol = symbol == null ? "" : symbol;
        detail = detail == null ? "" : detail;
    }

    public ProgramPoint(MethodKey method, int instruction, String symbol) {
        this(method, instruction, symbol, "");
    }

    public String key() {
        return (method == null ? symbol : method.toString()) + "#" + instruction + "#" + detail;
    }

    static ProgramPoint parse(String value) {
        String text = Objects.requireNonNullElse(value, "");
        String detail = "";
        int detailMarker = text.indexOf(" parameter=");
        if (detailMarker >= 0) {
            detail = text.substring(detailMarker + 1);
            text = text.substring(0, detailMarker);
        }
        int marker = text.lastIndexOf(" instruction=");
        int instruction = -1;
        if (marker >= 0) {
            try {
                instruction = Integer.parseInt(text.substring(marker + 13));
            } catch (NumberFormatException ignored) {
                instruction = -1;
            }
            text = text.substring(0, marker);
        }
        MethodKey method = parseMethod(text);
        return new ProgramPoint(method, instruction, text, detail);
    }

    private static MethodKey parseMethod(String value) {
        int descriptor = value.indexOf('(');
        int separator = descriptor < 0 ? -1 : value.lastIndexOf('.', descriptor);
        if (separator < 0 || descriptor < 0) {
            return null;
        }
        String owner = value.substring(0, separator).replace('.', '/');
        String name = value.substring(separator + 1, descriptor);
        return new MethodKey(owner, name, value.substring(descriptor));
    }
}
