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
package org.neo4j.cypher.internal.runtime.interpreted.commands.expressions

import org.neo4j.cypher.internal.logical.plans.UserFunctionSignature
import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.UserDefinedAggregator
import org.neo4j.cypher.internal.runtime.interpreted.commands.AstNode
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.internal.runtime.interpreted.pipes.aggregation.AggregationFunction
import org.neo4j.memory.HeapEstimator.shallowSizeOfInstanceWithObjectReferences
import org.neo4j.memory.MemoryTracker
import org.neo4j.values.AnyValue

case class AggregationFunctionInvocation(signature: UserFunctionSignature, override val arguments: IndexedSeq[Expression])
  extends AggregationExpression {

  override def createAggregationFunction(memoryTracker: MemoryTracker): AggregationFunction = {
    memoryTracker.allocateHeap(AggregationFunctionInvocation.SHALLOW_SIZE)
    new AggregationFunction {
      private var inner: UserDefinedAggregator = _

      override def result(state: QueryState): AnyValue = {
        aggregator(state).result
      }

      override def apply(data: ReadableRow, state: QueryState): Unit = {
        val argValues = arguments.map(arg => {
          arg(data, state)
        })
        aggregator(state).update(argValues)
      }

      private def aggregator(state: QueryState) = {
        if (inner == null) {
          inner = call(state)
        }
        inner
      }
    }
  }

  override def children: Seq[AstNode[_]] = arguments

  protected def call(state: QueryState): UserDefinedAggregator =
    state.query.aggregateFunction(signature.id, signature.allowed)

  override def rewrite(f: Expression => Expression): Expression =
    f(AggregationFunctionInvocation(signature, arguments.map(a => a.rewrite(f))))
}

object AggregationFunctionInvocation {
  // AggregationFunction instances are usually created from nested anonymous classes with some outer context:
  // This should give a low-end approximate.
  final val SHALLOW_SIZE: Long =
    shallowSizeOfInstanceWithObjectReferences(2) + // AggregationFunction: 1 ref + 1 $outer
    shallowSizeOfInstanceWithObjectReferences(1) + // UserDefinedAggregator: 1 ref
    shallowSizeOfInstanceWithObjectReferences(3)   // UserAggregator: 2 refs + 1 this$0 (one is a new SecurityContext, but not counted)
}