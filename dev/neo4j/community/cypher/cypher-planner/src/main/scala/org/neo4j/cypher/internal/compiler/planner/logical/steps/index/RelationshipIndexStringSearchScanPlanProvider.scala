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
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.EntityIndexScanPlanProvider.isAllowedByRestrictions
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.RelationshipIndexLeafPlanner.IndexMatch
import org.neo4j.cypher.internal.expressions.Contains
import org.neo4j.cypher.internal.expressions.EndsWith
import org.neo4j.cypher.internal.logical.plans.LogicalPlan

object RelationshipIndexStringSearchScanPlanProvider extends RelationshipIndexPlanProvider {

  override def createPlans(indexMatches: Set[IndexMatch], hints: Set[Hint], argumentIds: Set[String], restrictions: LeafPlanRestrictions, context: LogicalPlanningContext): Set[LogicalPlan] = for {
    indexMatch <- indexMatches
    if isAllowedByRestrictions(indexMatch.variableName, restrictions) && indexMatch.indexDescriptor.properties.size == 1
    plan <- doCreatePlans(indexMatch, hints, argumentIds, context)
  } yield plan

  private def doCreatePlans(indexMatch: IndexMatch, hints: Set[Hint], argumentIds: Set[String], context: LogicalPlanningContext): Set[LogicalPlan] = {
    indexMatch.propertyPredicates.flatMap { indexPredicate =>
      indexPredicate.predicate match {
        case predicate@ (_:Contains | _:EndsWith) =>
          val (valueExpr, stringSearchMode) = predicate match {
            case contains: Contains =>
              (contains.rhs, ContainsSearchMode)
            case endsWith: EndsWith =>
              (endsWith.rhs, EndsWithSearchMode)
          }
          val singlePredicateSet = indexMatch.predicateSet(Seq(indexPredicate), exactPredicatesCanGetValue = false)

          val plan = context.logicalPlanProducer.planRelationshipIndexStringSearchScan(
            idName = indexMatch.variableName,
            relationshipType = indexMatch.relationshipTypeToken,
            pattern = indexMatch.patternRelationship,
            properties = singlePredicateSet.indexedProperties(context),
            stringSearchMode = stringSearchMode,
            solvedPredicates = singlePredicateSet.allSolvedPredicates,
            solvedHint = singlePredicateSet.matchingHints(hints).find(_.spec.fulfilledByScan),
            valueExpr = valueExpr,
            argumentIds = argumentIds,
            providedOrder = indexMatch.providedOrder,
            indexOrder = indexMatch.indexOrder,
            context = context)
          Some(plan)

        case _ =>
          None
      }

    }.toSet
  }
}
