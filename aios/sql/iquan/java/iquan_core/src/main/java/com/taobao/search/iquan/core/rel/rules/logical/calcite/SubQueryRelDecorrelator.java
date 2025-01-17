package com.taobao.search.iquan.core.rel.rules.logical.calcite;

import com.google.common.collect.ImmutableSet;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.logical.*;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.*;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.*;
import org.apache.calcite.util.mapping.Mappings;

import java.util.*;

public class SubQueryRelDecorrelator implements ReflectiveVisitor {
    // map built during translation
    private final CorelMap cm;
    private final RelBuilder relBuilder;
    private final RexBuilder rexBuilder;
    private final ReflectUtil.MethodDispatcher<Frame> dispatcher =
            ReflectUtil.createMethodDispatcher(
                    Frame.class, this, "decorrelateRel", RelNode.class);
    private final int maxCnfNodeCount;

    SubQueryRelDecorrelator(
            CorelMap cm, RelBuilder relBuilder, RexBuilder rexBuilder, int maxCnfNodeCount) {
        this.cm = cm;
        this.relBuilder = relBuilder;
        this.rexBuilder = rexBuilder;
        this.maxCnfNodeCount = maxCnfNodeCount;
    }

    Frame getInvoke(RelNode r) {
        return dispatcher.invoke(r);
    }

    /**
     * Rewrite LogicalProject.
     *
     * <p>Rewrite logic: Pass along any correlated variables coming from the input.
     *
     * @param rel the project rel to rewrite
     */
    public Frame decorrelateRel(LogicalProject rel) {
        final RelNode oldInput = rel.getInput();
        Frame frame = getInvoke(oldInput);
        if (frame == null) {
            // If input has not been rewritten, do not rewrite this rel.
            return null;
        }

        final List<RexNode> oldProjects = rel.getProjects();
        final List<RelDataTypeField> relOutput = rel.getRowType().getFieldList();
        final RelNode newInput = frame.r;

        // Project projects the original expressions,
        // plus any correlated variables the input wants to pass along.
        List<RexNode> projects = new ArrayList<>();
        List<String> names = new ArrayList<>();
//        final List<Pair<RexNode, String>> projects = new ArrayList<>();

        // If this Project has correlated reference, produce the correlated variables in the new
        // output.
        // TODO Currently, correlation in projection is not supported.
        assert !cm.getMapRefRelToCorRef().containsKey(rel);

        final Map<Integer, Integer> mapInputToOutput = new HashMap<>();
        final Map<Integer, Integer> mapOldToNewOutputs = new HashMap<>();
        // Project projects the original expressions
        int newPos;
        for (newPos = 0; newPos < oldProjects.size(); newPos++) {
            RexNode project =
                    DeCorrelateUtils.adjustInputRefs(
                            oldProjects.get(newPos),
                            frame.oldToNewOutputs,
                            newInput.getRowType());
//            projects.add(newPos, Pair.of(project, relOutput.get(newPos).getName()));
            projects.add(newPos, project);
            names.add(newPos, relOutput.get(newPos).getName());
            mapOldToNewOutputs.put(newPos, newPos);
            if (project instanceof RexInputRef) {
                mapInputToOutput.put(((RexInputRef) project).getIndex(), newPos);
            }
        }

        if (frame.c != null) {
            // Project any correlated variables the input wants to pass along.
            final ImmutableBitSet corInputIndices = RelOptUtil.InputFinder.bits(frame.c);
            final RelDataType inputRowType = newInput.getRowType();
            for (int inputIndex : corInputIndices.toList()) {
                if (!mapInputToOutput.containsKey(inputIndex)) {
                    projects.add(newPos, RexInputRef.of(inputIndex, inputRowType));
                    names.add(newPos, inputRowType.getFieldNames().get(inputIndex));
//                    projects.add(
//                            newPos,
//                            Pair.of(
//                                    RexInputRef.of(inputIndex, inputRowType),
//                                    inputRowType.getFieldNames().get(inputIndex)));
                    mapInputToOutput.put(inputIndex, newPos);
                    newPos++;
                }
            }
        }
        RelNode newProject = relBuilder.push(newInput).projectNamed(projects, names, true).build();
//        RelNode newProject = RelOptUtil.createProject(newInput, projects, false);

        final RexNode newCorCondition;
        if (frame.c != null) {
            newCorCondition =
                    DeCorrelateUtils.adjustInputRefs(frame.c, mapInputToOutput, newProject.getRowType());
        } else {
            newCorCondition = null;
        }

        return new Frame(rel, newProject, newCorCondition, mapOldToNewOutputs);
    }

    /**
     * Rewrite LogicalFilter.
     *
     * <p>Rewrite logic: 1. If a Filter references a correlated field in its filter condition,
     * rewrite the Filter references only non-correlated fields, and the condition references
     * correlated fields will be push to it's output. 2. If Filter does not reference correlated
     * variables, simply rewrite the filter condition using new input.
     *
     * @param rel the filter rel to rewrite
     */
    public Frame decorrelateRel(LogicalFilter rel) {
        final RelNode oldInput = rel.getInput();
        Frame frame = getInvoke(oldInput);
        if (frame == null) {
            // If input has not been rewritten, do not rewrite this rel.
            return null;
        }

        // Conditions reference only correlated fields
        final List<RexNode> corConditions = new ArrayList<>();
        // Conditions do not reference any correlated fields
        final List<RexNode> nonCorConditions = new ArrayList<>();
        // Conditions reference correlated fields, but not supported now
        final List<RexNode> unsupportedCorConditions = new ArrayList<>();

        DeCorrelateUtils.analyzeCorConditions(
                cm.getMapSubQueryNodeToCorSet().get(rel),
                rel.getCondition(),
                rexBuilder,
                maxCnfNodeCount,
                corConditions,
                nonCorConditions,
                unsupportedCorConditions);
        assert unsupportedCorConditions.isEmpty();

        final RexNode remainingCondition =
                RexUtil.composeConjunction(rexBuilder, nonCorConditions, false);

        // Using LogicalFilter.create instead of RelBuilder.filter to create Filter
        // because RelBuilder.filter method does not have VariablesSet arg.
        final LogicalFilter newFilter =
                LogicalFilter.create(
                        frame.r,
                        remainingCondition,
                        ImmutableSet.copyOf(rel.getVariablesSet()));

        // Adds input's correlation condition
        if (frame.c != null) {
            corConditions.add(frame.c);
        }

        final RexNode corCondition =
                RexUtil.composeConjunction(rexBuilder, corConditions, true);
        // Filter does not change the input ordering.
        // All corVars produced by filter will have the same output positions in the input rel.
        return new Frame(rel, newFilter, corCondition, frame.oldToNewOutputs);
    }

    /**
     * Rewrites a {@link LogicalAggregate}.
     *
     * <p>Rewrite logic: 1. Permute the group by keys to the front. 2. If the input of an
     * aggregate produces correlated variables, add them to the group list. 3. Change aggCalls
     * to reference the new project.
     *
     * @param rel Aggregate to rewrite
     */
    public Frame decorrelateRel(LogicalAggregate rel) {
        // Aggregate itself should not reference corVars.
        assert !cm.getMapRefRelToCorRef().containsKey(rel);

        final RelNode oldInput = rel.getInput();
        final Frame frame = getInvoke(oldInput);
        if (frame == null) {
            // If input has not been rewritten, do not rewrite this rel.
            return null;
        }

        final RelNode newInput = frame.r;
        // map from newInput
        final Map<Integer, Integer> mapNewInputToProjOutputs = new HashMap<>();
        final int oldGroupKeyCount = rel.getGroupSet().cardinality();

        // Project projects the original expressions,
        // plus any correlated variables the input wants to pass along.
//        final List<Pair<RexNode, String>> projects = new ArrayList<>();
        final List<RexNode> projects = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<RelDataTypeField> newInputOutput = newInput.getRowType().getFieldList();

        // oldInput has the original group by keys in the front.
        final NavigableMap<Integer, RexLiteral> omittedConstants = new TreeMap<>();
        int newPos = 0;
        for (int i = 0; i < oldGroupKeyCount; i++) {
            final RexLiteral constant = DeCorrelateUtils.projectedLiteral(newInput, i);
            if (constant != null) {
                // Exclude constants. Aggregate({true}) occurs because Aggregate({})
                // would generate 1 row even when applied to an empty table.
                omittedConstants.put(i, constant);
                continue;
            }

            int newInputPos = frame.oldToNewOutputs.get(i);
            Pair<RexNode, String> pair = RexInputRef.of2(newInputPos, newInputOutput);
            projects.add(newPos, pair.left);
            names.add(newPos, pair.right);
//            projects.add(newPos, RexInputRef.of2(newInputPos, newInputOutput));
            mapNewInputToProjOutputs.put(newInputPos, newPos);
            newPos++;
        }

        if (frame.c != null) {
            // If input produces correlated variables, move them to the front,
            // right after any existing GROUP BY fields.

            // Now add the corVars from the input, starting from position oldGroupKeyCount.
            for (Integer index : frame.getCorInputRefIndices()) {
                if (!mapNewInputToProjOutputs.containsKey(index)) {
//                    projects.add(newPos, RexInputRef.of2(index, newInputOutput));
                    Pair<RexNode, String> pair = RexInputRef.of2(index, newInputOutput);
                    projects.add(newPos, pair.left);
                    names.add(newPos, pair.right);
                    mapNewInputToProjOutputs.put(index, newPos);
                    newPos++;
                }
            }
        }

        // add the remaining fields
        final int newGroupKeyCount = newPos;
        for (int i = 0; i < newInputOutput.size(); i++) {
            if (!mapNewInputToProjOutputs.containsKey(i)) {
//                projects.add(newPos, RexInputRef.of2(i, newInputOutput));
                Pair<RexNode, String> pair = RexInputRef.of2(i, newInputOutput);
                projects.add(newPos, pair.left);
                names.add(newPos, pair.right);
                mapNewInputToProjOutputs.put(i, newPos);
                newPos++;
            }
        }

        assert newPos == newInputOutput.size();

        // This Project will be what the old input maps to,
        // replacing any previous mapping from old input).
//        final RelNode newProject = RelOptUtil.createProject(newInput, projects, false);
        final RelNode newProject = relBuilder.push(newInput).projectNamed(projects, names, true).build();

        final RexNode newCondition;
        if (frame.c != null) {
            newCondition =
                    DeCorrelateUtils.adjustInputRefs(frame.c, mapNewInputToProjOutputs, newProject.getRowType());
        } else {
            newCondition = null;
        }

        // update mappings:
        // oldInput ----> newInput
        //
        //                newProject
        //                   |
        // oldInput ----> newInput
        //
        // is transformed to
        //
        // oldInput ----> newProject
        //                   |
        //                newInput

        final Map<Integer, Integer> combinedMap = new HashMap<>();
        final Map<Integer, Integer> oldToNewOutputs = new HashMap<>();
        final List<Integer> originalGrouping = rel.getGroupSet().toList();
        for (Integer oldInputPos : frame.oldToNewOutputs.keySet()) {
            final Integer newIndex =
                    mapNewInputToProjOutputs.get(frame.oldToNewOutputs.get(oldInputPos));
            combinedMap.put(oldInputPos, newIndex);
            // mapping grouping fields
            if (originalGrouping.contains(oldInputPos)) {
                oldToNewOutputs.put(oldInputPos, newIndex);
            }
        }

        // now it's time to rewrite the Aggregate
        final ImmutableBitSet newGroupSet = ImmutableBitSet.range(newGroupKeyCount);
        final List<AggregateCall> newAggCalls = new ArrayList<>();
        final List<AggregateCall> oldAggCalls = rel.getAggCallList();

        for (AggregateCall oldAggCall : oldAggCalls) {
            final List<Integer> oldAggArgs = oldAggCall.getArgList();
            final List<Integer> aggArgs = new ArrayList<>();

            // Adjust the Aggregate argument positions.
            // Note Aggregate does not change input ordering, so the input
            // output position mapping can be used to derive the new positions
            // for the argument.
            for (int oldPos : oldAggArgs) {
                aggArgs.add(combinedMap.get(oldPos));
            }
            final int filterArg =
                    oldAggCall.filterArg < 0
                            ? oldAggCall.filterArg
                            : combinedMap.get(oldAggCall.filterArg);

            newAggCalls.add(
                    oldAggCall.adaptTo(
                            newProject,
                            aggArgs,
                            filterArg,
                            oldGroupKeyCount,
                            newGroupKeyCount));
        }

        relBuilder.push(
                LogicalAggregate.create(newProject, false, newGroupSet, null, newAggCalls));

        if (!omittedConstants.isEmpty()) {
            final List<RexNode> postProjects = new ArrayList<>(relBuilder.fields());
            for (Map.Entry<Integer, RexLiteral> entry : omittedConstants.entrySet()) {
                postProjects.add(
                        mapNewInputToProjOutputs.get(entry.getKey()), entry.getValue());
            }
            relBuilder.project(postProjects);
        }

        // mapping aggCall output fields
        for (int i = 0; i < oldAggCalls.size(); ++i) {
            oldToNewOutputs.put(
                    oldGroupKeyCount + i, newGroupKeyCount + omittedConstants.size() + i);
        }

        // Aggregate does not change input ordering so corVars will be
        // located at the same position as the input newProject.
        return new Frame(rel, relBuilder.build(), newCondition, oldToNewOutputs);
    }

    /**
     * Rewrite LogicalJoin.
     *
     * <p>Rewrite logic: 1. rewrite join condition. 2. map output positions and produce corVars
     * if any.
     *
     * @param rel Join
     */
    public Frame decorrelateRel(LogicalJoin rel) {
        final RelNode oldLeft = rel.getInput(0);
        final RelNode oldRight = rel.getInput(1);

        final Frame leftFrame = getInvoke(oldLeft);
        final Frame rightFrame = getInvoke(oldRight);

        if (leftFrame == null || rightFrame == null) {
            // If any input has not been rewritten, do not rewrite this rel.
            return null;
        }

        switch (rel.getJoinType()) {
            case LEFT:
                assert rightFrame.c == null;
                break;
            case RIGHT:
                assert leftFrame.c == null;
                break;
            case FULL:
                assert leftFrame.c == null && rightFrame.c == null;
                break;
            default:
                break;
        }

        final int oldLeftFieldCount = oldLeft.getRowType().getFieldCount();
        final int newLeftFieldCount = leftFrame.r.getRowType().getFieldCount();
        final int oldRightFieldCount = oldRight.getRowType().getFieldCount();
        assert rel.getRowType().getFieldCount() == oldLeftFieldCount + oldRightFieldCount;

        final RexNode newJoinCondition =
                DeCorrelateUtils.adjustJoinCondition(
                        rel.getCondition(),
                        oldLeftFieldCount,
                        newLeftFieldCount,
                        leftFrame.oldToNewOutputs,
                        rightFrame.oldToNewOutputs);

        final RelNode newJoin =
                LogicalJoin.create(
                        leftFrame.r,
                        rightFrame.r,
                        rel.getHints(),
                        newJoinCondition,
                        rel.getVariablesSet(),
                        rel.getJoinType());

        // Create the mapping between the output of the old correlation rel and the new join rel
        final Map<Integer, Integer> mapOldToNewOutputs = new HashMap<>();
        // Left input positions are not changed.
        mapOldToNewOutputs.putAll(leftFrame.oldToNewOutputs);

        // Right input positions are shifted by newLeftFieldCount.
        for (int i = 0; i < oldRightFieldCount; i++) {
            mapOldToNewOutputs.put(
                    i + oldLeftFieldCount,
                    rightFrame.oldToNewOutputs.get(i) + newLeftFieldCount);
        }

        final List<RexNode> corConditions = new ArrayList<>();
        if (leftFrame.c != null) {
            corConditions.add(leftFrame.c);
        }
        if (rightFrame.c != null) {
            // Right input positions are shifted by newLeftFieldCount.
            final Map<Integer, Integer> rightMapOldToNewOutputs = new HashMap<>();
            for (int index : rightFrame.getCorInputRefIndices()) {
                rightMapOldToNewOutputs.put(index, index + newLeftFieldCount);
            }
            final RexNode newRightCondition =
                    DeCorrelateUtils.adjustInputRefs(
                            rightFrame.c, rightMapOldToNewOutputs, newJoin.getRowType());
            corConditions.add(newRightCondition);
        }

        final RexNode newCondition =
                RexUtil.composeConjunction(rexBuilder, corConditions, true);
        return new Frame(rel, newJoin, newCondition, mapOldToNewOutputs);
    }

    /**
     * Rewrite Sort.
     *
     * <p>Rewrite logic: change the collations field to reference the new input.
     *
     * @param rel Sort to be rewritten
     */
    public Frame decorrelateRel(Sort rel) {
        // Sort itself should not reference corVars.
        assert !cm.getMapRefRelToCorRef().containsKey(rel);

        // Sort only references field positions in collations field.
        // The collations field in the newRel now need to refer to the
        // new output positions in its input.
        // Its output does not change the input ordering, so there's no
        // need to call propagateExpr.
        final RelNode oldInput = rel.getInput();
        final Frame frame = getInvoke(oldInput);
        if (frame == null) {
            // If input has not been rewritten, do not rewrite this rel.
            return null;
        }
        final RelNode newInput = frame.r;

        Mappings.TargetMapping mapping =
                Mappings.target(
                        frame.oldToNewOutputs,
                        oldInput.getRowType().getFieldCount(),
                        newInput.getRowType().getFieldCount());

        RelCollation oldCollation = rel.getCollation();
        RelCollation newCollation = RexUtil.apply(mapping, oldCollation);

        final Sort newSort = LogicalSort.create(newInput, newCollation, rel.offset, rel.fetch);

        // Sort does not change input ordering
        return new Frame(rel, newSort, frame.c, frame.oldToNewOutputs);
    }

    /**
     * Rewrites a {@link Values}.
     *
     * @param rel Values to be rewritten
     */
    public Frame decorrelateRel(Values rel) {
        // There are no inputs, so rel does not need to be changed.
        return null;
    }

    public Frame decorrelateRel(LogicalCorrelate rel) {
        // does not allow correlation condition in its inputs now, so choose default behavior
        return decorrelateRel((RelNode) rel);
    }

    /**
     * Fallback if none of the other {@code decorrelateRel} methods match.
     */
    public Frame decorrelateRel(RelNode rel) {
        RelNode newRel = rel.copy(rel.getTraitSet(), rel.getInputs());
        if (rel.getInputs().size() > 0) {
            List<RelNode> oldInputs = rel.getInputs();
            List<RelNode> newInputs = new ArrayList<>();
            for (int i = 0; i < oldInputs.size(); ++i) {
                final Frame frame = getInvoke(oldInputs.get(i));
                if (frame == null || frame.c != null) {
                    // if input is not rewritten, or if it produces correlated variables,
                    // terminate rewrite
                    return null;
                }
                newInputs.add(frame.r);
                newRel.replaceInput(i, frame.r);
            }

            if (!Util.equalShallow(oldInputs, newInputs)) {
                newRel = rel.copy(rel.getTraitSet(), newInputs);
            }
        }
        // the output position should not change since there are no corVars coming from below.
        return new Frame(rel, newRel, null, DeCorrelateUtils.identityMap(rel.getRowType().getFieldCount()));
    }
}
