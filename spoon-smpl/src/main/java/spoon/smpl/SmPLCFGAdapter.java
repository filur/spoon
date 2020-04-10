package spoon.smpl;

import fr.inria.controlflow.*;
import spoon.reflect.code.CtIf;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;

import java.util.List;
import java.util.Set;

/**
 * SmPLCFGAdapter imposes constraints on and adaptations to a ControlFlowGraph for use
 * in SmPL-related contexts. The constraints are that the ControlFlowGraph must not be
 * simplified (see ControlFlowGraph::simplify).
 *
 * The adaptations are:
 * 1) The outmost BLOCK_BEGIN node (successor to the BEGIN node) is removed.
 * 2) All remaining BLOCK_BEGIN nodes are tagged with String "trueBranch" or "falseBranch".
 * 3) All BLOCK_END nodes are removed.
 */
public class SmPLCFGAdapter {
    /**
     * A NodeTag is a combination of a String label and an AST element anchor.
     *
     * The idea is that certain nodes (BRANCH, BLOCK_BEGIN and CONVERGE) in an SmPL-adapted CFG should
     * be tagged with a NodeTag using labels "branch", ("trueBranch" or "falseBranch") and "after",
     * respectively, along with the appropriate AST element for anchoring prepend and/or append operations
     * to.
     */
    public static class NodeTag {
        /**
         * Create a new NodeTag.
         * @param label Label
         * @param anchor Anchor element
         */
        public NodeTag(String label, CtElement anchor) {
            this.label = label;
            this.anchor = anchor;
        }

        /**
         * Get the label string.
         * @return Label string
         */
        public String getLabel() {
            return label;
        }

        /**
         * Get the anchor element.
         * @return Anchor element
         */
        public CtElement getAnchor() {
            return anchor;
        }

        @Override
        public String toString() {
            return "NodeTag(" + label + ", " + anchor.toString() + ")";
        }

        /**
         * Label string.
         */
        private final String label;

        /**
         * Anchor element.
         */
        private final CtElement anchor;
    }

    /**
     * Create a new SmPL-adapted CFG from a given method element.
     * @param method Method for which to generate an SmPL-adapted CFG
     */
    public SmPLCFGAdapter(CtMethod<?> method) {
        this(new ControlFlowBuilder().build(method.getBody()));
    }

    /**
     * Create a new SmPL-adapted CFG from a given unsimplified generic CFG.
     * @param cfg CFG to adapt
     */
    public SmPLCFGAdapter(ControlFlowGraph cfg) {
        this.cfg = cfg;

        if (cfg.findNodesOfKind(BranchKind.BLOCK_BEGIN).size() == 0) {
            throw new IllegalArgumentException("The CFG must NOT be simplified (see ControlFlowGraph::simplify)");
        }

        removeOutermostBlockBeginNode(cfg);
        removeBlockEndNodes(cfg);

        // Annotate branches
        for (ControlFlowNode node : cfg.vertexSet()) {
            if (node.getKind() != BranchKind.BRANCH) {
                // We're only looking for BRANCH nodes
                continue;
            }

            if (node.next().size() != 2) {
                throw new IllegalStateException("branch node with invalid number of successors");
            }

            // If is the only supported branch statement at this time
            CtIf ifStm = (CtIf) node.getStatement().getParent();

            node.setTag(new NodeTag("branch", ifStm));

            ControlFlowNode afterNode = findConvergenceNode(node);
            afterNode.setTag(new NodeTag("after", ifStm));

            ControlFlowNode n1 = node.next().get(0);
            ControlFlowNode n2 = node.next().get(1);

            // If only one successor is a BLOCK_BEGIN the branch is else-less.
            if (n1.getKind() == BranchKind.BLOCK_BEGIN && n2.getKind() == BranchKind.CONVERGE) {
                n1.setTag(new NodeTag("trueBranch", ifStm.getThenStatement()));
            } else if (n2.getKind() == BranchKind.BLOCK_BEGIN && n1.getKind() == BranchKind.CONVERGE) {
                n2.setTag(new NodeTag("trueBranch", ifStm.getThenStatement()));
            } else {
                // Both successors are blocks

                ControlFlowNode n1next = n1.next().get(0);
                ControlFlowNode n2next = n2.next().get(0);

                if (n1next.getKind() == BranchKind.CONVERGE && n2next.getKind() == BranchKind.CONVERGE) {
                    // Both blocks are empty, we can choose labels arbitrarily
                    n1.setTag(new NodeTag("trueBranch", ifStm.getThenStatement()));
                    n2.setTag(new NodeTag("falseBranch", ifStm.getElseStatement()));
                } else {
                    // One or both blocks contains statements, must assign correct label

                    boolean n1HasStatements = n1next.getStatement() != null;
                    boolean n1IsTrueBranch;

                    if (n1HasStatements) {
                        n1IsTrueBranch = n1next.getStatement().getParent() == ifStm.getThenStatement();
                    } else {
                        n1IsTrueBranch = n2next.getStatement().getParent() == ifStm.getElseStatement();
                    }

                    if (n1IsTrueBranch) {
                        n1.setTag(new NodeTag("trueBranch", ifStm.getThenStatement()));
                        n2.setTag(new NodeTag("falseBranch", ifStm.getElseStatement()));
                    } else {
                        n1.setTag(new NodeTag("falseBranch", ifStm.getElseStatement()));
                        n2.setTag(new NodeTag("trueBranch", ifStm.getThenStatement()));
                    }
                }
            }
        }
    }

    private static ControlFlowNode findConvergenceNode(ControlFlowNode node) {
        // FIXME: this relies on implementation details of ControlFlowBuilder::visitCtIf and ControlFlowNode.count
        // A good fix would be adding this info explicitly to spoon-control-flow, e.g by having
        // ControlFlowNodes have a field .postBranchConverge that was set to the specific
        // convergence node created for the branch, and having a method ControlFlowNode::getPostBranchConvergenceNode
        return node.getParent().findNodeById(node.getId() + 1);
    }

    /**
     * @see ControlFlowGraph::vertexSet
     */
    public Set<ControlFlowNode> vertexSet() {
        return cfg.vertexSet();
    }

    /**
     * @see ControlFlowGraph::incomingEdgesOf
     */
    public Set<ControlFlowEdge> incomingEdgesOf(ControlFlowNode vertex) {
        return cfg.incomingEdgesOf(vertex);
    }

    /**
     * @see ControlFlowGraph::outgoingEdgesOf
     */
    public Set<ControlFlowEdge> outgoingEdgesOf(ControlFlowNode vertex) {
        return cfg.outgoingEdgesOf(vertex);
    }

    /**
     * @see ControlFlowGraph::findNodeById
     */
    public ControlFlowNode findNodeById(int id) {
        return cfg.findNodeById(id);
    }

    /**
     * @see ControlFlowGraph::findNodesOfKind
     */
    public List<ControlFlowNode> findNodesOfKind(BranchKind kind) {
        return cfg.findNodesOfKind(kind);
    }

    /**
     * @see ControlFlowGraph::getExitNode
     */
    public ControlFlowNode getExitNode() {
        return cfg.getExitNode();
    }

    @Override
    public String toString() {
        return cfg.toGraphVisText();
    }

    /**
     * Remove a node from a CFG, adding edges to preserve paths.
     * @param cfg CFG to operate on
     * @param node Node to remove
     */
    private static void removeNode(ControlFlowGraph cfg, ControlFlowNode node) {
        Set<ControlFlowEdge> incoming = cfg.incomingEdgesOf(node);
        Set<ControlFlowEdge> outgoing = cfg.outgoingEdgesOf(node);

        for (ControlFlowEdge in : incoming) {
            for (ControlFlowEdge out : outgoing) {
                cfg.addEdge(in.getSourceNode(), out.getTargetNode());
            }
        }

        cfg.edgesOf(node).forEach(cfg::removeEdge);
        cfg.removeVertex(node);
    }

    /**
     * Remove all nodes of kind BLOCK_END from a given CFG.
     * @param cfg CFG to operate on
     */
    private static void removeBlockEndNodes(ControlFlowGraph cfg) {
        cfg.findNodesOfKind(BranchKind.BLOCK_END).forEach((node) -> removeNode(cfg, node));
    }

    /**
     * If the BEGIN node has a BLOCK_BEGIN as successor, remove the BLOCK_BEGIN. This BLOCK_BEGIN
     * generally represents the block enclosing the full method body.
     * @param cfg CFG to operate on
     */
    private static void removeOutermostBlockBeginNode(ControlFlowGraph cfg) {
        cfg.findNodesOfKind(BranchKind.BEGIN).forEach((node) -> {
            if (node.next().get(0).getKind() == BranchKind.BLOCK_BEGIN) {
                removeNode(cfg, node.next().get(0));
            }
        });
    }

    /**
     * The adapted CFG.
     */
    private final ControlFlowGraph cfg;
}
