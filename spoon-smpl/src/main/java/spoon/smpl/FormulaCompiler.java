package spoon.smpl;

import fr.inria.controlflow.BranchKind;
import fr.inria.controlflow.ControlFlowNode;
import org.apache.commons.lang3.NotImplementedException;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.smpl.formula.*;
import spoon.smpl.pattern.PatternBuilder;
import spoon.smpl.pattern.PatternNode;

import java.util.*;

/**
 * FormulaCompiler compiles CTL-VW Formulas from a given SmPL-adapted CFG of a method body in
 * the SmPL Java DSL (domain-specific language) of spoon-smpl, as generated by SmPLParser.
 */
public class FormulaCompiler {
    public FormulaCompiler(SmPLMethodCFG cfg) { this(cfg, new HashMap<>()); }
    /**
     * Create a new FormulaCompiler.
     * @param cfg SmPL-adapted CFG to produce formula from
     * @param metavars Metavariable names and their constraints
     */
    public FormulaCompiler(SmPLMethodCFG cfg, Map<String, MetavariableConstraint> metavars) {
        this.cfg = cfg;
        this.quantifiedMetavars = new ArrayList<>();
        this.metavars = metavars;
        this.patternBuilder = new PatternBuilder(new ArrayList<>(metavars.keySet()));
        this.queuedOperations = new ArrayList<>();
        this.operationsAnchor = null;
    }

    /**
     * Optimize a given formula.
     *
     * Optimizations:
     * 1) Operation-capturing formulas "And(LHS, ExistsVar("_v", SetEnv("_v", List<Operation>)))"
     *    with empty operation lists are replaced by LHS.
     *
     * @param input Formula to optimize
     * @return Optimized formula
     */
    public static Formula optimize(Formula input) {
        if (input == null) {
            return null;
        } else if (input instanceof AllNext) {
            return new AllNext(optimize(((AllNext) input).getInnerElement()));
        } else if (input instanceof AllUntil) {
            return new AllUntil(optimize(((AllUntil) input).getLhs()), optimize(((AllUntil) input).getRhs()));
        } else if (input instanceof And) {
            And and = (And) input;

            if (and.getRhs() instanceof ExistsVar
                && ((ExistsVar) and.getRhs()).getVarName().equals("_v")
                && ((ExistsVar) and.getRhs()).getInnerElement() instanceof SetEnv
                && ((SetEnv) ((ExistsVar) and.getRhs()).getInnerElement()).getValue() instanceof List
                && ((List<?>) ((SetEnv) ((ExistsVar) and.getRhs()).getInnerElement()).getValue()).size() == 0) {
                return and.getLhs();
            }

            return new And(optimize(and.getLhs()), optimize(and.getRhs()));
        } else if (input instanceof BranchPattern) {
            return input;
        } else if (input instanceof ExistsNext) {
            return new ExistsNext(optimize(((ExistsNext) input).getInnerElement()));
        } else if (input instanceof ExistsUntil) {
            return new ExistsUntil(optimize(((ExistsUntil) input).getLhs()), optimize(((ExistsUntil) input).getRhs()));
        } else if (input instanceof ExistsVar) {
            return new ExistsVar(((ExistsVar) input).getVarName(), optimize(((ExistsVar) input).getInnerElement()));
        } else if (input instanceof Not) {
            return new Not(optimize(((Not) input).getInnerElement()));
        } else if (input instanceof Or) {
            return new Or(optimize(((Or) input).getLhs()), optimize(((Or) input).getRhs()));
        } else if (input instanceof Proposition) {
            return input;
        } else if (input instanceof SetEnv) {
            return input;
        } else if (input instanceof StatementPattern) {
            return input;
        } else if (input instanceof True) {
            return input;
        } else {
            throw new IllegalArgumentException("unhandled formula element " + input.getClass().toString());
        }
    }

    /**
     * Compile the CTL-VW Formula.
     * @return CTL-VW Formula
     */
    public Formula compileFormula() {
        quantifiedMetavars = new ArrayList<>();
        queuedOperations = new ArrayList<>();
        operationsAnchor = null;

        Formula result = compileFormulaInner(cfg.findNodesOfKind(BranchKind.BEGIN).get(0).next().get(0));
        String prevStr = "";

        while (!prevStr.equals(result.toString())) {
            prevStr = result.toString();
            result = optimize(result);
        }

        return result;
    }

    /**
     * Compile a CTL-VW Formula.
     * @param node First node of control flow graph to generate formula for
     * @return CTL-VW Formula
     */
    private Formula compileFormulaInner(ControlFlowNode node) {
        Formula formula;

        if (node.getKind() == BranchKind.EXIT) {
            return null;
        }

        switch (node.next().size()) {
            case 0:
                throw new IllegalArgumentException("Control flow node with no outgoing path");

            case 1:
                switch (node.getKind()) {
                    case STATEMENT:
                        return compileStatementFormula(node);

                    case BLOCK_BEGIN:
                        if (!(node.getTag() instanceof SmPLMethodCFG.NodeTag)) {
                            throw new IllegalArgumentException("invalid BLOCK_BEGIN tag for node " +
                                                                Integer.toString(node.getId()));
                        }

                        formula = new And(new Proposition(((SmPLMethodCFG.NodeTag) node.getTag()).getLabel()),
                                                  new ExistsVar("_v", new SetEnv("_v", new ArrayList<>())));

                        operationsAnchor = formula;

                        formula = new And(new And(formula,
                                                  new AllNext(compileFormulaInner(node.next().get(0)))),
                                          new ExistsVar("_v", new SetEnv("_v", new ArrayList<>())));

                        operationsAnchor = formula;
                        return formula;

                    case CONVERGE:
                        formula = new And(new Proposition("after"),
                                          new ExistsVar("_v", new SetEnv("_v", new ArrayList<>())));

                        operationsAnchor = formula;

                        Formula innerFormula = compileFormulaInner(node.next().get(0));

                        if (innerFormula == null) {
                            return formula;
                        } else {
                            formula = new And(new And(formula, new AllNext(innerFormula)),
                                              new ExistsVar("_v", new SetEnv("_v", new ArrayList<>())));

                            operationsAnchor = formula;
                            return formula;
                        }

                    default:
                        throw new IllegalArgumentException("Unexpected control flow node kind for single successor: " + node.getKind().toString());
                }

            default:
                switch (node.getKind()) {
                    case STATEMENT:
                        // Will probably need this if adding support for exceptions
                        throw new NotImplementedException("Not implemented");

                    case BRANCH:
                        node.getStatement().accept(patternBuilder);
                        PatternNode cond = patternBuilder.getResult();
                        Class<? extends CtElement> branchType = node.getStatement().getParent().getClass();

                        formula = new BranchPattern(cond, branchType, metavars);
                        ((BranchPattern) formula).setStringRepresentation(node.getStatement().toString());

                        if (queuedOperations.size() > 0) {
                            formula = new And(formula, new ExistsVar("_v", new SetEnv("_v", queuedOperations)));
                            queuedOperations = new ArrayList<>();
                        }

                        // Mark first occurences of metavars as quantified before compiling inner formulas
                        List<String> newMetavars = getUnquantifiedMetavarsUsedIn(node.getStatement());
                        quantifiedMetavars.addAll(newMetavars);

                        Formula lhs = compileFormulaInner(node.next().get(0));
                        Formula rhs = compileFormulaInner(node.next().get(1));

                        formula = new And(formula, new AllNext(new Or(lhs, rhs)));

                        // Actually quantify the new metavars
                        Collections.reverse(newMetavars);

                        for (String varname : newMetavars) {
                            formula = new ExistsVar(varname, formula);
                        }

                        return formula;

                    default:
                        throw new IllegalArgumentException("Unexpected control flow node kind for multiple successors: " + node.getKind().toString());
                }
        }
    }

    /**
     * Compile a CTL-VW Formula for a given single-statement single-successor CFG node.
     * @param node A single-statement, single-successor CFG node
     * @return CTL-VW Formula
     */
    private Formula compileStatementFormula(ControlFlowNode node) {
        if (isDots(node.getStatement())) {
            // TODO: add guards needed for ensuring shortest path
            Formula innerFormula = compileFormulaInner(node.next().get(0));

            if (innerFormula == null) {
                return new True();
            } else {
                return new AllUntil(new True(), innerFormula);
            }
        } else if (isDeleteOperation(node.getStatement())) {
            queuedOperations.add(new DeleteOperationImpl());
            return compileFormulaInner(node.next().get(0));
        } else if (isAddOperation(node.getStatement())) {
            if (operationsAnchor == null) {
                queuedOperations.add(new PrependOperationImpl(node.next().get(0).getStatement()));
            } else {
                appendOperation(operationsAnchor, new AppendOperationImpl(node.next().get(0).getStatement()));
            }

            return compileFormulaInner(node.next().get(0).next().get(0));
        } else {
            node.getStatement().accept(patternBuilder);
            Formula formula = new StatementPattern(patternBuilder.getResult(), metavars);

            ((StatementPattern) formula).setStringRepresentation(node.getStatement().toString());

            if (queuedOperations.size() > 0) {
                formula = new And(formula, new ExistsVar("_v", new SetEnv("_v", queuedOperations)));
                queuedOperations = new ArrayList<>();
            } else {
                formula = new And(formula, new ExistsVar("_v", new SetEnv("_v", new ArrayList<>())));
            }

            operationsAnchor = formula;

            // Mark first occurences of metavars as quantified before compiling inner formula
            List<String> newMetavars = getUnquantifiedMetavarsUsedIn(node.getStatement());
            quantifiedMetavars.addAll(newMetavars);

            Formula innerFormula = compileFormulaInner(node.next().get(0));

            if (innerFormula != null) {
                formula = new And(formula, new AllNext(innerFormula));
            }

            // Actually quantify the new metavars
            Collections.reverse(newMetavars);

            for (String varname : newMetavars) {
                formula = new ExistsVar(varname, formula);
            }

            return formula;
        }
    }

    /**
     * Check if an element represents an SmPL dots construct in the SmPL Java DSL.
     * @param e Element to check
     * @return True if element represents an SmPL dots construct, false otherwise
     */
    private static boolean isDots(CtElement e) {
        if (e instanceof CtInvocation<?>) {
            return ((CtInvocation<?>) e).getExecutable().getSimpleName().equals("__SmPLDots__");
        } else {
            return false;
        }
    }

    /**
     * Check if an element represents an SmPL delete operation in the SmPL Java DSL.
     * @param e Element to check
     * @return True if element represents an SmPL delete operation, false otherwise
     */
    private static boolean isDeleteOperation(CtElement e) {
        if (e instanceof CtInvocation<?>) {
            String simpleName = ((CtInvocation<?>) e).getExecutable().getSimpleName();
            return (simpleName.equals("__SmPLDelete__"));
        } else {
            return false;
        }
    }

    /**
     * Check if an element represents an SmPL addition operation in the SmPL Java DSL.
     * @param e Element to check
     * @return True if element represents an SmPL addition operation, false otherwise
     */
    private static boolean isAddOperation(CtElement e) {
        if (e instanceof CtInvocation<?>) {
            String simpleName = ((CtInvocation<?>) e).getExecutable().getSimpleName();
            return (simpleName.equals("__SmPLAdd__"));
        } else {
            return false;
        }
    }

    /**
     * Get sorted list of metavariable names referenced in a given AST element.
     * @param e Element to scan
     * @return Sorted list of metavariable names
     */
    private List<String> getMetavarsUsedIn(CtElement e) {
        List<String> result = new ArrayList<>();

        e.filterChildren(new TypeFilter<>(CtLocalVariable.class)).forEach((element) -> {
            String varname = ((CtLocalVariable<?>) element).getReference().getSimpleName();
            String typename = ((CtLocalVariable<?>) element).getType().getSimpleName();

            if (metavars.containsKey(varname) && !result.contains(varname)) {
                result.add(varname);
            }

            if (metavars.containsKey(typename) && !result.contains(typename)) {
                result.add(typename);
            }
        });

        e.filterChildren(new TypeFilter<>(CtVariableReference.class)).forEach((element) -> {
            String varname = element.toString();

            if (metavars.containsKey(varname) && !result.contains(varname)) {
                result.add(varname);
            }
        });

        Collections.sort(result);

        return result;
    }

    /**
     * Get sorted list of not-yet-quantified metavariable names referenced in a given AST element.
     * @param e Element to scan
     * @return Sorted list of not-yet-quantified metavariable names
     */
    private List<String> getUnquantifiedMetavarsUsedIn(CtElement e) {
        List<String> result = getMetavarsUsedIn(e);
        result.removeAll(quantifiedMetavars);
        return result;
    }

    /**
     * Append an operation to a given Formula.
     * @param target Target formula to append operation to
     * @param op Operation to append
     */
    @SuppressWarnings("unchecked")
    private static void appendOperation(Formula target, Operation op) {
        boolean validTarget = target instanceof And
                           && ((And) target).getRhs() instanceof ExistsVar
                           && ((ExistsVar) ((And) target).getRhs()).getVarName().equals("_v")
                           && ((ExistsVar) ((And) target).getRhs()).getInnerElement() instanceof SetEnv;

        if (validTarget) {
            SetEnv formula = (SetEnv) ((ExistsVar) ((And) target).getRhs()).getInnerElement();
            ((List<Operation>) formula.getValue()).add(op);
        } else {
            throw new IllegalArgumentException("Cannot append operation to " + target.toString());
        }
    }

    /**
     * SmPL-adapted CFG to use for formula generation.
     */
    private SmPLMethodCFG cfg;

    /**
     * List of metavariable names that have already been quantified.
     */
    private List<String> quantifiedMetavars;

    /**
     * Metavariable names and their corresponding constraints.
     */
    private Map<String, MetavariableConstraint> metavars;

    /**
     * A PatternBuilder to build patterns.
     */
    private PatternBuilder patternBuilder;

    /**
     * Operations queued to be assigned to the next available operation anchor.
     */
    private List<Operation> queuedOperations;

    /**
     * Current operation anchor.
     */
    private Formula operationsAnchor;
}
