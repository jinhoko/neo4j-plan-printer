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
package org.neo4j.cypher.internal.ast.factory.neo4j

import org.junit.runner.RunWith
import org.neo4j.cypher.internal.ast.Statement
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.OpenCypherExceptionFactory
import org.neo4j.cypher.internal.util.OpenCypherExceptionFactory.SyntaxException
import org.scalatest.Assertion
import org.scalatest.Assertions
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import scala.util.Failure
import scala.util.Success
import scala.util.Try

@RunWith(classOf[JUnitRunner])
abstract class ParserComparisonTestBase() extends Assertions with Matchers {
  private val exceptionFactory = new OpenCypherExceptionFactory(None)

  private def fixLineSeparator(message: String): String = {
    // This is needed because current version of scala seems to produce \n from multi line strings
    // This produces a problem with windows line endings \r\n
    if(message.contains(System.lineSeparator()))
      message
    else
      message.replaceAll("\n", System.lineSeparator())
  }

  /**
   * Tests that JavaCC parser produces SyntaxException.
   */
  protected def assertJavaCCException(query: String, expectedMessage: String): Assertion = {
    val exception = the[OpenCypherExceptionFactory.SyntaxException] thrownBy {
      JavaCCParser.parse(query, exceptionFactory, new AnonymousVariableNameGenerator())
    }
    exception.getMessage shouldBe fixLineSeparator(expectedMessage)
  }

  /**
   * Tests that JavaCC parser produces SyntaxException.
   */
  protected def assertJavaCCExceptionStart(query: String, expectedMessage: String): Assertion = {
    val exception = the[OpenCypherExceptionFactory.SyntaxException] thrownBy {
      JavaCCParser.parse(query, exceptionFactory, new AnonymousVariableNameGenerator())
    }
    exception.getMessage should startWith(fixLineSeparator(expectedMessage))
  }

  /**
   * Tests that the JavaCC parser produce correct AST.
   */
  protected def assertJavaCCAST(query: String, expected: Statement): Assertion = {
    val ast = JavaCCParser.parse(query, exceptionFactory, new AnonymousVariableNameGenerator())
    ast shouldBe expected
  }

  /**
   * Tests that the parboiled and JavaCC parsers produce the same AST and error positions.
   */
  protected def assertSameAST(query: String): Assertion = assertSameAST(query, query)

  protected def assertSameAST(query: String, parBoiledQuery: String): Assertion = {
    withClue(query+System.lineSeparator()) {
      val parboiledParser = new org.neo4j.cypher.internal.parser.CypherParser()
      val parboiledAST = Try(parboiledParser.parse(parBoiledQuery, exceptionFactory, None))

      val javaccAST = Try(JavaCCParser.parse(query, exceptionFactory, new AnonymousVariableNameGenerator()))

      (javaccAST, parboiledAST) match {
        case (Failure(javaccEx: SyntaxException), Failure(parboiledEx: SyntaxException)) =>
          withClue(Seq(javaccEx, parboiledEx).mkString("", "\n\n", "\n\n")) {
            javaccEx.pos shouldBe parboiledEx.pos
          }
        case (Failure(javaccEx), Success(_)) =>
          throw javaccEx
        case _ =>
          javaccAST shouldBe parboiledAST
      }
    }
  }
}
