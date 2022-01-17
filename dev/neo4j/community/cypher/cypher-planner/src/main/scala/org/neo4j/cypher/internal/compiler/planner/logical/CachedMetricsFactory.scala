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
import org.neo4j.cypher.internal.compiler.CypherPlannerConfiguration
import org.neo4j.cypher.internal.compiler.ExecutionModel
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.CardinalityModel
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.CostModel
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.QueryGraphCardinalityModel
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.QueryGraphSolverInput
import org.neo4j.cypher.internal.compiler.planner.logical.cardinality.ExpressionSelectivityCalculator
import org.neo4j.cypher.internal.ir.PlannerQueryPart
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.helpers.CachedFunction
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.planner.spi.GraphStatistics
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes.Cardinalities
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes.ProvidedOrders
import org.neo4j.cypher.internal.util.Cardinality
import org.neo4j.cypher.internal.util.Ref

case class CachedMetricsFactory(metricsFactory: MetricsFactory) extends MetricsFactory {
  override def newCardinalityEstimator(queryGraphCardinalityModel: QueryGraphCardinalityModel, evaluator: ExpressionEvaluator): CardinalityModel = {
    val wrapped: CardinalityModel = metricsFactory.newCardinalityEstimator(queryGraphCardinalityModel, evaluator)
    val cached = CachedFunction[PlannerQueryPart, Metrics.QueryGraphSolverInput, SemanticTable, Cardinality] { (a, b, c) => wrapped(a, b, c) }
    (query: PlannerQueryPart, input: Metrics.QueryGraphSolverInput, semanticTable: SemanticTable) => cached.apply(query, input, semanticTable)
  }

  /*
   * The arguments to the CachedFunction are the cache-key, e.g plan, input, semanticTable, cardinalities, providedOrders, monitor.
   *
   * We wrap the Cardinalities/ProvidedOrders object in the `Ref` class since we don't need to compare the attributes inside
   * the Cardinalities/ProvidedOrders object.
   * The reason for this is that the objects are mutable and during planning we modify them instead of creating new ones.
   */
  override def newCostModel(config: CypherPlannerConfiguration, executionModel: ExecutionModel): CostModel = {
    val cached = CachedFunction(
      (plan: LogicalPlan,
       input: QueryGraphSolverInput,
       semanticTable: SemanticTable,
       cardinalities: Ref[Cardinalities],
       providedOrders: Ref[ProvidedOrders],
       monitor: CostModelMonitor) => {
        SimpleMetricsFactory.newCostModel(config: CypherPlannerConfiguration, executionModel).costFor(plan, input, semanticTable, cardinalities.value,
          providedOrders.value, monitor)
      })
    (plan: LogicalPlan, input: Metrics.QueryGraphSolverInput, semanticTable: SemanticTable, cardinalities: Cardinalities, providedOrders: ProvidedOrders, monitor: CostModelMonitor) =>
      cached(plan, input, semanticTable, Ref(cardinalities), Ref(providedOrders), monitor)
  }

  override def newQueryGraphCardinalityModel(statistics: GraphStatistics): QueryGraphCardinalityModel = {
    val wrapped: QueryGraphCardinalityModel = metricsFactory.newQueryGraphCardinalityModel(statistics)
    val cached = CachedFunction[QueryGraph, Metrics.QueryGraphSolverInput, SemanticTable, Cardinality] { (a, b, c) => wrapped(a, b, c) }
    new QueryGraphCardinalityModel {
      override def apply(queryGraph: QueryGraph, input: Metrics.QueryGraphSolverInput, semanticTable: SemanticTable): Cardinality = {
        cached.apply(queryGraph, input, semanticTable)
      }

      override val expressionSelectivityCalculator: ExpressionSelectivityCalculator = wrapped.expressionSelectivityCalculator
    }
  }

}
