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
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.runtime.spec.Edition
import org.neo4j.cypher.internal.runtime.spec.LogicalQueryBuilder
import org.neo4j.cypher.internal.runtime.spec.RuntimeTestSuite

abstract class CachePropertiesTestBase[CONTEXT <: RuntimeContext](
                                                                   edition: Edition[CONTEXT],
                                                                   runtime: CypherRuntime[CONTEXT],
                                                                   sizeHint: Int
                                                                 ) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("should not explode on cached properties") {
    // given
    val nodes = given { nodePropertyGraph(sizeHint, { case i => Map("p" -> i)}) }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("n")
      .filter("cache[n.p] < 20 == 0")
      .cacheProperties("cache[n.p]")
      .allNodeScan("n")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = nodes.take(20).map(n => Array(n))
    runtimeResult should beColumns("n").withRows(expected)
  }

  test("handle cached properties in node index seek on the RHS of an apply") {
    // given
    val b = given {
      nodeIndex("B", "id")
      nodePropertyGraph(
        sizeHint, { case i => Map("id" -> i)}, "A")
      nodePropertyGraph(1, {case _ => Map("id" -> 1)}, "B").head
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("b")
      .apply()
      .|.nodeIndexOperator("b:B(id = ???)", paramExpr = Some(cachedNodeProp("a", "id")), argumentIds = Set("b"))
      .nodeByLabelScan("a", "A")
      .build()
    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("b").withSingleRow(b)
  }
}
