package spoon.smpl;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;

/**
 * Collection of static debug utility methods.
 */
public class DebugUtils {
    /**
     * Given a CtClass of an SmPL rule in the SmPL Java DSL, pretty-print format
     * the rule method adding line numbers to statements.
     * @param ctClass SmPL rule in SmPL Java DSL
     * @return Pretty-print formatted string
     */
    public static String printRuleMethodWithLines(CtClass<?> ctClass) {
        CtMethod<?> ruleMethod = SmPLJavaDSL.getRuleMethod(ctClass);
        return printRuleMethodWithLinesInner(ruleMethod.getBody(), 0).toString();
    }

    /**
     * Recursive helper method for printRuleMethodWithLines.
     * @param e Element to pretty-print format
     * @param indent Current indentation level
     * @return Pretty-print formatted string
     */
    private static StringBuilder printRuleMethodWithLinesInner(CtElement e, int indent) {
        StringBuilder sb = new StringBuilder();
        String lineStr;

        if (e instanceof CtBlock) {
            sb.append("{\n");

            for (CtStatement stmt : ((CtBlock<?>) e).getStatements()) {
                sb.append(printRuleMethodWithLinesInner(stmt, indent + 4));
            }

            for (int n = 0; n < indent; ++n) { sb.append(" "); }
            sb.append("}\n");
        } else if (e instanceof CtIf) {
            lineStr = Integer.toString(e.getPosition().getLine());
            sb.append(lineStr);

            for (int n = 0; n < indent - lineStr.length(); ++n) { sb.append(" "); }
            sb.append("if (")
                    .append(((CtIf) e).getCondition().toString())
                    .append(") ");

            sb.append(printRuleMethodWithLinesInner(((CtIf) e).getThenStatement(), indent));

            if (((CtIf) e).getElseStatement() != null) {
                for (int n = 0; n < indent; ++n) { sb.append(" "); }
                sb.append("else ").append(printRuleMethodWithLinesInner(((CtIf) e).getElseStatement(), indent));
            }
        } else {
            lineStr = Integer.toString(e.getPosition().getLine());
            sb.append(lineStr);
            for (int n = 0; n < indent - lineStr.length(); ++n) { sb.append(" "); }
            sb.append(e.toString()).append("\n");
        }

        return sb;
    }

    /**
     * Format text for pretty-printing.
     * @param text Text to format
     * @return Formatted text
     */
    public static String prettify(String text) {
        return prettify(text, '{', '}', 4, false);
    }

    /**
     * Format text for pretty-printing.
     * @param text Text to format
     * @param open Indentation-increasing character
     * @param close Indentation-decreasing character
     * @param indentSize Indentation size
     * @param addNewlines Add newlines after indentation-altering characters?
     * @return Formatted text
     */
    public static String prettify(String text, char open, char close, int indentSize, boolean addNewlines) {
        StringBuilder result = new StringBuilder();

        int indent = 0;
        boolean doIndent = false;

        for (char c : text.toCharArray()) {
            if (c == close) {
                indent -= 1;

                if (addNewlines) {
                    result.append('\n');
                    doIndent = true;
                }
            }

            if (doIndent) {
                doIndent = false;

                for (int i = 0; i < indent; ++i) {
                    for (int j = 0; j < indentSize; ++j) {
                        result.append(" ");
                    }
                }
            }

            result.append(c);

            if (c == '\n') {
                doIndent = true;
            }

            if (c == open) {
                indent += 1;

                if (addNewlines) {
                    result.append('\n');
                    doIndent = true;
                }
            }
        }

        return result.toString();
    }
}
