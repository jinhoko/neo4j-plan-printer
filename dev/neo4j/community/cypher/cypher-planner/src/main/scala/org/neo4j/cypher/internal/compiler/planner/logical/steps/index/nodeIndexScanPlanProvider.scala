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
package org.neo4j.cypher.internal.compiler.planner.logical.steps.index

import org.neo4j.cypher.internal.ast.Hint
import org.neo4j.cypher.internal.compiler.planner.logical.LeafPlanRestrictions
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.plans.AsPropertyScannable
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.EntityIndexScanPlanProvider.Solution
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.EntityIndexScanPlanProvider.isAllowedByRestrictions
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.EntityIndexScanPlanProvider.mergeSolutions
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.EntityIndexScanPlanProvider.predicatesForIndexScan
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.NodeIndexLeafPlanner.IndexMatch
import org.neo4j.cypher.internal.expressions.LabelToken
import org.neo4j.cypher.internal.logical.plans.IndexOrder
import org.neo4j.cypher.internal.logical.plans.IndexedProperty
import org.neo4j.cypher.internal.logical.plans.LogicalPlan

case class nodeIndexScanPlanProvider(
  // This is a temporary hack to get rid of some plans that always win cost comparisons against Seek plans on the same index
  seekPlannerPeek: NodeIndexPlanProviderPeek = NodeIndexPlanProviderPeek.default
) extends NodeIndexPlanProvider {

  /**
   * Container for all values that define a NodeIndexScan plan
   */
  case class NodeIndexScanParameters(
                                      idName: String,
                                      token: LabelToken,
                                      properties: Seq[IndexedProperty],
                                      argumentIds: Set[String],
                                      indexOrder: IndexOrder,
                                    )

  override def createPlans(indexMatches: Set[IndexMatch], hints: Set[Hint], argumentIds: Set[String], restrictions: LeafPlanRestrictions, context: LogicalPlanningContext): Set[LogicalPlan] = {

    // This is a temporary hack to get rid of some plans that always win cost comparisons against Seek plans on the same index
    // We emulate the cases where a Scan was produced by the old leaf planners
    def shouldAttemptPlanning(indexMatch: IndexMatch): Boolean =
      if (indexMatch.propertyPredicates.size == 1) {
        indexMatch.propertyPredicates.head.predicate match {
          case AsPropertyScannable(_) => true
          case _                      => false
        }
      } else {
        def hasImplicits = indexMatch.hasImplicitPredicates
        def seekPlanned = seekPlannerPeek.wouldCreatePlan(indexMatch, hints, argumentIds, restrictions, context)

        !hasImplicits && !seekPlanned
      }

    val solutions = for {
      indexMatch <- indexMatches
      if isAllowedByRestrictions(indexMatch.variableName, restrictions)
      if shouldAttemptPlanning(indexMatch)
    } yield createSolution(indexMatch, hints, argumentIds, context)

    val distinctSolutions = mergeSolutions(solutions)

    distinctSolutions.map(solution =>
      context.logicalPlanProducer.planNodeIndexScan(
        idName = solution.indexScanParameters.idName,
        label = solution.indexScanParameters.token,
        properties = solution.indexScanParameters.properties,
        solvedPredicates = solution.solvedPredicates,
        solvedHint = solution.solvedHint,
        argumentIds = solution.indexScanParameters.argumentIds,
        providedOrder = solution.providedOrder,
        indexOrder = solution.indexScanParameters.indexOrder,
        context = context,
      )
    )
  }

  private def createSolution(indexMatch: IndexMatch, hints: Set[Hint], argumentIds: Set[String], context: LogicalPlanningContext): Solution[NodeIndexScanParameters] = {
    val predicateSet = indexMatch.predicateSet(predicatesForIndexScan(indexMatch.propertyPredicates), exactPredicatesCanGetValue = false)

    val hint = predicateSet.matchingHints(hints).find(_.spec.fulfilledByScan)

    Solution(
      NodeIndexScanParameters(
        idName = indexMatch.variableName,
        token = indexMatch.labelToken,
        properties = predicateSet.indexedProperties(context),
        argumentIds = argumentIds,
        indexOrder = indexMatch.indexOrder,
      ),
      solvedPredicates = predicateSet.allSolvedPredicates,
      solvedHint = hint,
      providedOrder = indexMatch.providedOrder,
    )
  }
}
