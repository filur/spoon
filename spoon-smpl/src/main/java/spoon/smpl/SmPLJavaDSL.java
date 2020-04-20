package spoon.smpl;

import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilities to define and facilitate working with the SmPL Java DSL.
 */
public class SmPLJavaDSL {
    /**
     * Name of the field used to hold the name of the rule.
     */
    private static final String ruleNameFieldName = "__SmPLRuleName__";

    /**
     * Get the name of the field used to hold the name of the rule.
     * @return Name of the field used to hold the name of the rule
     */
    public static String getRuleNameFieldName() {
        return ruleNameFieldName;
    }

    /**
     * Name of the method used to encode metavariable definitions.
     */
    private static final String metavarsMethodName = "__SmPLMetavars__";

    /**
     * Get name of the method used to encode metavariable definitions.
     * @return Name of the method used to encode metavariable definitions
     */
    public static String getMetavarsMethodName() {
        return metavarsMethodName;
    }

    /**
     * Name of executable used to encode dots statements.
     */
    private static final String dotsElementName = "__SmPLDots__";

    /**
     * Get name of executable used to encode dots statements.
     * @return Name of executable used to encode dots statements
     */
    public static String getDotsElementName() {
        return dotsElementName;
    }

    /**
     * Name of executable used to encode "when != x" constraints on dots.
     */
    private static final String dotsWhenNotEqualName = "whenNotEqual";

    /**
     * Get name of executable used to encode "when != x" constraints on dots.
     * @return Name of executable used to encode "when != x" constraints on dots
     */
    public static String getDotsWhenNotEqualName() {
        return dotsWhenNotEqualName;
    }

    /**
     * Name of executable used to encode deleted lines available for anchoring.
     */
    private static final String deletionAnchorName = "__SmPLDeletion__";

    /**
     * Get name of executable used to encode deleted lines available for anchoring.
     * @return Name of executable used to encode deleted lines available for anchoring
     */
    public static String getDeletionAnchorName() {
        return deletionAnchorName;
    }

    /**
     * Check if a given element represents a deletion anchor in the SmPL Java DSL.
     * @param e Element to check
     * @return True if element represents a deletion anchor, false otherwise
     */
    public static boolean isDeletionAnchor(CtElement e) {
        return e instanceof CtInvocation<?>
               && ((CtInvocation<?>) e).getExecutable().getSimpleName().equals(deletionAnchorName);
    }

    /**
     * Check if a given element represents an SmPL dots construct in the SmPL Java DSL.
     * @param e Element to check
     * @return True if element represents an SmPL dots construct, false otherwise
     */
    public static boolean isDots(CtElement e) {
        return e instanceof CtInvocation<?>
               && ((CtInvocation<?>) e).getExecutable().getSimpleName().equals(dotsElementName);
    }

    /**
     * Given a CtClass in the SmPL Java DSL, find the method encoding the matching/transformation
     * rule.
     * @param ctClass Class in SmPL Java DSL
     * @return Method encoding the matching/transformation rule
     */
    public static CtMethod<?> getRuleMethod(CtClass<?> ctClass) {
        for (CtMethod<?> method : ctClass.getMethods()) {
            if (!method.getSimpleName().equals(metavarsMethodName)) {
                return method;
            }
        }

        return null;
    }

    /**
     * Given a CtInvocation representing an SmPL dots construct in the SmPL Java DSL, collect
     * all arguments provided in "when != x" constraints.
     * @param dots Element representing an SmPL dots construct
     * @return List of arguments x provided in "when != x" constraints
     */
    public static List<String> getWhenNotEquals(CtInvocation<?> dots) {
        List<String> result = new ArrayList<>();

        for (CtExpression<?> stmt : dots.getArguments()) {
            if (isWhenNotEquals(stmt)) {
                CtVariableRead<?> read = (CtVariableRead<?>) ((CtInvocation<?>) stmt).getArguments().get(0);
                result.add(read.getVariable().getSimpleName());
            }
        }

        return result;
    }

    /**
     * Check if a given element represents a "when != x" constraint on dots in the SmPL
     * Java DSL.
     * @param e Element to check
     * @return True if element represents a "when != x" constraint, false otherwise
     */
    public static boolean isWhenNotEquals(CtElement e) {
        return e instanceof CtInvocation<?>
               && ((CtInvocation<?>) e).getExecutable().getSimpleName().equals(dotsWhenNotEqualName);
    }
}
