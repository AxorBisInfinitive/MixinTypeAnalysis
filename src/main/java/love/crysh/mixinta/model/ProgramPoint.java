package love.crysh.mixinta.model;

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
        int marker = text.lastIndexOf(" instruction=");
        int instruction = -1;
        if (marker >= 0) {
            String suffix = text.substring(marker + 13);
            int separator = suffix.indexOf(' ');
            try {
                instruction = Integer.parseInt(separator < 0 ? suffix : suffix.substring(0, separator));
            } catch (NumberFormatException ignored) {
                instruction = -1;
            }
            if (separator >= 0) detail = suffix.substring(separator + 1);
            text = text.substring(0, marker);
        } else {
            int detailMarker = text.indexOf(" parameter=");
            if (detailMarker < 0) detailMarker = text.indexOf(" operand=");
            if (detailMarker < 0) detailMarker = text.indexOf(" capture=");
            if (detailMarker < 0) detailMarker = text.indexOf(" channel=");
            if (detailMarker < 0) detailMarker = text.indexOf(" receiver");
            if (detailMarker < 0) detailMarker = text.indexOf(" use");
            if (detailMarker >= 0) {
                detail = text.substring(detailMarker + 1).strip();
                text = text.substring(0, detailMarker);
            }
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
