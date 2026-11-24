package love.crysh.exp;

public final class Main {
    private Main() {
    }

    private static final int X = 12;
    private static final int Y = 3;
    private static final int Z = 4;

    public static void main(String[] args) {
        int r1 = add(X, Y);
        int r2 = div(r1, Z);
        System.out.println(r2);
    }

    private static int add(int a, int b) {
        return a + b;
    }

    private static int div(int a, int b) {
        return a / b;
    }
}
