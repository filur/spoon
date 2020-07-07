package spoon.smpl;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import spoon.Launcher;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.visitor.CtScanner;
import spoon.smpl.formula.*;
import spoon.smpl.metavars.*;
import spoon.support.compiler.VirtualFile;
import spoon.support.reflect.code.CtInvocationImpl;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
        class DeletionAnchorRemover extends CtScanner {
            @Override
            protected void enter(CtElement e) {
                if (SmPLJavaDSL.isDeletionAnchor(e)) {
                    e.delete();
                }
            }
        }

        List<String> separated = separateAdditionsDeletions(rewrite(smpl));

        CtClass<?> dels = parseClass(separated.get(0));
        CtClass<?> adds = parseClass(separated.get(1));

        fixNoContextTypeAccesses(dels);
        fixNoContextTypeAccesses(adds);

        if (dels.getMethods().size() > 2) {
            throw new IllegalArgumentException("Referring to multiple methods in match context is not supported");
        }

        CtMethod<?> delsRuleMethod = SmPLJavaDSL.getRuleMethod(dels);
        CtMethod<?> addsRuleMethod = null;

        if (delsRuleMethod == null) {
            throw new IllegalArgumentException("Empty match context");
        }

        // TODO: why do we need to includeMetaElements here?
        Set<Integer> delsLines = collectStatementLines(delsRuleMethod, true);

        for (CtMethod<?> method : adds.getMethods()) {
            if (method.getSignature().equals(delsRuleMethod.getSignature())
                || (adds.getMethods().size() == 2 && !method.getSimpleName().equals(SmPLJavaDSL.getMetavarsMethodName()))) {
                addsRuleMethod = method;
                break;
            }

            for (CtStatement stmt : collectStatements(method, true)) {
                if (SmPLJavaDSL.isDeletionAnchor(stmt) || delsLines.contains(stmt.getPosition().getLine())) {
                    addsRuleMethod = stmt.getParent(CtMethod.class);
                    break;
                }
            }

            if (addsRuleMethod != null) {
                break;
            }
        }

        if (addsRuleMethod == null) {
            throw new IllegalStateException("Unable to determine rule method of additions AST");
        }

        Set<Integer> addsLines = collectStatementLines(addsRuleMethod, true);

        Set<Integer> commonLines = new HashSet<>(delsLines);
        commonLines.retainAll(addsLines);

        AnchoredOperationsMap anchoredOperations = anchorAdditions(addsRuleMethod, commonLines);

        Set<Integer> containedCommonLines = findContainedCommonLines(addsRuleMethod, commonLines);
        commonLines.removeAll(containedCommonLines);

        for (int line : delsLines) {
            if (!commonLines.contains(line)) {
                anchoredOperations.addKeyIfNotExists(line);
                anchoredOperations.get(line).add(new DeleteOperation());
            }
        }

        for (int line : anchoredOperations.keySet()) {
            anchoredOperations.put(line, replaceDeleteXpendOperationPair(anchoredOperations.get(line)));
        }

        String delsSignature = delsRuleMethod.getType().toString() + " " + delsRuleMethod.getSignature();
        String addsSignature = addsRuleMethod.getType().toString() + " " + addsRuleMethod.getSignature();

        if (!delsSignature.equals(addsSignature)) {
            anchoredOperations.addKeyIfNotExists(AnchoredOperationsMap.methodBodyAnchor);
            anchoredOperations.get(AnchoredOperationsMap.methodBodyAnchor).add(new MethodHeaderReplaceOperation(addsRuleMethod));
        }

        new DeletionAnchorRemover().scan(adds);
        SmPLRuleImpl result = (SmPLRuleImpl) compile(smpl, dels, commonLines, anchoredOperations);

        List<String> sigs = new ArrayList<>();

        for (CtMethod<?> method : dels.getMethods()) {
            sigs.add(method.getSignature());
        }

        for (CtMethod<?> method : adds.getMethods()) {
            if (!sigs.contains(method.getSignature())) {
                result.addAddedMethod(method);
            }
        }

        return result;
    }

    /**
     * Compile a given AST in the SmPL Java DSL, producing an SmPLRule containing a Formula.
     *
     * @param ast AST to compile
     * @return SmPLRule instance
     */
    public static SmPLRule compile(String source, CtClass<?> ast, Set<Integer> commonLines, AnchoredOperationsMap additions) {
        String ruleName = null;

        if (ast.getDeclaredField(SmPLJavaDSL.getRuleNameFieldName()) != null) {
            ruleName = ((CtLiteral<?>) ast.getDeclaredField(SmPLJavaDSL.getRuleNameFieldName())
                                          .getFieldDeclaration()
                                          .getAssignment()).getValue().toString();
        }

        Map<String, MetavariableConstraint> metavars = new HashMap<>();

        Map<String, MetavariableConstraint> metavarTypeMap = new HashMap<>();
        metavarTypeMap.put("type", new TypeConstraint());
        metavarTypeMap.put("identifier", new IdentifierConstraint());
        metavarTypeMap.put("constant", new ConstantConstraint());
        metavarTypeMap.put("expression", new ExpressionConstraint());

        if (ast.getMethodsByName(SmPLJavaDSL.getMetavarsMethodName()).size() != 0) {
            CtMethod<?> mth = ast.getMethodsByName(SmPLJavaDSL.getMetavarsMethodName()).get(0);

            String currentVarName = null;

            for (CtElement e : mth.getBody().getStatements()) {
                if (e instanceof CtInvocation) {
                    CtInvocation<?> invocation = (CtInvocation<?>) e;
                    String exeName = invocation.getExecutable().getSimpleName();

                    if (metavarTypeMap.containsKey(exeName)) {
                        CtElement arg = invocation.getArguments().get(0);

                        if (arg instanceof CtFieldRead<?>) {
                            currentVarName = ((CtFieldRead<?>) arg).getVariable().getSimpleName();
                        } else if (arg instanceof CtTypeAccess<?>) {
                            currentVarName = ((CtTypeAccess<?>) arg).getAccessedType().getSimpleName();
                        } else {
                            throw new IllegalArgumentException("Unable to extract metavariable name at <position>");
                        }

                        metavars.put(currentVarName, metavarTypeMap.get(exeName));
                    } else if (exeName.equals("constraint")) {
                        String constraintType = ((CtLiteral<?>) invocation.getArguments().get(0)).getValue().toString();
                        String constraintValue = ((CtLiteral<?>) invocation.getArguments().get(1)).getValue().toString();

                        switch (constraintType) {
                            case "regex-match":
                                metavars.put(currentVarName, new RegexConstraint(constraintValue, metavars.get(currentVarName)));
                                break;

                            default:
                                throw new IllegalArgumentException("unknown constraint type " + constraintType);
                        }


                    } else {
                        throw new IllegalArgumentException();
                    }
                } else if (e instanceof CtLocalVariable) {
                    CtLocalVariable<?> ctLocalVar = (CtLocalVariable<?>) e;
                    metavars.put(ctLocalVar.getSimpleName(), new TypedIdentifierConstraint(ctLocalVar.getType().getSimpleName()));
                } else {
                    throw new IllegalArgumentException("unhandled metavariable element " + e.toString());
                }
            }
        }

        CtMethod<?> ruleMethod = SmPLJavaDSL.getRuleMethod(ast);

        if (ruleMethod == null) {
            // TODO: does the new lexer-parser approach ever reach here? does it ever produce a class without a rule method?
            // A completely empty rule matches nothing
            return new SmPLRuleImpl(source, null, new Not(new True()), metavars);
        }

        FormulaCompiler fc = new FormulaCompiler(new SmPLMethodCFG(ruleMethod), metavars, additions);
        SmPLRule rule = new SmPLRuleImpl(source, ruleMethod, fc.compileFormula(), metavars);
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
        handleProblems(SmPLProblemDetector.detectProblems(text));

        List<SmPLLexer.Token> tokens = SmPLLexer.lex(text);
        handleProblems(SmPLProblemDetector.detectProblems(tokens));

        StringBuilder output = new StringBuilder();
        StringBuilder bodyOutput = new StringBuilder();

        String tokenText;

        int pos = 0;
        int end = tokens.size();

        boolean isAddition = false;
        boolean isDeletion = false;
        boolean isMethodHeader = false;
        boolean matchesOnMethodHeader = false;
        boolean dotsWouldBeStatement = true;

        List<String> genericMetavarTypes = Arrays.asList("identifier", "type", "constant", "expression");

        // Open class
        output.append("class RewrittenSmPLRule {\n");

        // Set value of rule name field
        if (tokens.get(pos).getType() == SmPLLexer.TokenType.Rulename) {
            // Add rule name field
            output.append("String ").append(SmPLJavaDSL.getRuleNameFieldName()).append(" = ").append("\"")
                  .append(tokens.get(pos).getText().strip()).append("\";\n");
            ++pos;
        }

        // Create metavar declarations method
        output.append("void ").append(SmPLJavaDSL.getMetavarsMethodName()).append("() {\n");

        // Add metavar declarations
        while (tokens.get(pos).getType() == SmPLLexer.TokenType.MetavarType) {
            String metavarType = tokens.get(pos).getText().strip();
            ++pos;

            while (Arrays.asList(SmPLLexer.TokenType.MetavarIdentifier, SmPLLexer.TokenType.WhenMatches).contains(tokens.get(pos).getType())) {
                switch (tokens.get(pos).getType()) {
                    case MetavarIdentifier:
                        if (genericMetavarTypes.contains(metavarType)) {
                            output.append(metavarType).append("(").append(tokens.get(pos).getText().strip()).append(");\n");
                        } else {
                            output.append(metavarType).append(" ").append(tokens.get(pos).getText().strip()).append(";\n");
                        }
                        break;

                    case WhenMatches:
                        output.append("constraint(\"regex-match\", " + tokens.get(pos + 1).getText() + ");\n");
                        ++pos;
                        break;

                    default:
                        throw new IllegalStateException("impossible");
                }

                ++pos;
            }
        }

        // Close metavar declarations method
        output.append("}\n");

        // Process rest of tokens, i.e the rule body
        while (pos < end) {
            SmPLLexer.Token token = tokens.get(pos);

            switch (token.getType()) {
                case Newline:
                    if (!isAddition) {
                        String lastLine = getLastLine(bodyOutput.toString());
                        String exprMatch = getExpressionMatch(lastLine);

                        if (exprMatch != null) {
                            bodyOutput.delete(bodyOutput.length() - lastLine.length(), bodyOutput.length());

                            if (isDeletion) {
                                bodyOutput.append("- ");
                            }

                            bodyOutput.append(SmPLJavaDSL.getExpressionMatchWrapperName()).append("(").append(exprMatch).append(");");
                        }
                    }

                    isAddition = false;
                    dotsWouldBeStatement = true;
                    isMethodHeader = false;
                    bodyOutput.append("\n");
                    break;

                case Addition:
                    isAddition = true;
                    bodyOutput.append("+ ");
                    break;

                case Deletion:
                    isDeletion = true;
                    bodyOutput.append("- ");
                    break;

                case Code:
                    tokenText = token.getText().strip();

                    if (tokenText.length() > 0) {
                        dotsWouldBeStatement = false;
                    }

                    if (!isAddition && methodHeader.test(tokenText)) {
                        isMethodHeader = true;
                        matchesOnMethodHeader = true;
                    }

                    bodyOutput.append(tokenText);
                    break;

                case Dots:
                    if (dotsWouldBeStatement) {
                        bodyOutput.append(SmPLJavaDSL.getDotsStatementElementName()).append("(");
                        pos = parseDotsConstraints(tokens, pos, bodyOutput);
                        bodyOutput.append(");\n");
                    } else if (isMethodHeader) {
                        bodyOutput.append(SmPLJavaDSL.createDotsParameterString());
                    } else {
                        bodyOutput.append(SmPLJavaDSL.getDotsParameterOrArgumentElementName());
                    }
                    break;

                case OptDotsBegin:
                    bodyOutput.append("if (");
                    bodyOutput.append(SmPLJavaDSL.getDotsWithOptionalMatchName()).append("(");
                    pos = parseDotsConstraints(tokens, pos, bodyOutput);
                    bodyOutput.append(")");
                    bodyOutput.append(") {\n");
                    break;

                case OptDotsEnd:
                    bodyOutput.append("}\n");
                    break;

                case DisjunctionBegin:
                    bodyOutput.append("if (").append(SmPLJavaDSL.getBeginDisjunctionName()).append(") {\n");
                    break;

                case DisjunctionContinue:
                    bodyOutput.append("} else if (").append(SmPLJavaDSL.getContinueDisjunctionName()).append(") {\n");
                    break;

                case DisjunctionEnd:
                    bodyOutput.append("}\n");
                    break;

                default:
                    throw new IllegalStateException("unhandled token " + token.getType().toString());
            }

            ++pos;
        }

        if (!matchesOnMethodHeader) {
            // Wrap rule body code in "unspecified" method header and implicit dots
            output.append(SmPLJavaDSL.createUnspecifiedMethodHeaderString()).append(" {\n");
            output.append("if (").append(SmPLJavaDSL.createImplicitDotsCall()).append(") {\n");
            output.append(bodyOutput);
            output.append("}\n");
            output.append("}\n");
        } else {
            output.append(bodyOutput);
        }

        // Close class
        output.append("}\n");

        return removeEmptyLines(output.toString()) + "\n";
    }

    /**
     * Check for problems reported by SmPLProblemDetector and abort if there are unrecoverable errors.
     *
     * @param problems
     */
    private static void handleProblems(List<SmPLProblemDetector.Problem> problems) {
        if (problems == null) {
            return;
        }

        boolean canRecover = true;

        for (SmPLProblemDetector.Problem problem : problems) {
            System.err.println(problem.toString());

            if (problem.type == SmPLProblemDetector.ProblemType.Error) {
                canRecover = false;
            }
        }

        if (!canRecover) {
            throw new IllegalArgumentException("Unrecoverable SmPL parse error");
        }
    }

    /**
     * Remove empty lines from a String.
     *
     * @param s String to process
     * @return String with empty lines removed
     */
    private static String removeEmptyLines(String s) {
        return String.join("\n", Arrays.stream(s.split("\n")).filter(ss -> !ss.isEmpty()).collect(Collectors.toList()));
    }

    /**
     * Parse tokens following a dots operator, collecting all constraints and producing the relevant DSL code.
     *
     * @param tokens Token stream
     * @param pos Position of dots operator in token stream
     * @param output Output receiver
     * @return Position of first unconsumed token in token stream
     */
    private static int parseDotsConstraints(List<SmPLLexer.Token> tokens, int pos, StringBuilder output) {
        int end = tokens.size();
        int finalPos = pos;
        String comma = "";

        pos += 1;

        while (pos < end) {
            SmPLLexer.Token token = tokens.get(pos);
            SmPLLexer.TokenType tpe = token.getType();

            if (tpe == SmPLLexer.TokenType.Newline) {
                pos += 1;
            } else if (tpe == SmPLLexer.TokenType.WhenAny || tpe == SmPLLexer.TokenType.WhenExists || tpe == SmPLLexer.TokenType.WhenNotEqual) {

                if (tpe == SmPLLexer.TokenType.WhenAny) {
                    output.append(comma).append(SmPLJavaDSL.getDotsWhenAnyName()).append("()");
                } else if (tpe == SmPLLexer.TokenType.WhenExists) {
                    output.append(comma).append(SmPLJavaDSL.getDotsWhenExistsName()).append("()");
                } else {
                    String exprMatch = getExpressionMatch(tokens.get(pos).getText().strip());

                    if (exprMatch != null) {
                        output.append(comma).append(SmPLJavaDSL.getDotsWhenNotEqualName()).append("(")
                              .append(SmPLJavaDSL.getExpressionMatchWrapperName()).append("(")
                              .append(tokens.get(pos).getText().strip()).append(")")
                              .append(")");
                    } else {
                        output.append(comma).append(SmPLJavaDSL.getDotsWhenNotEqualName()).append("(").append(tokens.get(pos).getText().strip()).append(")");
                    }
                }

                finalPos = ++pos;
                comma = ",";
            } else {
                break;
            }
        }

        return finalPos;
    }

    /**
     * Get the last line of a given String potentially containing newlines.
     *
     * @param str String input
     * @return String of characters following the rightmost newline character in input, or full input if input contains no newline characters
     */
    private static String getLastLine(String str) {
        return str.contains("\n") ? str.substring(str.lastIndexOf('\n') + 1) : str;
    }

    /**
     * Determine if a line of patch code refers to an expression rather than a full statement, and if so produce the
     * exact code of the expression.
     *
     * @param str Single line of patch code
     * @return String of expression source code, or null if input is determined to be a non-expression.
     */
    private static String getExpressionMatch(String str) {
        if (str.startsWith("-")) {
            str = str.substring(1);
        }

        str = str.strip();

        if (str.equals("") || str.equals("{") || str.equals("}") || str.startsWith("if") || str.endsWith("{") || str.endsWith(";")) {
            return null;
        }

        return str;
    }

    /**
     * Separate an SmPL patch given in plain text into two versions where one removes all
     * added lines retaining only deletions and context lines, and the other replaces all
     * deleted lines with a dummy placeholder for anchoring.
     *
     * @param input SmPL patch in plain text to separate
     * @return List of two Strings containing the two separated versions
     */
    private static List<String> separateAdditionsDeletions(String input) {
        StringBuilder dels = new StringBuilder();
        StringBuilder adds = new StringBuilder();

        for (String str : input.split("\n")) {
            if (str.length() > 0) {
                if (str.charAt(0) == '-') {
                    dels.append(' ').append(str.substring(1)).append("\n");
                    if (str.contains(SmPLJavaDSL.getDotsStatementElementName() + "();") || methodHeader.test(str.substring(1).strip())) {
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
     * @param method SmPL rule method in the SmPL Java DSL
     * @param commonLines Set of context lines common to both the deletions and the additions ASTs
     * @return Map of anchors to lists of operations
     */
    private static AnchoredOperationsMap anchorAdditions(CtMethod<?> method, Set<Integer> commonLines) {
        return anchorAdditions(method.getBody(), commonLines, AnchoredOperationsMap.methodBodyAnchor, "methodBody");
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
    private static AnchoredOperationsMap anchorAdditions(CtElement e, Set<Integer> commonLines, int blockAnchor, String context) {
        AnchoredOperationsMap result = new AnchoredOperationsMap();

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
                    // This is a deletion or a context statement

                    if (SmPLJavaDSL.isStatementLevelDots(stmt)) {
                        // TODO: this is pretty awful, find a cleaner way
                        // Arriving at dots carrying an unanchored statement that was itself preceded by dots
                        //   yields an impossible situation
                        if (unanchored.size() > 0) {
                            for (Pair<InsertIntoBlockOperation.Anchor, CtElement> pair : unanchored) {
                                if (pair.getLeft().equals(InsertIntoBlockOperation.Anchor.BOTTOM)) {
                                    throw new IllegalArgumentException("unanchorable statement");
                                }
                            }
                        }

                        unanchoredCommitted.addAll(unanchored);
                        isAfterDots = true;
                        elementAnchor = 0;
                    } else {
                        isAfterDots = false;
                        // TODO: if we used line+offset maybe we could support multiple anchorable statements per line in a patch
                        elementAnchor = stmtLine;

                        // The InsertIntoBlockOperation.Anchor is irrelevant here
                        for (Pair<InsertIntoBlockOperation.Anchor, CtElement> element : unanchored) {
                            result.addKeyIfNotExists(elementAnchor);
                            result.get(elementAnchor).add(new PrependOperation(element.getRight()));
                        }
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
                    // This is an addition

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
                    case "methodBody":
                        result.get(blockAnchor)
                              .add(new InsertIntoBlockOperation(InsertIntoBlockOperation.BlockType.METHODBODY,
                                                                element.getLeft(),
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
     * Scan a given method in the SmPL Java DSL and collect the line numbers associated with statements in its body.
     *
     * @param method Rule method in SmPL Java DSL
     * @return Set of line numbers at which statements occur in the rule method
     */
    private static Set<Integer> collectStatementLines(CtMethod<?> method, boolean includeMetaElements) {
        Set<Integer> result = new HashSet<>();

        for (CtStatement stmt : collectStatements(method, includeMetaElements)) {
            result.add(stmt.getPosition().getLine());
        }

        return result;
    }

    /**
     * Scan a given method and collect all statement elements found in its body.
     *
     * @param method Method to scan
     * @return List of statements found in method body
     */
    private static List<CtStatement> collectStatements(CtMethod<?> method, boolean includeMetaElements) {
        class StatementCollectingScanner extends CtScanner {
            public List<CtStatement> result = new ArrayList<>();

            @Override
            protected void enter(CtElement e) {
                if (!includeMetaElements && !SmPLJavaDSL.isExpressionMatchWrapper(e) && SmPLJavaDSL.isMetaElement(e)) {
                    return;
                }

                if (!SmPLJavaDSL.isDeletionAnchor(e) && e instanceof CtStatement && !(e instanceof CtBlock)) {
                    result.add((CtStatement) e);
                }
            }
        }

        StatementCollectingScanner statements = new StatementCollectingScanner();
        statements.scan(method.getBody().getStatements());
        return statements.result;
    }

    /**
     * Scan the given rule method in the SmPL Java DSL and find the set of statement-associated line numbers that
     * are included in a given set of 'common' line numbers, but for which the parent element is a block belonging
     * to a statement that does not occur on a line belonging to the set of 'common' line numbers.
     *
     * i.e the set of context lines enclosed in non-context lines.
     *
     * @param method Method in SmPL Java DSL
     * @param commonLines Set of 'common' line numbers
     * @return Set of line numbers enclosed by statements that are not associated with common lines
     */
    private static Set<Integer> findContainedCommonLines(CtMethod<?> method, Set<Integer> commonLines) {
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

        ContainedCommonLineScanner contained = new ContainedCommonLineScanner(method.getPosition().getLine(), commonLines);
        contained.scan(method.getBody().getStatements());
        return contained.result;
    }

    /**
     * Parse a single Java class using Spoon with auto-imports disabled.
     *
     * @param code Source code of single Java class
     * @return Spoon metamodel representation of input class
     */
    private static CtClass<?> parseClass(String code) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setAutoImports(false);
        launcher.addInputResource(new VirtualFile(code));
        launcher.buildModel();
        return (CtClass<?>) launcher.getModel().getAllTypes().iterator().next();
    }

    /**
     * Replace (by mutating input AST) certain CtTypeAccesses with CtFieldReads.
     *
     * When there is absolutely no context for the identifier "foo", Launcher.buildModel with auto-imports disabled
     * parses expressions of the form "foo.x" as a CtTypeAccess of the type "x" in the package "foo". In the SmPL
     * context we would rather have such expressions seen as a CtFieldRead of "x" on some arbitrary variable-like "foo".
     *
     * The replacement has the following structure:
     *  CtTypeAccess("x", pkg=CtPackageReference("foo")) --> CtFieldRead("x", target=CtFieldRead("foo", target=null))
     *
     * @param element AST to process
     */
    private static void fixNoContextTypeAccesses(CtElement element) {
        CtScanner scanner = new CtScanner() {
            List<Class<? extends CtElement>> validParentTypes = Arrays.asList(CtInvocationImpl.class);

            @Override
            protected void enter(CtElement e) {
                if (!(e instanceof CtTypeAccess)) {
                    return;
                }

                CtTypeAccess<?> typeAccess = (CtTypeAccess<?>) e;

                if (!typeAccess.toString().contains(".") || !validParentTypes.contains(typeAccess.getParent().getClass())) {
                    return;
                }

                Factory factory = e.getFactory();

                CtFieldReference<Object> outerField = factory.createFieldReference();
                outerField.setSimpleName(typeAccess.getAccessedType().getPackage().getSimpleName());

                CtFieldReference<Object> innerField = factory.createFieldReference();
                innerField.setSimpleName(typeAccess.getAccessedType().getSimpleName());

                CtFieldRead<Object> outerFieldRead = factory.createFieldRead();
                outerFieldRead.setTarget(null);
                outerFieldRead.setVariable(outerField);

                CtFieldRead<Object> innerFieldRead = factory.createFieldRead();
                innerFieldRead.setTarget(outerFieldRead);
                innerFieldRead.setVariable(innerField);

                typeAccess.replace(innerFieldRead);
            }
        };

        scanner.scan(element);
    }

    /**
     * Replace Delete-Append or Delete-Prepend Operation pairs with ReplaceOperations.
     *
     * @param ops List of Operations to process
     * @return Singleton list containing a ReplaceOperation if input was an appropriate pair, unmodified input list otherwise.
     */
    private static List<Operation> replaceDeleteXpendOperationPair(List<Operation> ops) {
        if (ops.size() != 2) {
            return ops;
        }

        Operation op1 = ops.get(0);
        Operation op2 = ops.get(1);

        if (op1 instanceof DeleteOperation && op2 instanceof PrependOperation) {
            return Collections.singletonList(new ReplaceOperation(((PrependOperation) op2).elementToPrepend));
        } else if (op1 instanceof DeleteOperation && op2 instanceof AppendOperation) {
            return Collections.singletonList(new ReplaceOperation(((AppendOperation) op2).elementToAppend));
        } else if (op2 instanceof DeleteOperation && op1 instanceof PrependOperation) {
            return Collections.singletonList(new ReplaceOperation(((PrependOperation) op1).elementToPrepend));
        } else if (op2 instanceof DeleteOperation && op1 instanceof AppendOperation) {
            return Collections.singletonList(new ReplaceOperation(((AppendOperation) op1).elementToAppend));
        } else {
            return ops;
        }
    }

    /**
     * Regex match predicate for identifying a method header.
     */
    private static java.util.function.Predicate<String> methodHeader
        = Pattern.compile("(?s)^(public\\s+|private\\s+|protected\\s+|static\\s+)*" +
                          "[A-Za-z_][A-Za-z0-9_-]*\\s+[A-Za-z_][A-Za-z0-9_-]*\\s*\\(.*").asMatchPredicate();
}
