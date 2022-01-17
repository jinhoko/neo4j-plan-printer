//JHKO

package org.neo4j.cypher.internal.compiler.planner.logical.debug

import org.neo4j.cypher.internal.ast.AliasedReturnItem
import org.neo4j.cypher.internal.ast.Query
import org.neo4j.cypher.internal.ast.Return
import org.neo4j.cypher.internal.ast.ReturnItems
import org.neo4j.cypher.internal.ast.SingleQuery
import org.neo4j.cypher.internal.ast.Statement
import org.neo4j.cypher.internal.compiler.phases.LogicalPlanState
import org.neo4j.cypher.internal.compiler.phases.PlannerContext
import org.neo4j.cypher.internal.expressions.ListLiteral
import org.neo4j.cypher.internal.expressions.StringLiteral
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase.LOGICAL_PLANNING
import org.neo4j.cypher.internal.frontend.phases.Phase
import org.neo4j.cypher.internal.logical.plans.Argument
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.LogicalPlanToPlanBuilderString
import org.neo4j.cypher.internal.logical.plans.ProduceResult
import org.neo4j.cypher.internal.logical.plans.UnwindCollection
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.attribution.SequentialIdGen

import org.neo4j.cypher.internal.runtime.debug.DebugLog

/*
	Just print out logical plans
*/
case object DebugPlanPrinter extends Phase[PlannerContext, LogicalPlanState, LogicalPlanState] {

  DebugLog.beginTime()

  override def phase: CompilationPhaseTracer.CompilationPhase = LOGICAL_PLANNING

  override def process(from: LogicalPlanState, context: PlannerContext): LogicalPlanState = {

	// JHKO
    // val string = if (context.debugOptions.queryGraphEnabled)
    //   from.query.toString
    // else if (context.debugOptions.astEnabled)
    //   from.statement().toString
    // else if (context.debugOptions.semanticStateEnabled)
    //   from.semantics().toString
    // else if (context.debugOptions.logicalPlanEnabled)
    //   from.logicalPlan.toString
    // else if (context.debugOptions.logicalPlanBuilderEnabled)
    //   LogicalPlanToPlanBuilderString(from.logicalPlan)
    // else
    //   """Output options are: queryGraph, ast, semanticstate, logicalplan, logicalplanbuilder"""

	try {
    DebugLog.log(s"######################################################\n######################################################\n######################################################")
		DebugLog.log(s"QG : \n ${from.query.toString}")
		DebugLog.log(s"AST: \n ${from.statement().toString}")
		DebugLog.log(s"SEM: \n ${from.semantics().toString}")
		DebugLog.log(s"LP : \n ${from.logicalPlan.toString}")
		DebugLog.log(s"LPB: \n ${LogicalPlanToPlanBuilderString(from.logicalPlan)}")
	} catch {
		case _: Throwable => { DebugLog.log( "[DebugPlanPrinter] error occured while fetching logical query plans") }
	}

	// input never changes
	return from
  }

//   private def stringToLogicalPlan(string: String): (LogicalPlan, Statement, Seq[String]) = {
//     implicit val idGen = new SequentialIdGen()
//     val pos = InputPosition(0, 0, 0)
//     val stringValues = string.split(System.lineSeparator()).map(s => StringLiteral(s)(pos))
//     val expression = ListLiteral(stringValues.toSeq)(pos)
//     val unwind = UnwindCollection(Argument(Set.empty), "col", expression)
//     val logicalPlan = ProduceResult(unwind, Seq("col"))

//     val variable = Variable("col")(pos)
//     val returnItem = AliasedReturnItem(variable, variable)(pos)
//     val returnClause = Return(distinct = false, ReturnItems(includeExisting = false, Seq(returnItem))(pos), None, None, None, Set.empty)(pos)
//     val newStatement = Query(None, SingleQuery(Seq(returnClause))(pos))(pos)
//     val newReturnColumns = Seq("col")

//     (logicalPlan, newStatement, newReturnColumns)
//   }

  override def postConditions: Set[StepSequencer.Condition] = Set.empty

}