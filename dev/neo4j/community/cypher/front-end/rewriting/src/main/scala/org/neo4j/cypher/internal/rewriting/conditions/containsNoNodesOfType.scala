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
package org.neo4j.cypher.internal.rewriting.conditions

import org.neo4j.cypher.internal.rewriting.ValidatingCondition
import org.neo4j.cypher.internal.util.ASTNode

import scala.reflect.ClassTag

case class containsNoNodesOfType[T <: ASTNode]()(implicit val tag: ClassTag[T]) extends ValidatingCondition {
  def apply(that: Any): Seq[String] = collectNodesOfType[T].apply(that).map {
    node => s"Expected none but found ${node.getClass.getSimpleName} at position ${node.position}"
  }

  override def name = s"$productPrefix[${tag.runtimeClass.getSimpleName}]"

  override def hashCode(): Int = tag.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case cc:containsNoNodesOfType[_] => tag.equals(cc.tag)
    case  _ => false
  }
}
