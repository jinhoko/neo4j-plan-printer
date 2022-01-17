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
package org.neo4j.cypher.internal.compiler

import org.neo4j.common.EntityType
import org.neo4j.cypher.internal.ast.CreateBtreeNodeIndex
import org.neo4j.cypher.internal.ast.CreateBtreeRelationshipIndex
import org.neo4j.cypher.internal.ast.CreateFulltextNodeIndex
import org.neo4j.cypher.internal.ast.CreateFulltextRelationshipIndex
import org.neo4j.cypher.internal.ast.CreateIndexOldSyntax
import org.neo4j.cypher.internal.ast.CreateLookupIndex
import org.neo4j.cypher.internal.ast.CreateNodeKeyConstraint
import org.neo4j.cypher.internal.ast.CreateNodePropertyExistenceConstraint
import org.neo4j.cypher.internal.ast.CreateRelationshipPropertyExistenceConstraint
import org.neo4j.cypher.internal.ast.CreateUniquePropertyConstraint
import org.neo4j.cypher.internal.ast.DropConstraintOnName
import org.neo4j.cypher.internal.ast.DropIndex
import org.neo4j.cypher.internal.ast.DropIndexOnName
import org.neo4j.cypher.internal.ast.DropNodeKeyConstraint
import org.neo4j.cypher.internal.ast.DropNodePropertyExistenceConstraint
import org.neo4j.cypher.internal.ast.DropRelationshipPropertyExistenceConstraint
import org.neo4j.cypher.internal.ast.DropUniquePropertyConstraint
import org.neo4j.cypher.internal.ast.IfExistsDo
import org.neo4j.cypher.internal.ast.IfExistsDoNothing
import org.neo4j.cypher.internal.ast.NoOptions
import org.neo4j.cypher.internal.ast.Options
import org.neo4j.cypher.internal.compiler.phases.LogicalPlanState
import org.neo4j.cypher.internal.compiler.phases.PlannerContext
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LabelName
import org.neo4j.cypher.internal.expressions.Property
import org.neo4j.cypher.internal.expressions.RelTypeName
import org.neo4j.cypher.internal.frontend.phases.BaseState
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase.PIPE_BUILDING
import org.neo4j.cypher.internal.frontend.phases.Phase
import org.neo4j.cypher.internal.logical.plans
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.planner.spi.AdministrationPlannerName
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.attribution.SequentialIdGen

/**
 * This planner takes on queries that requires no planning such as schema commands
 */
case object SchemaCommandPlanBuilder extends Phase[PlannerContext, BaseState, LogicalPlanState] {

  override def phase: CompilationPhase = PIPE_BUILDING

  override def postConditions: Set[StepSequencer.Condition] = Set.empty

  override def process(from: BaseState, context: PlannerContext): LogicalPlanState = {
    implicit val idGen: SequentialIdGen = new SequentialIdGen()

    def createBtreeIndex(entityName: Either[LabelName, RelTypeName],
                    props: List[Property],
                    name: Option[String],
                    ifExistsDo: IfExistsDo,
                    options: Options): Option[LogicalPlan] = {
      val propKeys = props.map(_.propertyKey)
      val source = ifExistsDo match {
        case IfExistsDoNothing => Some(plans.DoNothingIfExistsForBtreeIndex(entityName, propKeys, name))
        case _ => None
      }
      Some(plans.CreateBtreeIndex(source, entityName, propKeys, name, options))
    }

    def createFulltextIndex(entityNames: Either[List[LabelName], List[RelTypeName]],
                    props: List[Property],
                    name: Option[String],
                    ifExistsDo: IfExistsDo,
                    options: Options): Option[LogicalPlan] = {
      val propKeys = props.map(_.propertyKey)
      val source = ifExistsDo match {
        case IfExistsDoNothing => Some(plans.DoNothingIfExistsForFulltextIndex(entityNames, propKeys, name))
        case _ => None
      }
      Some(plans.CreateFulltextIndex(source, entityNames, propKeys, name, options))
    }

    val maybeLogicalPlan: Option[LogicalPlan] = from.statement() match {
      // CREATE CONSTRAINT [name] [IF NOT EXISTS] ON (node:Label) ASSERT (node.prop1,node.prop2) IS NODE KEY [OPTIONS {...}]
      case CreateNodeKeyConstraint(node, label, props, name, ifExistsDo, options, _) =>
        val source = ifExistsDo match {
          case IfExistsDoNothing => Some(plans.DoNothingIfExistsForConstraint(node.name, scala.util.Left(label), props, plans.NodeKey, name))
          case _ => None
        }
        Some(plans.CreateNodeKeyConstraint(source, node.name, label, props, name, options))

      // DROP CONSTRAINT ON (node:Label) ASSERT (node.prop1,node.prop2) IS NODE KEY
      case DropNodeKeyConstraint(_, label, props, _) =>
        Some(plans.DropNodeKeyConstraint(label, props))

      // CREATE CONSTRAINT [name] [IF NOT EXISTS] ON (node:Label) ASSERT node.prop IS UNIQUE [OPTIONS {...}]
      // CREATE CONSTRAINT [name] [IF NOT EXISTS] ON (node:Label) ASSERT (node.prop1,node.prop2) IS UNIQUE [OPTIONS {...}]
      case CreateUniquePropertyConstraint(node, label, props, name, ifExistsDo, options, _) =>
        val source = ifExistsDo match {
          case IfExistsDoNothing => Some(plans.DoNothingIfExistsForConstraint(node.name, scala.util.Left(label), props, plans.Uniqueness, name))
          case _ => None
        }
        Some(plans.CreateUniquePropertyConstraint(source, node.name, label, props, name, options))

      // DROP CONSTRAINT ON (node:Label) ASSERT node.prop IS UNIQUE
      // DROP CONSTRAINT ON (node:Label) ASSERT (node.prop1,node.prop2) IS UNIQUE
      case DropUniquePropertyConstraint(_, label, props, _) =>
        Some(plans.DropUniquePropertyConstraint(label, props))

      // CREATE CONSTRAINT [name] [IF NOT EXISTS] ON (node:Label) ASSERT EXISTS (node.prop)
      // CREATE CONSTRAINT [name] [IF NOT EXISTS] ON (node:Label) ASSERT node.prop IS NOT NULL
      case CreateNodePropertyExistenceConstraint(_, label, prop, name, ifExistsDo, _, _, _) =>
        val source = ifExistsDo match {
          case IfExistsDoNothing => Some(plans.DoNothingIfExistsForConstraint(prop.map.asCanonicalStringVal, scala.util.Left(label), Seq(prop), plans.NodePropertyExistence, name))
          case _ => None
        }
        Some(plans.CreateNodePropertyExistenceConstraint(source, label, prop, name))

      // DROP CONSTRAINT ON (node:Label) ASSERT EXISTS (node.prop)
      case DropNodePropertyExistenceConstraint(_, label, prop, _) =>
        Some(plans.DropNodePropertyExistenceConstraint(label, prop))

      // CREATE CONSTRAINT [name] [IF NOT EXISTS] ON ()-[r:R]-() ASSERT EXISTS (r.prop)
      // CREATE CONSTRAINT [name] [IF NOT EXISTS] ON ()-[r:R]-() ASSERT r.prop IS NOT NULL
      case CreateRelationshipPropertyExistenceConstraint(_, relType, prop, name, ifExistsDo, _, _, _) =>
        val source = ifExistsDo match {
          case IfExistsDoNothing => Some(plans.DoNothingIfExistsForConstraint(prop.map.asCanonicalStringVal, scala.util.Right(relType), Seq(prop), plans.RelationshipPropertyExistence, name))
          case _ => None
        }
        Some(plans.CreateRelationshipPropertyExistenceConstraint(source, relType, prop, name))

      // DROP CONSTRAINT ON ()-[r:R]-() ASSERT EXISTS (r.prop)
      case DropRelationshipPropertyExistenceConstraint(_, relType, prop, _) =>
        Some(plans.DropRelationshipPropertyExistenceConstraint(relType, prop))

      // DROP CONSTRAINT name [IF EXISTS]
      case DropConstraintOnName(name, ifExists, _) =>
        Some(plans.DropConstraintOnName(name, ifExists))
        
      // CREATE INDEX ON :LABEL(prop)
      case CreateIndexOldSyntax(label, props, _) =>
        Some(plans.CreateBtreeIndex(None, Left(label), props, None, NoOptions))

      // CREATE INDEX [name] [IF NOT EXISTS] FOR (n:LABEL) ON (n.prop) [OPTIONS {...}]
      case CreateBtreeNodeIndex(_, label, props, name, ifExistsDo, options, _) =>
        createBtreeIndex(Left(label), props, name, ifExistsDo, options)

      // CREATE INDEX [name] [IF NOT EXISTS] FOR ()-[r:RELATIONSHIP_TYPE]->() ON (r.prop) [OPTIONS {...}]
      case CreateBtreeRelationshipIndex(_, relType, props, name, ifExistsDo, options, _) =>
        createBtreeIndex(Right(relType), props, name, ifExistsDo, options)

      // CREATE LOOKUP INDEX [name] [IF NOT EXISTS] FOR (n) ON EACH labels(n)
      // CREATE LOOKUP INDEX [name] [IF NOT EXISTS] FOR ()-[r]-() ON [EACH] type(r)
      case CreateLookupIndex(_, isNodeIndex, _, name, ifExistsDo, _, _) =>
        val entityType = if (isNodeIndex) EntityType.NODE else EntityType.RELATIONSHIP
        val source = ifExistsDo match {
          case IfExistsDoNothing => Some(plans.DoNothingIfExistsForLookupIndex(entityType, name))
          case _ => None
        }
        Some(plans.CreateLookupIndex(source, entityType, name))

      // CREATE FULLTEXT INDEX [name] [IF NOT EXISTS] FOR (n[:LABEL[|...]]) ON EACH (n.prop[, ...]) [OPTIONS {...}]
      case CreateFulltextNodeIndex(_, labels, props, name, ifExistsDo, options, _) =>
        createFulltextIndex(Left(labels), props, name, ifExistsDo, options)

      // CREATE FULLTEXT INDEX [name] [IF NOT EXISTS] FOR ()-[r[:RELATIONSHIP_TYPE[|...]]]->() ON EACH (r.prop[, ...]) [OPTIONS {...}]
      case CreateFulltextRelationshipIndex(_, relTypes, props, name, ifExistsDo, options, _) =>
        createFulltextIndex(Right(relTypes), props, name, ifExistsDo, options)

      // DROP INDEX ON :LABEL(prop)
      case DropIndex(label, props, _) =>
        Some(plans.DropIndex(label, props))

      // DROP INDEX name [IF EXISTS]
      case DropIndexOnName(name, ifExists, _) =>
        Some(plans.DropIndexOnName(name, ifExists))

      case _ => None
    }

    val planState = LogicalPlanState(from)

    if (maybeLogicalPlan.isDefined)
      planState.copy(maybeLogicalPlan = maybeLogicalPlan, plannerName = AdministrationPlannerName)
    else planState
  }
}
