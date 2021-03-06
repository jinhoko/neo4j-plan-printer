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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.neo4j.cypher.internal.runtime.ImplicitValueConversion.toLongValue
import org.neo4j.cypher.internal.runtime.ImplicitValueConversion.toRelationshipValue
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.RelationshipOperations
import org.neo4j.cypher.internal.runtime.interpreted.QueryStateHelper
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.exceptions.CypherTypeException
import org.neo4j.graphdb.RelationshipType
import org.neo4j.values.storable.Values.stringValue

class RelationshipTypeFunctionTest extends CypherFunSuite with FakeEntityTestSupport {

  private val mockedContext = mock[QueryContext]
  private val operations = mock[RelationshipOperations]
  result(operations).when(mockedContext).relationshipOps

  private val state = QueryStateHelper.emptyWith(query = mockedContext)
  private val function = RelationshipTypeFunction(Variable("r"))

  test("should give the type of a relationship") {
    result(false).when(operations).isDeletedInThisTx(any())

    val rel = new FakeRel(null, null, RelationshipType.withName("T"))
    function.compute(rel, null, state) should equal(stringValue("T"))
  }

  test("should handle deleted relationships since types are inlined") {
    result(true).when(operations).isDeletedInThisTx(any())

    val rel = new FakeRel(null, null, RelationshipType.withName("T"))
    function.compute(rel, null, state) should equal(stringValue("T"))
  }

  test("should throw if encountering anything other than a relationship") {
    result(false).when(operations).isDeletedInThisTx(any())

    the[CypherTypeException] thrownBy {
      function.compute(1337L, null, state)
    } should have message "Invalid input for function 'type()': Expected a Relationship, got: Long(1337)"

  }

  private def result(value: Any) = doReturn(value, Nil: _*)
}
