package spoon.smpl;

import fr.inria.controlflow.ControlFlowBuilder;
import fr.inria.controlflow.ControlFlowNode;
import spoon.Launcher;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.smpl.formula.MetavariableConstraint;
import spoon.smpl.pattern.PatternBuilder;
import spoon.smpl.pattern.PatternNode;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.fail;

public class TestUtils {
    public static void resetControlFlowNodeCounter() {
        try {
            Field field = ControlFlowNode.class.getDeclaredField("count");
            field.setAccessible(true);
            ControlFlowNode.count = 0;
        } catch (Exception e) {
            fail("Unable to reset ControlFlowNode id counter");
        }
    }

    public static Map<String, MetavariableConstraint> makeMetavars(Object ... xs) {
        Map<String, MetavariableConstraint> result = new HashMap<>();

        for (int i = 0; i < xs.length; i += 2) {
            result.put((String) xs[i], (MetavariableConstraint) xs[i + 1]);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public static ModelChecker.ResultSet res(Object ... xs) {
        ModelChecker.ResultSet resultSet = new ModelChecker.ResultSet();

        int i = 0;

        while (true) {
            if (i+1 >= xs.length) {
                break;
            }

            if (i+2 < xs.length && xs[i+2] instanceof Set<?>) {
                resultSet.add(new ModelChecker.Result((Integer) xs[i], (Environment) xs[i+1], (Set<ModelChecker.Witness>) xs[i+2]));
                i += 3;
            } else {
                resultSet.add(new ModelChecker.Result((Integer) xs[i], (Environment) xs[i+1], new HashSet<>()));
                i += 2;
            }
        }

        return resultSet;
    }

    public static Set<Environment> envSet(Environment ... envs) {
        return new HashSet<>(Arrays.asList(envs));
    }

    public static Environment env(Object ... xs) {
        Environment result = new Environment();

        for (int i = 0; i < xs.length; i += 2) {
            result.put((String) xs[i], xs[i+1]);
        }

        return result;
    }

    public static Environment.NegativeBinding envNeg(Object ... xs) {
        return new Environment.NegativeBinding(xs);
    }

    public static String sortedEnvs(String s) {
        String result = s;
        Pattern p = Pattern.compile("\\{([^}]*)\\}");
        Matcher matcher = p.matcher(s);

        while (matcher.find()) {
            String found = matcher.group();
            found = found.substring(1, found.length() - 1);
            List<String> stuff = Arrays.asList(found.split(",\\s*"));

            Collections.sort(stuff);

            StringBuilder sb = new StringBuilder();
            sb.append("{");

            for (int i = 0; i < stuff.size(); ++i) {
                sb.append(stuff.get(i));
                sb.append(", ");
            }

            sb.delete(sb.length() - 2, sb.length());
            sb.append("}");

            result = result.replace(matcher.group(), sb.toString());
        }

        return result;
    }

    public static Set<Integer> intSet(Integer ... xs) {
        return new HashSet<Integer>(Arrays.asList(xs));
    }

    public static PatternNode makePattern(CtElement element, List<String> params) {
        PatternBuilder builder = new PatternBuilder(params);
        element.accept(builder);
        return builder.getResult();
    }

    public static PatternNode makePattern(CtElement element) {
        return makePattern(element, new ArrayList<String>());
    }

    public static CtMethod<?> parseMethod(String methodCode) {
        CtClass<?> myclass = Launcher.parseClass("class A { " + methodCode + " }");
        return (CtMethod<?>) myclass.getMethods().toArray()[0];
    }

    public static CtElement parseStatement(String code) {
        CtClass<?> myclass = Launcher.parseClass("class A { void m() { " + code + " } }");
        return ((CtMethod<?>)myclass.getMethods().toArray()[0]).getBody().getLastStatement();
    }

    public static CtElement parseExpression(String code) {
        CtClass<?> myclass = Launcher.parseClass("class A { Object m() { return " + code + " } }");
        CtReturn<?> ctReturn = ((CtMethod<?>)myclass.getMethods().toArray()[0]).getBody().getLastStatement();
        return ctReturn.getReturnedExpression();
    }

    public static CtElement parseReturnStatement(String code) {
        CtClass<?> myclass = Launcher.parseClass("class A { Object m() { " + code + " } }");
        return ((CtMethod<?>)myclass.getMethods().toArray()[0]).getBody().getLastStatement();
    }

    public static SmPLCFGAdapter methodCfg(CtMethod<?> method) {
        ControlFlowBuilder cfgBuilder = new ControlFlowBuilder();
        return new SmPLCFGAdapter(cfgBuilder.build(method));
    }

    public static ModelChecker.Witness witness(int state, String metavar, Object binding) {
        return new ModelChecker.Witness(state, metavar, binding, new HashSet<>());
    }

    public static ModelChecker.Witness witness(int state, String metavar, Object binding, ModelChecker.Witness ... witnesses) {
        return new ModelChecker.Witness(state, metavar, binding, new HashSet<ModelChecker.Witness>(Arrays.asList(witnesses)));
    }
}
