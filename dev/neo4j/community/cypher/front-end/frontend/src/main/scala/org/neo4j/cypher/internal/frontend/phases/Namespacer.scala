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
package org.neo4j.cypher.internal.frontend.phases

import org.neo4j.cypher.internal.ast.ProcedureResultItem
import org.neo4j.cypher.internal.ast.ProjectingUnionAll
import org.neo4j.cypher.internal.ast.ProjectingUnionDistinct
import org.neo4j.cypher.internal.ast.Statement
import org.neo4j.cypher.internal.ast.UnionAll
import org.neo4j.cypher.internal.ast.UnionDistinct
import org.neo4j.cypher.internal.ast.semantics.Scope
import org.neo4j.cypher.internal.ast.semantics.SemanticFeature
import org.neo4j.cypher.internal.ast.semantics.SymbolUse
import org.neo4j.cypher.internal.expressions.ExpressionWithOuterScope
import org.neo4j.cypher.internal.expressions.ProcedureOutput
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase.AST_REWRITE
import org.neo4j.cypher.internal.frontend.phases.factories.PlanPipelineTransformerFactory
import org.neo4j.cypher.internal.rewriting.conditions.SemanticInfoAvailable
import org.neo4j.cypher.internal.rewriting.conditions.containsNoNodesOfType
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.Ref
import org.neo4j.cypher.internal.util.Rewriter
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.bottomUp
import org.neo4j.cypher.internal.util.inSequence

case object AmbiguousNamesDisambiguated extends StepSequencer.Condition

/**
 * Rename variables so they are all unique.
 */
case object Namespacer extends Phase[BaseContext, BaseState, BaseState] with StepSequencer.Step with PlanPipelineTransformerFactory {
  type VariableRenamings = Map[Ref[Variable], Variable]

  override def phase: CompilationPhase = AST_REWRITE

  override def process(from: BaseState, ignored: BaseContext): BaseState = {
    val withProjectedUnions = from.statement().endoRewrite(projectUnions)
    val table = from.semanticTable()

    val ambiguousNames = shadowedNames(from.semantics().scopeTree)
    thrownOnAmbiguousAnonymousNames(ambiguousNames)
    val variableDefinitions: Map[SymbolUse, SymbolUse] = from.semantics().scopeTree.allVariableDefinitions
    val renamings = variableRenamings(withProjectedUnions, variableDefinitions, ambiguousNames)

    if (renamings.isEmpty) {
      from.withStatement(withProjectedUnions).withSemanticTable(table)
    } else {
      val rewriter = renamingRewriter(renamings)
      val newStatement = withProjectedUnions.endoRewrite(rewriter)
      val newSemanticTable = table.replaceExpressions(rewriter)
      from.withStatement(newStatement).withSemanticTable(newSemanticTable)
    }
  }

  private def thrownOnAmbiguousAnonymousNames(ambiguousNames: Set[String]): Unit = {
    ambiguousNames.filter(AnonymousVariableNameGenerator.notNamed).foreach { n =>
      throw new IllegalStateException(s"Anonymous variable `$n` is ambiguous. This is a bug.")
    }
  }

  private def shadowedNames(scopeTree: Scope): Set[String] = {
    val definitions = scopeTree.allSymbolDefinitions

    definitions.collect {
      case (name, symbolDefinitions) if symbolDefinitions.size > 1 => name
    }.toSet
  }

  private def variableRenamings(statement: Statement, variableDefinitions: Map[SymbolUse, SymbolUse],
                                ambiguousNames: Set[String]): VariableRenamings =
    statement.treeFold(Map.empty[Ref[Variable], Variable]) {
      case i: Variable if ambiguousNames(i.name) =>
        val renaming = createVariableRenaming(variableDefinitions, i)
        acc => TraverseChildren(acc + renaming)
      case e: ExpressionWithOuterScope =>
        val renamings = e.outerScope
          .filter(v => ambiguousNames(v.name))
          .foldLeft(Set[(Ref[Variable], Variable)]()) { (innerAcc, v) =>
            innerAcc + createVariableRenaming(variableDefinitions, v)
          }
        acc => TraverseChildren(acc ++ renamings)
    }

  private def createVariableRenaming(variableDefinitions: Map[SymbolUse, SymbolUse], v: Variable): (Ref[Variable], Variable) = {
    val symbolDefinition = variableDefinitions(SymbolUse(v))
    val newVariable = v.renameId(s"  ${symbolDefinition.nameWithPosition}")
    val renaming = Ref(v) -> newVariable
    renaming
  }

  private def projectUnions: Rewriter =
    bottomUp(Rewriter.lift {
      case u: UnionAll => ProjectingUnionAll(u.part, u.query, u.unionMappings)(u.position)
      case u: UnionDistinct => ProjectingUnionDistinct(u.part, u.query, u.unionMappings)(u.position)
    })

  private def renamingRewriter(renamings: VariableRenamings): Rewriter = inSequence(
    bottomUp(Rewriter.lift {
      case item@ProcedureResultItem(None, v: Variable) if renamings.contains(Ref(v)) =>
        item.copy(output = Some(ProcedureOutput(v.name)(v.position)))(item.position)
    }),
    bottomUp(Rewriter.lift {
      case v: Variable =>
        renamings.get(Ref(v)) match {
          case Some(newVariable) => newVariable
          case None              => v
        }
      case e: ExpressionWithOuterScope =>
        val newOuterScope = e.outerScope.map(v => {
          renamings.get(Ref(v)) match {
            case Some(newVariable) => newVariable
            case None              => v
          }
        })
        e.withOuterScope(newOuterScope)
    }))

  override def preConditions: Set[StepSequencer.Condition] = SemanticInfoAvailable

  override def postConditions: Set[StepSequencer.Condition] = Set(
    StatementCondition(containsNoNodesOfType[UnionAll]),
    StatementCondition(containsNoNodesOfType[UnionDistinct]),
    AmbiguousNamesDisambiguated
  )

  override def invalidatedConditions: Set[StepSequencer.Condition] = SemanticInfoAvailable // Introduces new AST nodes

  override def getTransformer(pushdownPropertyReads: Boolean,
                              semanticFeatures: Seq[SemanticFeature]): Transformer[BaseContext, BaseState, BaseState] = this
}
