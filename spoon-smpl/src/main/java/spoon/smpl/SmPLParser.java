package spoon.smpl;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import spoon.Launcher;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.visitor.CtScanner;
import spoon.smpl.formula.*;
import spoon.smpl.metavars.*;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * SmPLParser contains methods for rewriting SmPL text input to an SmPL Java DSL (domain-specific language)
 * and to compile this DSL into an SmPLRule instance.
 */
public class SmPLParser {
    /**
     * Parse an SmPL rule given in plain text.
     *
     * @param smpl SmPL rule in plain text
     * @return SmPLRule instance corresponding to input
     */
    public static SmPLRule parse(String smpl) {
        List<String> separated = separate(rewrite(smpl));

        CtClass<?> dels = Launcher.parseClass(separated.get(0));
        CtClass<?> adds = Launcher.parseClass(separated.get(1));

        Set<Integer> delsLines = collectStatementLines(dels);
        Set<Integer> addsLines = collectStatementLines(adds);

        Set<Integer> commonLines = new HashSet<>(delsLines);
        commonLines.retainAll(addsLines);

        AnchoredOperations anchoredOperations = anchorAdditions(adds, commonLines);

        Set<Integer> containedCommonLines = findContainedCommonLines(adds, commonLines);
        commonLines.removeAll(containedCommonLines);

        class DeletionAnchorRemover extends CtScanner {
            @Override
            protected void enter(CtElement e) {
                if (SmPLJavaDSL.isDeletionAnchor(e)) {
                    e.delete();
                }
            }
        }

        new DeletionAnchorRemover().scan(adds);

        return compile(dels, commonLines, anchoredOperations);
    }

    /**
     * Compile a given AST in the SmPL Java DSL.
     *
     * @param ast AST to compile
     * @return SmPLRule instance
     */
    public static SmPLRule compile(CtClass<?> ast, Set<Integer> commonLines, AnchoredOperations additions) {
        String ruleName = null;

        if (ast.getDeclaredField(SmPLJavaDSL.getRuleNameFieldName()) != null) {
            ruleName = ((CtLiteral<?>) ast.getDeclaredField(SmPLJavaDSL.getRuleNameFieldName())
                                          .getFieldDeclaration()
                                          .getAssignment()).getValue().toString();
        }

        Map<String, MetavariableConstraint> metavars = new HashMap<>();

        if (ast.getMethodsByName(SmPLJavaDSL.getMetavarsMethodName()).size() != 0) {
            CtMethod<?> mth = ast.getMethodsByName(SmPLJavaDSL.getMetavarsMethodName()).get(0);

            for (CtElement e : mth.getBody().getStatements()) {
                CtInvocation<?> invocation = (CtInvocation<?>) e;
                CtElement arg = invocation.getArguments().get(0);
                String varname = null;

                if (arg instanceof CtFieldRead<?>) {
                    varname = ((CtFieldRead<?>) arg).getVariable().getSimpleName();
                } else if (arg instanceof CtTypeAccess<?>) {
                    varname = ((CtTypeAccess<?>) arg).getAccessedType().getSimpleName();
                } else {
                    throw new IllegalArgumentException("Unable to extract metavariable name at <position>");
                }

                switch (invocation.getExecutable().getSimpleName()) {
                    case "type":
                        metavars.put(varname, new TypeConstraint());
                        break;

                    case "identifier":
                        metavars.put(varname, new IdentifierConstraint());
                        break;

                    case "constant":
                        metavars.put(varname, new ConstantConstraint());
                        break;

                    case "expression":
                        metavars.put(varname, new ExpressionConstraint());
                        break;

                    default:
                        throw new IllegalArgumentException("Unknown metavariable type " + invocation.getExecutable().getSimpleName());
                }
            }
        }

        CtMethod<?> ruleMethod = SmPLJavaDSL.getRuleMethod(ast);

        if (ruleMethod == null) {
            // A completely empty rule matches nothing
            return new SmPLRuleImpl(new Not(new True()), metavars);
        }

        FormulaCompiler fc = new FormulaCompiler(new SmPLMethodCFG(ruleMethod), metavars, commonLines, additions);
        SmPLRule rule = new SmPLRuleImpl(fc.compileFormula(), metavars);
        rule.setName(ruleName);

        return rule;
    }

    /**
     * Rewrite an SmPL rule given in plain text into an SmPL Java DSL.
     *
     * @param text SmPL rule in plain text
     * @return Plain text Java code in SmPL Java DSL
     */
    public static String rewrite(String text) {
        class Result {
            public Result() {
                out = new StringBuilder();
                hasMethodHeader = false;
            }

            public StringBuilder out;
            public boolean hasMethodHeader;
        }

        class RewriteRule {
            public RewriteRule(String name, String regex, Consumer<Stack<List<RewriteRule>>> contextOp, BiConsumer<Result, Matcher> outputOp) {
                this.name = name;
                this.pattern = Pattern.compile(regex);
                this.contextOp = contextOp;
                this.outputOp = outputOp;
            }

            public final String name;
            public final Pattern pattern;
            public final Consumer<Stack<List<RewriteRule>>> contextOp;
            public final BiConsumer<Result, Matcher> outputOp;
        }

        if (text.length() < 1) {
            throw new RuntimeException("Empty input");
        }

        List<RewriteRule> init = new ArrayList<>();
        List<RewriteRule> metavars = new ArrayList<>();
        List<RewriteRule> code = new ArrayList<>();
        List<RewriteRule> body = new ArrayList<>();
        List<RewriteRule> dots = new ArrayList<>();

        // TODO: escape character
        // TODO: strings

        // Initial context
        init.add(new RewriteRule("atat", "(?s)^@@",
                (ctx) -> { ctx.pop(); ctx.push(metavars); },
                (result, match) -> { result.out.append("void __SmPLMetavars__() {\n"); }));

        init.add(new RewriteRule("atat_rulename", "(?s)^@\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*@",
                (ctx) -> { ctx.pop(); ctx.push(metavars); },
                (result, match) -> {
                    result.out.append("String __SmPLRuleName__ = \"").append(match.group(1)).append("\";\n");
                    result.out.append("void __SmPLMetavars__() {\n");
                }));

        // Metavars context
        metavars.add(new RewriteRule("whitespace", "(?s)^\\s+",
                (ctx) -> {},
                (result, match) -> {}));

        metavars.add(new RewriteRule("atat", "(?s)^@@([^\\S\n]*\n)?",
                (ctx) -> { ctx.pop(); ctx.push(code); },
                (result, match) -> { result.out.append("}\n"); }));

        metavars.add(new RewriteRule("identifier", "(?s)^identifier\\s+([^;]+);",
                (ctx) -> {},
                (result, match) -> {
                    for (String id : match.group(1).split("\\s*,\\s*")) {
                        result.out.append("identifier(").append(id).append(");\n");
                    }
                }));

        metavars.add(new RewriteRule("type", "(?s)^type\\s+([^;]+);",
                (ctx) -> {},
                (result, match) -> {
                    for (String id : match.group(1).split("\\s*,\\s*")) {
                        result.out.append("type(").append(id).append(");\n");
                    }
                }));

        metavars.add(new RewriteRule("constant", "(?s)^constant\\s+([^;]+);",
                (ctx) -> {},
                (result, match) -> {
                    for (String id : match.group(1).split("\\s*,\\s*")) {
                        result.out.append("constant(").append(id).append(");\n");
                    }
                }));

        metavars.add(new RewriteRule("expression", "(?s)^expression\\s+([^;]+);",
                (ctx) -> {},
                (result, match) -> {
                    for (String id : match.group(1).split("\\s*,\\s*")) {
                        result.out.append("expression(").append(id).append(");\n");
                    }
                }));

        // TODO: call this method header context instead?
        // Code context
        /*code.add(new RewriteRule("whitespace", "(?s)^\\s+",
                (ctx) -> {},
                (result, match) -> {}));*/

        // TODO: separate context for the signature
        code.add(new RewriteRule("method_decl", "(?s)^[A-Za-z]+\\s+[A-Za-z]+\\s*\\([A-Za-z,\\s]*\\)\\s*\\{",
                (ctx) -> { ctx.pop(); ctx.push(body); },
                (result, match) -> {
                    result.out.append(match.group());
                    result.hasMethodHeader = true;
                }));

        code.add(new RewriteRule("dots", "(?s)^\\.\\.\\.",
                (ctx) -> { ctx.pop(); ctx.push(body); ctx.push(dots); },
                (result, match) -> {
                    result.out.append("__SmPLUndeclared__ method() {\n");
                    result.out.append("__SmPLDots__(");
                    result.hasMethodHeader = true;
                }));

        code.add(new RewriteRule("anychar", "(?s)^.",
                (ctx) -> { ctx.pop(); ctx.push(body); },
                (result, match) -> {
                    result.out.append("__SmPLUndeclared__ method() {\n");
                    result.out.append(match.group());
                    result.hasMethodHeader = true;
                }));

        // Method body context
        body.add(new RewriteRule("dots", "(?s)^\\.\\.\\.",
                (ctx) -> { ctx.push(dots); },
                (result, match) -> { result.out.append("__SmPLDots__("); }));

        body.add(new RewriteRule("anychar", "(?s)^.",
                (ctx) -> {},
                (result, match) -> { result.out.append(match.group()); }));

        // Dots context
        dots.add(new RewriteRule("whitespace", "(?s)^\\s+",
                (ctx) -> {},
                (result, match) -> {}));

        dots.add(new RewriteRule("when_neq", "(?s)^when\\s*!=\\s*([a-z]+)",
                (ctx) -> {},
                (result, match) -> {
                    if (result.out.charAt(result.out.length() - 1) == ')') {
                        result.out.append(",");
                    }

                    result.out.append("whenNotEqual(").append(match.group(1)).append(")");
                }));

        dots.add(new RewriteRule("anychar", "(?s)^.",
                (ctx) -> { ctx.pop(); },
                (result, match) -> { result.out.append(");\n").append(match.group()); }));

        Result result = new Result();
        result.out.append("class SmPLRule {\n");

        Stack<List<RewriteRule>> context = new Stack<>();
        context.push(init);

        int pos = 0;

        while (pos < text.length()) {
            List<String> expected = new ArrayList<>();
            boolean foundSomething = false;
            String texthere = text.substring(pos);

            List<RewriteRule> rules = context.peek();

            for (RewriteRule rule : rules) {
                expected.add(rule.name);
                Matcher matcher = rule.pattern.matcher(texthere);

                if (matcher.find()) {
                    rule.contextOp.accept(context);
                    rule.outputOp.accept(result, matcher);

                    pos += matcher.end();
                    foundSomething = true;
                    break;
                }
            }

            if (!foundSomething) {
                throw new RuntimeException("Parse error at offset " + Integer.toString(pos) + ", expected one of " + expected.toString());
            }
        }

        if (result.hasMethodHeader) {
            result.out.append("}\n");
        }

        result.out.append("}\n");

        return result.out.toString();
    }

    /**
     * Separate an SmPL patch given in plain text into two versions where one removes all
     * added lines retaining only deletions and context lines, and the other replaces all
     * deleted lines with a dummy placeholder for anchoring.
     *
     * @param input SmPL patch in plain text to separate
     * @return List of two Strings containing the two separated versions
     */
    private static List<String> separate(String input) {
        StringBuilder dels = new StringBuilder();
        StringBuilder adds = new StringBuilder();

        for (String str : input.split("\n")) {
            if (str.charAt(0) == '-') {
                dels.append(' ').append(str.substring(1)).append("\n");
                if (str.contains(SmPLJavaDSL.getDotsElementName() + "();")) {
                    adds.append("\n");
                } else {
                    adds.append(SmPLJavaDSL.getDeletionAnchorName()).append("();\n");
                }
            } else if (str.charAt(0) == '+') {
                dels.append("\n");
                adds.append(' ').append(str.substring(1)).append("\n");
            } else {
                dels.append(str).append("\n");
                adds.append(str).append("\n");
            }
        }

        return Arrays.asList(dels.toString(), adds.toString());
    }

    /**
     * Find appropriate anchors for all addition operations.
     *
     * @param e SmPL rule class in the SmPL Java DSL
     * @param commonLines Set of context lines common to both the deletions and the additions ASTs
     * @return Map of anchors to lists of operations
     */
    private static AnchoredOperations anchorAdditions(CtClass<?> e, Set<Integer> commonLines) {
        CtMethod<?> ruleMethod = SmPLJavaDSL.getRuleMethod(e);
        return anchorAdditions(ruleMethod.getBody(), commonLines, 0, null);
    }

    /**
     * Recursive helper function for anchorAdditions.
     *
     * @param e Element to scan
     * @param commonLines Set of context lines common to both the deletions and the additions ASTs
     * @param blockAnchor Line number of statement seen as current block-insert anchor.
     * @param context Anchoring context, one of null, "methodHeader", "trueBranch" or "falseBranch"
     * @return Map of anchors to lists of operations
     */
    private static AnchoredOperations anchorAdditions(CtElement e, Set<Integer> commonLines, int blockAnchor, String context) {
        AnchoredOperations result = new AnchoredOperations();

        // Temporary storage for operations until an anchor is found
        List<Pair<InsertIntoBlockOperation.Anchor, CtElement>> unanchored = new ArrayList<>();

        // Less temporary storage for operations encountered without an anchor that cannot be anchored
        // to the next encountered anchorable statement, to be dealt with later
        List<Pair<InsertIntoBlockOperation.Anchor, CtElement>> unanchoredCommitted = new ArrayList<>();

        int elementAnchor = 0;
        boolean isAfterDots = false;

        if (e instanceof CtBlock<?>) {
            for (CtStatement stmt : ((CtBlock<?>) e).getStatements()) {
                int stmtLine = stmt.getPosition().getLine();

                if (SmPLJavaDSL.isDeletionAnchor(stmt) || commonLines.contains(stmtLine)) {
                    if (!SmPLJavaDSL.isDots(stmt)) {
                        isAfterDots = false;
                        elementAnchor = stmtLine;

                        // The InsertIntoBlockOperation.Anchor is irrelevant here
                        for (Pair<InsertIntoBlockOperation.Anchor, CtElement> element : unanchored) {
                            result.addKeyIfNotExists(elementAnchor);
                            result.get(elementAnchor).add(new PrependOperation(element.getRight()));
                        }
                    } else {
                        unanchoredCommitted.addAll(unanchored);
                        isAfterDots = true;
                    }

                    unanchored.clear();

                    // Process branches of if-then-else statements
                    if (stmt instanceof CtIf) {
                        CtIf ctIf = (CtIf) stmt;
                        result.join(anchorAdditions(ctIf.getThenStatement(), commonLines, stmtLine, "trueBranch"));

                        if (ctIf.getElseStatement() != null) {
                            result.join(anchorAdditions(((CtIf) stmt).getElseStatement(), commonLines, stmtLine, "falseBranch"));
                        }
                    }
                } else {
                    if (elementAnchor != 0) {
                        result.addKeyIfNotExists(elementAnchor);
                        result.get(elementAnchor).add(new AppendOperation(stmt));
                    } else {
                        unanchored.add(new ImmutablePair<>(isAfterDots ? InsertIntoBlockOperation.Anchor.BOTTOM
                                                                       : InsertIntoBlockOperation.Anchor.TOP, stmt));
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("cannot handle " + e.getClass().toString());
        }

        unanchored.addAll(unanchoredCommitted);

        // Process unanchored elements
        if (unanchored.size() > 0) {
            result.addKeyIfNotExists(blockAnchor);

            for (Pair<InsertIntoBlockOperation.Anchor, CtElement> element : unanchored) {
                switch (context) {
                    case "methodHeader":
                        result.get(blockAnchor)
                              .add(new InsertIntoBlockOperation(InsertIntoBlockOperation.BlockType.METHODBODY,
                                                                InsertIntoBlockOperation.Anchor.TOP,
                                                                (CtStatement) element.getRight()));
                        break;
                    case "trueBranch":
                        result.get(blockAnchor)
                              .add(new InsertIntoBlockOperation(InsertIntoBlockOperation.BlockType.TRUEBRANCH,
                                                                element.getLeft(),
                                                                (CtStatement) element.getRight()));
                        break;
                    case "falseBranch":
                        result.get(blockAnchor)
                              .add(new InsertIntoBlockOperation(InsertIntoBlockOperation.BlockType.FALSEBRANCH,
                                                                element.getLeft(),
                                                                (CtStatement) element.getRight()));
                        break;
                    default:
                        throw new IllegalStateException("unknown context " + context);
                }
            }
        }

        return result;
    }

    /**
     * Scan the rule method of a given class in the SmPL Java DSL and collect the line
     * numbers associated with statements in the method body.
     *
     * @param ctClass Class in SmPL Java DSL
     * @return Set of line numbers at which statements occur in the rule method
     */
    private static Set<Integer> collectStatementLines(CtClass<?> ctClass) {
        class LineCollectingScanner extends CtScanner {
            public Set<Integer> result = new HashSet<>();

            @Override
            protected void enter(CtElement e) {
                if (!SmPLJavaDSL.isDeletionAnchor(e) && e instanceof CtStatement && !(e instanceof CtBlock)) {
                    result.add(e.getPosition().getLine());
                }
            }
        }

        LineCollectingScanner lines = new LineCollectingScanner();
        lines.scan(SmPLJavaDSL.getRuleMethod(ctClass).getBody().getStatements());
        return lines.result;
    }

    /**
     * Scan the rule method of a given class in the SmPL Java DSL and find the set of
     * statement-associated line numbers that are included in a given set of 'common' line
     * numbers, but for which the parent element is a block belonging to a statement that
     * does not occur on a line belonging to the set of 'common' line numbers.
     *
     * i.e the set of context lines enclosed in non-context lines.
     *
     * @param ctClass Class in SmPL Java DSL
     * @param commonLines Set of 'common' line numbers
     * @return Set of line numbers enclosed by statements that are not associated with common lines
     */
    private static Set<Integer> findContainedCommonLines(CtClass<?> ctClass, Set<Integer> commonLines) {
        class ContainedCommonLineScanner extends CtScanner {
            public ContainedCommonLineScanner(int rootParent, Set<Integer> commonLines) {
                this.rootParent = rootParent;
                this.commonLines = commonLines;
            }

            private int rootParent;
            private Set<Integer> commonLines;
            public Set<Integer> result = new HashSet<>();

            @Override
            protected void enter(CtElement e) {
                if (e instanceof CtStatement && !(e instanceof CtBlock)) {
                    int elementPos = e.getPosition().getLine();

                    if (!commonLines.contains(elementPos)) {
                        return;
                    }

                    int parentStmtPos = e.getParent().getParent().getPosition().getLine();

                    if (parentStmtPos != rootParent && !commonLines.contains(parentStmtPos)) {
                        result.add(elementPos);
                    }
                }
            }
        }

        ContainedCommonLineScanner contained = new ContainedCommonLineScanner(SmPLJavaDSL.getRuleMethod(ctClass).getPosition().getLine(), commonLines);
        contained.scan(SmPLJavaDSL.getRuleMethod(ctClass).getBody().getStatements());
        return contained.result;
    }
}
