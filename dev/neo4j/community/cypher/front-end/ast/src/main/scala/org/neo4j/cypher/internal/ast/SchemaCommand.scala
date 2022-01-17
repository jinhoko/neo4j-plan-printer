/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.ast

import org.neo4j.cypher.internal.ast.semantics.SemanticAnalysisTooling
import org.neo4j.cypher.internal.ast.semantics.SemanticCheck
import org.neo4j.cypher.internal.ast.semantics.SemanticCheckResult
import org.neo4j.cypher.internal.ast.semantics.SemanticError
import org.neo4j.cypher.internal.ast.semantics.SemanticExpressionCheck
import org.neo4j.cypher.internal.expressions.FunctionInvocation
import org.neo4j.cypher.internal.expressions.FunctionName
import org.neo4j.cypher.internal.expressions.LabelName
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.Property
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.RelTypeName
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.expressions.functions.Labels
import org.neo4j.cypher.internal.expressions.functions.Type
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTRelationship
import org.neo4j.cypher.internal.util.symbols.CypherType

sealed trait SchemaCommand extends Statement {
  def useGraph: Option[GraphSelection]
  def withGraph(useGraph: Option[GraphSelection]): SchemaCommand

  override def returnColumns: List[LogicalVariable] = List.empty

  override def containsUpdates: Boolean = true
}

case class CreateIndexOldSyntax(label: LabelName, properties: List[PropertyKeyName], useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends SchemaCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
  def semanticCheck = Seq()
}

abstract class CreateIndex(variable: Variable, properties: List[Property], ifExistsDo: IfExistsDo, isNodeIndex: Boolean, options: Options)(val position: InputPosition)
  extends SchemaCommand with SemanticAnalysisTooling {
  override def semanticCheck: SemanticCheck = ifExistsDo match {
    case IfExistsInvalidSyntax | IfExistsReplace => SemanticError("Failed to create index: `OR REPLACE` cannot be used together with this command.", position)
    case _ =>
      val ctType = if (isNodeIndex) CTNode else CTRelationship
      declareVariable(variable, ctType) chain
        SemanticExpressionCheck.simple(properties) chain
        semanticCheckFold(properties) {
          property =>
            when(!property.map.isInstanceOf[Variable]) {
              error("Cannot index nested properties", property.position)
            }
        }
  }

  // The validation of the values (provider, config keys and config values) are done at runtime.
  def checkOptionsMap(indexString: String): SemanticCheck = options match {
    case OptionsMap(ops) if (ops.filterKeys(k => !k.equalsIgnoreCase("indexProvider") && !k.equalsIgnoreCase("indexConfig")).nonEmpty) =>
      SemanticError(s"Failed to create $indexString index: Invalid option provided, valid options are `indexProvider` and `indexConfig`.", position)
    case _ => SemanticCheckResult.success
  }
}

case class CreateBtreeNodeIndex(variable: Variable, label: LabelName, properties: List[Property], name: Option[String], ifExistsDo: IfExistsDo, options: Options, useGraph: Option[GraphSelection] = None)(override val position: InputPosition)
  extends CreateIndex(variable, properties, ifExistsDo, true, options)(position) {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck = checkOptionsMap("btree node") chain super.semanticCheck
}

case class CreateBtreeRelationshipIndex(variable: Variable, relType: RelTypeName, properties: List[Property], name: Option[String], ifExistsDo: IfExistsDo, options: Options, useGraph: Option[GraphSelection] = None)(override val position: InputPosition)
  extends CreateIndex(variable, properties, ifExistsDo, false, options)(position) {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck = checkOptionsMap("btree relationship property") chain super.semanticCheck
}

case class CreateLookupIndex(variable: Variable, isNodeIndex: Boolean, function: FunctionInvocation, name: Option[String], ifExistsDo: IfExistsDo, options: Options, useGraph: Option[GraphSelection] = None)(override val position: InputPosition)
  extends CreateIndex(variable, List.empty, ifExistsDo, isNodeIndex, options)(position) {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  private def allowedFunction(name: String): Boolean = if (isNodeIndex) name.equalsIgnoreCase(Labels.name) else name.equalsIgnoreCase(Type.name)

  override def semanticCheck: SemanticCheck = function match {
    case FunctionInvocation(_, FunctionName(name), _, _) if !allowedFunction(name) =>
      if (isNodeIndex) SemanticError(s"Failed to create node lookup index: Function '$name' is not allowed, valid function is '${Labels.name}'.", position)
      else SemanticError(s"Failed to create relationship lookup index: Function '$name' is not allowed, valid function is '${Type.name}'.", position)
    case _ =>
      if (options != NoOptions) SemanticError("Failed to create index: Lookup indexes do not support option values.", position)
      else super.semanticCheck chain SemanticExpressionCheck.simple(function)
  }
}

case class CreateFulltextNodeIndex(variable: Variable, label: List[LabelName], properties: List[Property], name: Option[String], ifExistsDo: IfExistsDo, options: Options, useGraph: Option[GraphSelection] = None)(override val position: InputPosition)
  extends CreateIndex(variable, properties, ifExistsDo, true, options)(position) {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck = checkOptionsMap("fulltext node") chain super.semanticCheck
}

case class CreateFulltextRelationshipIndex(variable: Variable, relType: List[RelTypeName], properties: List[Property], name: Option[String], ifExistsDo: IfExistsDo, options: Options, useGraph: Option[GraphSelection] = None)(override val position: InputPosition)
  extends CreateIndex(variable, properties, ifExistsDo, false, options)(position) {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck = checkOptionsMap("fulltext relationship") chain super.semanticCheck
}

case class DropIndex(label: LabelName, properties: List[PropertyKeyName], useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends SchemaCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
  def property: PropertyKeyName = properties.head
  def semanticCheck = Seq()
}

case class DropIndexOnName(name: String, ifExists: Boolean, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends SchemaCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
  def semanticCheck = Seq()
}

trait PropertyConstraintCommand extends SchemaCommand with SemanticAnalysisTooling {
  def variable: Variable

  def property: Property

  def entityType: CypherType

  override def semanticCheck =
    declareVariable(variable, entityType) chain
      SemanticExpressionCheck.simple(property) chain
      when(!property.map.isInstanceOf[Variable]) {
        error("Cannot index nested properties", property.position)
      }
}

trait CompositePropertyConstraintCommand extends SchemaCommand with SemanticAnalysisTooling {
  def variable: Variable

  def properties: Seq[Property]

  def entityType: CypherType

  def restrictedToSingleProperty: Boolean

  override def semanticCheck =
    declareVariable(variable, entityType) chain
      SemanticExpressionCheck.simple(properties) chain
      semanticCheckFold(properties) {
        property =>
          when(!property.map.isInstanceOf[Variable]) {
            error("Cannot index nested properties", property.position)
          }
      } chain
      when(restrictedToSingleProperty && properties.size > 1) {
        error("Only single property uniqueness constraints are supported", properties(1).position)
      }
}

trait NodePropertyConstraintCommand extends PropertyConstraintCommand {

  val entityType = CTNode

  def label: LabelName
}

trait UniquePropertyConstraintCommand extends CompositePropertyConstraintCommand {

  val entityType = CTNode

  def label: LabelName

  override def restrictedToSingleProperty: Boolean = true
}

trait NodeKeyConstraintCommand extends CompositePropertyConstraintCommand {

  val entityType = CTNode

  def label: LabelName

  override def restrictedToSingleProperty: Boolean = false
}

trait RelationshipPropertyConstraintCommand extends PropertyConstraintCommand {

  val entityType = CTRelationship

  def relType: RelTypeName
}

case class CreateNodeKeyConstraint(variable: Variable, label: LabelName, properties: Seq[Property], name: Option[String], ifExistsDo: IfExistsDo, options: Options, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends NodeKeyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck =  ifExistsDo match {
    case IfExistsInvalidSyntax | IfExistsReplace => SemanticError(s"Failed to create node key constraint: `OR REPLACE` cannot be used together with this command.", position)
    case _ => options match {
      // The validation of the values (provider, config keys and config values) are done at runtime.
      case OptionsMap(ops) if (ops.filterKeys(k => !k.equalsIgnoreCase("indexProvider") && !k.equalsIgnoreCase("indexConfig")).nonEmpty) =>
        SemanticError(s"Failed to create node key constraint: Invalid option provided, valid options are `indexProvider` and `indexConfig`.", position)
      case _ =>  super.semanticCheck
  }}
}

case class DropNodeKeyConstraint(variable: Variable, label: LabelName, properties: Seq[Property], useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends NodeKeyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
}

case class CreateUniquePropertyConstraint(variable: Variable, label: LabelName, properties: Seq[Property], name: Option[String], ifExistsDo: IfExistsDo, options: Options, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends UniquePropertyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck =  ifExistsDo match {
    case IfExistsInvalidSyntax | IfExistsReplace => SemanticError(s"Failed to create uniqueness constraint: `OR REPLACE` cannot be used together with this command.", position)
    case _ => options match {
      // The validation of the values (provider, config keys and config values) are done at runtime.
      case OptionsMap(ops) if (ops.filterKeys(k => !k.equalsIgnoreCase("indexProvider") && !k.equalsIgnoreCase("indexConfig")).nonEmpty) =>
        SemanticError(s"Failed to create uniqueness constraint: Invalid option provided, valid options are `indexProvider` and `indexConfig`.", position)
      case _ => super.semanticCheck
  }}
}

case class DropUniquePropertyConstraint(variable: Variable, label: LabelName, properties: Seq[Property], useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends UniquePropertyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
}

case class CreateNodePropertyExistenceConstraint(variable: Variable, label: LabelName, property: Property, name: Option[String], ifExistsDo: IfExistsDo, oldSyntax: Boolean, options: Options, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends NodePropertyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck =  ifExistsDo match {
    case IfExistsInvalidSyntax | IfExistsReplace => SemanticError(s"Failed to create node property existence constraint: `OR REPLACE` cannot be used together with this command.", position)
    case _ =>
      if (options != NoOptions) SemanticError(s"Failed to create node property existence constraint: `OPTIONS` cannot be used together with this command.", position)
      else super.semanticCheck
  }
}

case class DropNodePropertyExistenceConstraint(variable: Variable, label: LabelName, property: Property, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends NodePropertyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
}

case class CreateRelationshipPropertyExistenceConstraint(variable: Variable, relType: RelTypeName, property: Property, name: Option[String], ifExistsDo: IfExistsDo, oldSyntax: Boolean, options: Options, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends RelationshipPropertyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)

  override def semanticCheck: SemanticCheck =  ifExistsDo match {
    case IfExistsInvalidSyntax | IfExistsReplace => SemanticError(s"Failed to create relationship property existence constraint: `OR REPLACE` cannot be used together with this command.", position)
    case _ =>
      if (options != NoOptions) SemanticError(s"Failed to create relationship property existence constraint: `OPTIONS` cannot be used together with this command.", position)
      else super.semanticCheck
  }
}

case class DropRelationshipPropertyExistenceConstraint(variable: Variable, relType: RelTypeName, property: Property, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends RelationshipPropertyConstraintCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
}

case class DropConstraintOnName(name: String, ifExists: Boolean, useGraph: Option[GraphSelection] = None)(val position: InputPosition) extends SchemaCommand {
  override def withGraph(useGraph: Option[GraphSelection]): SchemaCommand = copy(useGraph = useGraph)(position)
  def semanticCheck = Seq()
}
