/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.compiler.planner.ProcedureCallProjection
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.CardinalityModel
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.QueryGraphCardinalityModel
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.QueryGraphSolverInput
import org.neo4j.cypher.internal.compiler.planner.logical.PlannerDefaults.DEFAULT_DISTINCT_SELECTIVITY
import org.neo4j.cypher.internal.compiler.planner.logical.PlannerDefaults.DEFAULT_LIMIT_ROW_COUNT
import org.neo4j.cypher.internal.compiler.planner.logical.PlannerDefaults.DEFAULT_MULTIPLIER
import org.neo4j.cypher.internal.compiler.planner.logical.PlannerDefaults.DEFAULT_SKIP_ROW_COUNT
import org.neo4j.cypher.internal.compiler.planner.logical.StatisticsBackedCardinalityModel.CardinalityAndInput
import org.neo4j.cypher.internal.compiler.planner.logical.cardinality.IndependenceCombiner
import org.neo4j.cypher.internal.compiler.planner.logical.cardinality.SelectivityCombiner
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.FunctionInvocation
import org.neo4j.cypher.internal.expressions.FunctionName
import org.neo4j.cypher.internal.expressions.IntegerLiteral
import org.neo4j.cypher.internal.expressions.ListLiteral
import org.neo4j.cypher.internal.expressions.Namespace
import org.neo4j.cypher.internal.ir.AggregatingQueryProjection
import org.neo4j.cypher.internal.ir.CallSubqueryHorizon
import org.neo4j.cypher.internal.ir.CommandProjection
import org.neo4j.cypher.internal.ir.DistinctQueryProjection
import org.neo4j.cypher.internal.ir.LoadCSVProjection
import org.neo4j.cypher.internal.ir.PassthroughAllHorizon
import org.neo4j.cypher.internal.ir.PlannerQueryPart
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.QueryHorizon
import org.neo4j.cypher.internal.ir.QueryPagination
import org.neo4j.cypher.internal.ir.QueryProjection
import org.neo4j.cypher.internal.ir.RegularQueryProjection
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.ir.UnionQuery
import org.neo4j.cypher.internal.ir.UnwindProjection
import org.neo4j.cypher.internal.util.Cardinality
import org.neo4j.cypher.internal.util.Multiplier
import org.neo4j.values.storable.NumberValue

class StatisticsBackedCardinalityModel(queryGraphCardinalityModel: QueryGraphCardinalityModel, simpleExpressionEvaluator: ExpressionEvaluator) extends CardinalityModel {

  private val expressionSelectivityCalculator = queryGraphCardinalityModel.expressionSelectivityCalculator
  private val combiner: SelectivityCombiner = IndependenceCombiner

  def apply(queryPart: PlannerQueryPart, input: QueryGraphSolverInput, semanticTable: SemanticTable): Cardinality = {
    queryPart match {
      case query: SinglePlannerQuery =>
        val output = query.fold(CardinalityAndInput(Cardinality.SINGLE, input)) {
          case (CardinalityAndInput(inboundCardinality, input), plannerQuery) =>
            val CardinalityAndInput(qgCardinality, afterQGInput) = calculateCardinalityForQueryGraph(plannerQuery.queryGraph, input, semanticTable)
            val beforeHorizonCardinality = qgCardinality * inboundCardinality

            val afterHorizon =
              calculateCardinalityForQueryHorizon(CardinalityAndInput(beforeHorizonCardinality, afterQGInput), plannerQuery.horizon, semanticTable)
            afterHorizon.copy(input = afterHorizon.input.withFusedLabelInfo(plannerQuery.firstLabelInfo))
        }
        output.cardinality

      case UnionQuery(part, query, distinct, _) =>
        val unionCardinality = apply(part, input, semanticTable) + apply(query, input, semanticTable)
        if (distinct) {
          unionCardinality * DEFAULT_DISTINCT_SELECTIVITY
        } else {
          unionCardinality
        }
    }
  }

  private def calculateCardinalityForQueryHorizon(cardinalityAndInput: CardinalityAndInput, horizon: QueryHorizon, semanticTable: SemanticTable): CardinalityAndInput = horizon match {
    case projection: QueryProjection =>
      val cardinalityBeforeSkip = queryProjectionCardinalityBeforeLimit(cardinalityAndInput.cardinality, projection)
      val cardinalityBeforeLimit = queryProjectionCardinalityWithSkip(cardinalityBeforeSkip, projection.queryPagination)
      val cardinalityBeforeSelection = queryProjectionCardinalityWithLimit(cardinalityBeforeLimit, projection.queryPagination)
      queryProjectionCardinalityWithSelections(CardinalityAndInput(cardinalityBeforeSelection, cardinalityAndInput.input), projection.selections, semanticTable)

    // Unwind
    case UnwindProjection(_, expression) =>
      val multiplier = expression match {
        case ListLiteral(expressions) => Multiplier(expressions.size)
        case FunctionInvocation(Namespace(Seq()), FunctionName("range"), _, Seq(from: IntegerLiteral, to: IntegerLiteral)) =>
          val diff = to.value - from.value + 1
          Multiplier(Math.max(0, diff))
        case FunctionInvocation(Namespace(Seq()), FunctionName("range"), _, Seq(from: IntegerLiteral, to: IntegerLiteral, step: IntegerLiteral)) =>
          val diff = to.value - from.value
          val steps = diff / step.value + 1
          Multiplier(Math.max(0, steps))
        case _ => DEFAULT_MULTIPLIER
      }
      cardinalityAndInput.copy(cardinality = cardinalityAndInput.cardinality * multiplier)

    // ProcedureCall
    case _: ProcedureCallProjection =>
      cardinalityAndInput.copy(cardinality = Cardinality.max(cardinalityAndInput.cardinality * DEFAULT_MULTIPLIER, 1.0)) // At least 1 row

    // Show command - e.g. ShowIndexesCommand
    case _: CommandProjection =>
      cardinalityAndInput.copy(cardinality = Cardinality.max(cardinalityAndInput.cardinality * DEFAULT_MULTIPLIER, 1.0)) // At least 1 row

    // Load CSV
    case _: LoadCSVProjection =>
      cardinalityAndInput.copy(cardinality = Cardinality.max(cardinalityAndInput.cardinality * DEFAULT_MULTIPLIER, 1.0)) // At least 1 row

    case _: PassthroughAllHorizon =>
      cardinalityAndInput

    case CallSubqueryHorizon(subquery, _) =>
      val subQueryCardinality = apply(subquery, cardinalityAndInput.input, semanticTable)
      // Cardinality of the subquery times current cardinality is the result
      cardinalityAndInput.copy(cardinality = cardinalityAndInput.cardinality * subQueryCardinality)
  }

  private def queryProjectionCardinalityBeforeLimit(in: Cardinality, projection: QueryProjection): Cardinality = projection match {
    case _: RegularQueryProjection =>
      in

    case _: DistinctQueryProjection =>
      in * DEFAULT_DISTINCT_SELECTIVITY
    case agg: AggregatingQueryProjection if agg.aggregationExpressions.isEmpty =>
      in * DEFAULT_DISTINCT_SELECTIVITY

    case agg: AggregatingQueryProjection =>
      StatisticsBackedCardinalityModel.aggregateCardinalityEstimation(in, agg.groupingExpressions)
  }

  private def queryProjectionCardinalityWithLimit(cardinalityBeforeLimit: Cardinality, queryPagination: QueryPagination): Cardinality = {
    queryPagination.limit match {
      case None => cardinalityBeforeLimit
      case Some(limitExpression) =>
        val limitRowCount: Long = evaluateLongIfStable(limitExpression).getOrElse(DEFAULT_LIMIT_ROW_COUNT)

        if (limitRowCount >= cardinalityBeforeLimit.amount) cardinalityBeforeLimit
        else Cardinality(limitRowCount)
    }
  }

  private def queryProjectionCardinalityWithSkip(cardinalityBeforeSkip: Cardinality, queryPagination: QueryPagination): Cardinality = {
    queryPagination.skip match {
      case None => cardinalityBeforeSkip
      case Some(skipExpression) =>
        val skipRowCount: Long = evaluateLongIfStable(skipExpression).getOrElse(DEFAULT_SKIP_ROW_COUNT)

        if (skipRowCount == 0) cardinalityBeforeSkip
        else if (skipRowCount >= cardinalityBeforeSkip.amount) Cardinality.EMPTY
        else cardinalityBeforeSkip.map(c => c - skipRowCount)

    }
  }

  /*
   * Returns the evaluated long value from the specified expression if the expression is stable and can be evaluated to a long.
   */
  private def evaluateLongIfStable(expression: Expression): Option[Long] = {
    def isStable(expression: Expression): Boolean = {
      !simpleExpressionEvaluator.hasParameters(expression) && simpleExpressionEvaluator.isDeterministic(expression)
    }

    expression match {
      case literal: IntegerLiteral => Some(literal.value)
      case nonLiteral if isStable(nonLiteral) =>
        simpleExpressionEvaluator
          .evaluateExpression(nonLiteral)
          .collect { case number: NumberValue => number.longValue() }
      case _ => None
    }
  }

  private def queryProjectionCardinalityWithSelections(inputBeforeSelection: CardinalityAndInput,
                                                       where: Selections,
                                                       semanticTable: SemanticTable): CardinalityAndInput = {
    val inboundCardinality = inputBeforeSelection.cardinality
    val fusedInput = inputBeforeSelection.input.withFusedLabelInfo(where.labelInfo)
    val expressionSelectivities = where.flatPredicates.map(expressionSelectivityCalculator(_, fusedInput.labelInfo, fusedInput.relTypeInfo)(semanticTable))
    val maybeWhereSelectivity = combiner.andTogetherSelectivities(expressionSelectivities)
    val cardinality = maybeWhereSelectivity match {
      case Some(whereSelectivity) => inboundCardinality * whereSelectivity
      case None => inboundCardinality
    }
    CardinalityAndInput(cardinality, fusedInput)
  }

  private def calculateCardinalityForQueryGraph(graph: QueryGraph,
                                                input: QueryGraphSolverInput,
                                                semanticTable: SemanticTable): CardinalityAndInput = {
    val fusedInput = input.withFusedLabelInfo(graph.patternNodeLabels).withFusedRelTypeInfo(graph.patternRelationshipTypes)
    val cardinality = queryGraphCardinalityModel(graph, fusedInput, semanticTable)
    CardinalityAndInput(cardinality, fusedInput)
  }
}

object StatisticsBackedCardinalityModel {

  case class CardinalityAndInput(cardinality: Cardinality, input: QueryGraphSolverInput)

  def aggregateCardinalityEstimation(in: Cardinality, groupingExpressions: Map[String, Expression]): Cardinality =
    if (groupingExpressions.isEmpty)
      Cardinality.SINGLE
    else
      // if input cardinality is < 1 the sqrt is bigger than the original value which makes no sense for aggregations
      Cardinality.min(in, Cardinality.sqrt(in))
}
