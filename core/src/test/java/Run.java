import org.openpump.SelfTest;
public class Run {
    public static void main(String[] a) {
        SelfTest.Result r = SelfTest.run();
        System.out.println("SELF-TEST: " + r.summary());
        for (int i = 0; i < r.failures.size(); i++)
            System.out.println("  FAIL: " + r.failures.get(i));
        System.exit(r.failed == 0 ? 0 : 1);
    }
}
