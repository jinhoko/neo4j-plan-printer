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

import org.neo4j.cypher.internal.compiler.helpers.LogicalPlanBuilder
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNode
import org.neo4j.cypher.internal.logical.plans.Ascending
import org.neo4j.cypher.internal.logical.plans.IndexOrderNone
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class SubQueryPlanningIntegrationTest extends CypherFunSuite with LogicalPlanningTestSupport2 {

  // Uncorrelated subqueries

  test("CALL around single query") {
    val query = "CALL { RETURN 1 as x } RETURN 2 as y"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("y")
        .projection("2 AS y")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("CALL around single query - using returned var in outer query") {
    val query = "CALL { RETURN 1 as x } RETURN x"
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("CALLs in sequence") {
    val query = "CALL { RETURN 1 AS x } CALL { RETURN 2 AS y } RETURN x, y"
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("x", "y")
        .cartesianProduct(fromSubquery = true)
        .|.projection("2 AS y")
        .|.argument()
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("Simple nested subqueries") {
    val query = "CALL { CALL { CALL { RETURN 1 as x } RETURN x } RETURN x } RETURN x"
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("Nested subqueries") {
    val query =
      """CALL {
        |  CALL {
        |    CALL {
        |      MATCH (a) RETURN a
        |    }
        |    MATCH (b) RETURN a, b
        |  }
        |  MATCH (c) RETURN a, b, c
        |}
        |RETURN a, b, c""".stripMargin
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("a", "b", "c")
        .apply()
        .|.allNodeScan("c", "a", "b")
        .apply()
        .|.allNodeScan("b", "a")
        .allNodeScan("a")
        .build()
    )
  }

  test("CALL around union query") {
    val query = "CALL { RETURN 1 as x UNION RETURN 2 as x } RETURN 3 as y"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("y")
        .projection("3 AS y")
        .distinct("x AS x")
        .union()
        .|.projection("x AS x")
        .|.projection("2 AS x")
        .|.argument()
        .projection("x AS x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("CALL around union query - using returned var in outer query") {
    val query = "CALL { RETURN 1 as x UNION RETURN 2 as x } RETURN x"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("x")
        .distinct("x AS x")
        .union()
        .|.projection("x AS x")
        .|.projection("2 AS x")
        .|.argument()
        .projection("x AS x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("CALL around union query - using returned var in outer query with MATCH afterwards") {
    val query = "CALL { RETURN 1 as x UNION RETURN 2 as x } MATCH (y) WHERE y.prop = x RETURN y"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("y")
        .filter("y.prop = x")
        .apply()
        .|.allNodeScan("y", "x")
        .distinct("x AS x")
        .union()
        .|.projection("x AS x")
        .|.projection("2 AS x")
        .|.argument()
        .projection("x AS x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("CALL around union query - using returned var in outer query with STUFF afterwards") {
    val query = "CALL { RETURN 1 as x UNION RETURN 2 as x } MATCH (y) WHERE y.prop = x RETURN sum(y.number) AS sum"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("sum")
        .aggregation(Seq.empty, Seq("sum(y.number) AS sum"))
        .filter("y.prop = x")
        .apply()
        .|.allNodeScan("y", "x")
        .distinct("x AS x")
        .union()
        .|.projection("x AS x")
        .|.projection("2 AS x")
        .|.argument()
        .projection("x AS x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("This should solve the WHERE clause") {
    val query = "WITH 1 AS x CALL { MATCH (y) WHERE y.prop = 5 RETURN y } RETURN x + 1 AS res, y"
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("res", "y")
        .projection("x + 1 AS res")
        .cartesianProduct(fromSubquery = true)
        .|.filter("y.prop = 5")
        .|.allNodeScan("y")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("This should solve the aggregation on the RHS of the CartesianProduct") {
    val query = "WITH 1 AS x CALL { MATCH (y) RETURN sum(y.prop) AS sum } RETURN x + 1 AS res, sum"
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("res", "sum")
        .projection("x + 1 AS res")
        .cartesianProduct(fromSubquery = true)
        .|.aggregation(Seq.empty, Seq("sum(y.prop) AS sum"))
        .|.allNodeScan("y")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("Complex query") {
    val query = normalizeNewLines(
      """MATCH (x:X)-[r]->(n) WHERE x.prop = 5
        |CALL {
        |  MATCH (y:Y) RETURN sum(y.number) AS sum
        |   UNION
        |  UNWIND range(0, 10) AS i MATCH (x:X) WHERE x.prop = i RETURN sum(x.number) AS sum
        |}
        |RETURN count(n) AS c, sum""".stripMargin)

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("c", "sum")
        .aggregation(Seq("sum AS sum"), Seq("count(n) AS c"))
        .cartesianProduct(fromSubquery = true)
        .|.distinct("sum AS sum")
        .|.union()
        .|.|.projection("sum AS sum")
        .|.|.aggregation(Seq.empty, Seq("sum(x.number) AS sum"))
        .|.|.filter("x.prop = i")
        .|.|.apply()
        .|.|.|.nodeByLabelScan("x", "X", IndexOrderNone, "i")
        .|.|.unwind("range(0, 10) AS i")
        .|.|.argument()
        .|.projection("sum AS sum")
        .|.aggregation(Seq.empty, Seq("sum(y.number) AS sum"))
        .|.nodeByLabelScan("y", "Y", IndexOrderNone)
        .expand("(x)-[r]->(n)")
        .filter("x.prop = 5")
        .nodeByLabelScan("x", "X", IndexOrderNone)
        .build()
    )
  }

  test("Should treat variables with the same name but different scopes correctly") {
    // Here x and x are two different things
    val query = "MATCH (x) CALL { MATCH (y)-[r]->(x:X) RETURN y } RETURN 5 AS five"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("five")
        .projection("5 AS five")
        .cartesianProduct(fromSubquery = true)
        .|.expand("(x)<-[r]-(y)")
        .|.nodeByLabelScan("x", "X")
        .allNodeScan("x")
        .build()
    )
  }

  test("should plan count store lookup in uncorrelated subquery") {
    val query =
      """MATCH (n)
        |CALL {
        | MATCH (x)-[r:REL]->(y)
        | RETURN count(*) AS c
        |}
        |RETURN n, c""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("n", "c")
        .cartesianProduct(fromSubquery = true)
        .|.relationshipCountFromCountStore("c", None, Seq("REL"), None)
        .allNodeScan("n")
        .build()
    )
  }

  // Correlated subqueries

  test("CALL around single correlated query") {
    val query = "WITH 1 AS x CALL { WITH x RETURN x as y } RETURN y"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("y")
        .projection("x AS y")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("nested correlated subqueries") {
    val query = "WITH 1 AS a CALL { WITH a CALL { WITH a CALL { WITH a RETURN a AS b } RETURN b AS c } RETURN c AS d } RETURN d"
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("d")
        .projection("c AS d")
        .projection("b AS c")
        .projection("a AS b")
        .projection("1 AS a")
        .argument()
        .build()
    )
  }

  test("CALL around correlated union query") {
    val query = normalizeNewLines(
      """
        |WITH 1 AS x, 2 AS y CALL {
        |  WITH x RETURN x AS z
        |  UNION
        |  WITH y RETURN y AS z
        |} RETURN z""".stripMargin)

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("z")
        .apply(fromSubquery = true)
        .|.distinct(s"z AS z")
        .|.union()
        .|.|.projection(s"z AS z")
        .|.|.projection(s"y AS z")
        .|.|.argument("y")
        .|.projection(s"z AS z")
        .|.projection(s"x AS z")
        .|.argument("x")
        .projection("2 AS y", "1 AS x")
        .argument()
        .build()
    )
  }

  test("This should solve the aggregation on the RHS of the Apply") {
    val query = "WITH 1 AS x CALL { WITH x MATCH (y) WHERE y.value > x RETURN sum(y.prop) AS sum } RETURN sum"
    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("sum")
        .apply(fromSubquery = true)
        .|.aggregation(Seq.empty, Seq("sum(y.prop) AS sum"))
        .|.filter("y.value > x")
        .|.allNodeScan("y", "x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("correlated CALL in a sequence with ambiguous variable names") {
    val query = "WITH 1 AS x CALL { WITH x RETURN x as y } CALL { MATCH (x) RETURN 1 AS z } RETURN y"

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("y")
        .cartesianProduct(fromSubquery = true)
        .|.projection("1 AS z")
        .|.allNodeScan("x")
        .projection(s"x AS y")
        .projection(s"1 AS x")
        .argument()
        .build()
    )
  }

  test("nested correlated CALLs with aggregation") {
    val query =
      """WITH 1 AS x
        |CALL {
        | WITH x
        | CALL { WITH x RETURN max(x) AS xmax }
        | CALL { WITH x RETURN min(x) AS xmin }
        | RETURN xmax, xmin
        |}
        |RETURN x, xmax, xmin
        |""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("x", "xmax", "xmin")
        .apply(fromSubquery = true)
        .|.apply(fromSubquery = true)
        .|.|.aggregation(Seq.empty, Seq("min(x) as xmin"))
        .|.|.argument("x")
        .|.apply(fromSubquery = true)
        .|.|.aggregation(Seq.empty, Seq("max(x) as xmax"))
        .|.|.argument("x")
        .|.argument("x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("correlated CALL with ordered aggregation") {
    val query =
      """WITH 1 AS x
        |CALL {
        | WITH x
        | WITH x AS y
        | ORDER BY y
        | RETURN y, max(y) as ymax
        |}
        |RETURN x, y, ymax
        |""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("x", "y", "ymax")
        .apply(fromSubquery = true)
        .|.orderedAggregation(Seq("y AS y"), Seq("max(y) AS ymax"), Seq("y") )
        .|.sort(Seq(Ascending("y")))
        .|.projection("x AS y")
        .|.argument("x")
        .projection("1 AS x")
        .argument()
        .build()
    )
  }

  test("excessive aliasing should not confuse namespacer") {
    val query = normalizeNewLines(
      """WITH 1 AS q
        |CALL {
        |  MATCH (a:A)
        |  RETURN a AS a, 1 AS b
        |  UNION
        |  WITH q
        |  MATCH (a:B)
        |  RETURN q AS b, a AS a
        |}
        |RETURN a AS q, b AS a, q AS b
        |""".stripMargin)

    planFor(query, stripProduceResults = false)._2 should equal {
      new LogicalPlanBuilder()
        .produceResults("q", "a", "b")
        .projection(s"a AS q", s"b AS a", s"q AS b")
        .apply(fromSubquery = true)
        .|.distinct(s"a AS a", s"b AS b")
        .|.union()
        .|.|.projection(s"a AS a", s"b AS b")
        .|.|.projection(s"q AS b")
        .|.|.nodeByLabelScan("a", "B", IndexOrderNone, "q")
        .|.projection(s"a AS a", s"b AS b")
        .|.projection(s"1 AS b")
        .|.nodeByLabelScan("a", "A", IndexOrderNone)
        .projection(s"1 AS q")
        .argument()
        .build()
    }
  }

  test("should not plan count store lookup in correlated subquery when node-variable is already bound") {
    val query =
      """MATCH (n)
        |CALL {
        | WITH n
        | MATCH (n)-[r:REL]->(m)
        | RETURN count(*) AS c
        |}
        |RETURN n, c""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("n", "c")
        .apply(fromSubquery = true)
        .|.aggregation(Seq.empty, Seq("count(*) AS c"))
        .|.expand("(n)-[r:REL]->(m)")
        .|.argument("n")
        .allNodeScan("n")
        .build()
    )
  }

  test("should not plan count store lookup in correlated subquery when relationship-variable is already bound") {
    val query =
      """MATCH (n)-[r:REL]->(m:M)
        |CALL {
        | WITH r
        | MATCH (x)-[r:REL]->(y)
        | RETURN count(*) AS c
        |}
        |RETURN n, c""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("n", "c")
        .apply(fromSubquery = true)
        .|.aggregation(Seq.empty, Seq("count(*) AS c"))
        .|.projectEndpoints("(x)-[r:REL]->(y)", startInScope = false, endInScope = false)
        .|.argument("r")
        .expand("(m)<-[r:REL]-(n)")
        .nodeByLabelScan("m", "M")
        .build()
    )
  }

  test("should plan uncorrelated subquery with updates with Apply (which gets unnested)") {
    val query =
      """MATCH (x)
        |CALL {
        |  CREATE (y:Label)
        |  RETURN *
        |}
        |RETURN count(*) AS count
        |""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .apply(fromSubquery = true)
        .|.create(createNode("y", "Label"))
        .|.argument()
        .allNodeScan("x")
        .build()
    )
  }

  test("should plan nested uncorrelated subqueries with updates with Apply") {
    val query =
      """MATCH (x)
        |CALL {
        |  CALL {
        |    CREATE (y:Label)
        |    RETURN *
        |  }
        |  RETURN *
        |}
        |RETURN count(*) AS count
        |""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .apply(fromSubquery = true)
        .|.create(createNode("y", "Label"))
        .|.argument()
        .allNodeScan("x")
        .build()
    )
  }

  test("should plan nested uncorrelated subqueries with updates in outer subquery with Apply / CartesianProduct") {
    val query =
      """MATCH (x)
        |CALL {
        |  CREATE (y:Label)
        |  WITH y
        |  CALL {
        |    RETURN 5 as literal
        |  }
        |  RETURN *
        |}
        |RETURN count(*) AS count
        |""".stripMargin

    planFor(query, stripProduceResults = false)._2 should equal(
      new LogicalPlanBuilder()
        .produceResults("count")
        .aggregation(Seq.empty, Seq("count(*) AS count"))
        .apply(fromSubquery = true)
        .|.cartesianProduct(fromSubquery = true)
        .|.|.projection("5 AS literal")
        .|.|.argument()
        .|.create(createNode("y", "Label"))
        .|.argument()
        .allNodeScan("x")
        .build()
    )
  }
}
