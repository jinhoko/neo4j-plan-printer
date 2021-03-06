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
package org.neo4j.cypher.internal.runtime.interpreted.commands

import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.ImplicitValueConversion.toListValue
import org.neo4j.cypher.internal.runtime.ImplicitValueConversion.toStringValue
import org.neo4j.cypher.internal.runtime.PathImpl
import org.neo4j.cypher.internal.runtime.interpreted.QueryStateHelper
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Length3_5Function
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Variable
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.exceptions.CypherTypeException
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.Relationship
import org.neo4j.kernel.impl.util.ValueUtils
import org.neo4j.values.storable.Values.intValue

class Length3_5FunctionTest extends CypherFunSuite {

  test("length3_5 can be used on paths") {
    //given
    val p = PathImpl(mock[Node], mock[Relationship], mock[Node])
    val m = CypherRow.from("p" -> ValueUtils.fromPath(p))
    val lengthFunction = Length3_5Function(Variable("p"))

    //when
    val result = lengthFunction(m, QueryStateHelper.empty)

    //then
    result should equal(intValue(p.length()))
  }
  test("length3_5 can be used on collections") {
    //given
    val l = Seq("it", "was", "the")
    val m = CypherRow.from("l" -> l)
    val lengthFunction = Length3_5Function(Variable("l"))

    //when
    val result = lengthFunction(m, QueryStateHelper.empty)

    //then
    result should equal(intValue(l.length))  }

  test("length3_5 can be used on strings") {
    //given
    val s = "it was the"
    val m = CypherRow.from("s" -> s)
    val lengthFunction = Length3_5Function(Variable("s"))

    //when
    val result = lengthFunction(m, QueryStateHelper.empty)

    //then
    result should equal(intValue(s.length))
  }

  test("length3_5 cannot be used on numbers") {
    //given
    val n = 123
    val m = CypherRow.from("n" -> intValue(n))
    val lengthFunction = Length3_5Function(Variable("n"))

    //when/then
    val e = intercept[CypherTypeException](lengthFunction.apply(m, QueryStateHelper.empty))
    e.getMessage should be("Invalid input for function 'length3_5()': Expected a Path, String or List, got: Int(123)")
  }
}
