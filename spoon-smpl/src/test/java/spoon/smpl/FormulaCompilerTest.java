package spoon.smpl;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static spoon.smpl.TestUtils.*;

/**
 * This suite is intentionally left very sparse as the current idea is that FormulaCompiler
 * will be thoroughly tested by the end-to-end SmPL patch application tests.
 *
 * Tests for bugs specific to the FormulaCompiler should go in this suite.
 */
public class FormulaCompilerTest {
    @Test
    public void testParentIdGuardGeneratedForDotsInMethodRootBug() {
        SmPLMethodCFG cfg = methodCfg(parseMethod("void m() {\n" +
                                                  "  a();\n" +
                                                  "  " + SmPLJavaDSL.getDotsStatementElementName() + "();\n" +
                                                  "  b();\n" +
                                                  "}\n"));

        FormulaCompiler compiler = new FormulaCompiler(cfg, makeMetavars(), intSet(2,3,4), new AnchoredOperationsMap());
        assertFalse(compiler.compileFormula().toString().contains("Metadata(parent->__parent-1__)"));
    }
}
