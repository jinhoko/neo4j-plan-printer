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
package org.neo4j.cypher.internal.runtime.interpreted

import java.net.URL

import org.neo4j.common.EntityType
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.expressions.SemanticDirection.BOTH
import org.neo4j.cypher.internal.expressions.SemanticDirection.INCOMING
import org.neo4j.cypher.internal.expressions.SemanticDirection.OUTGOING
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.cypher.internal.logical.plans.IndexOrder
import org.neo4j.cypher.internal.runtime
import org.neo4j.cypher.internal.runtime.ClosingIterator
import org.neo4j.cypher.internal.runtime.ClosingLongIterator
import org.neo4j.cypher.internal.runtime.ConstraintInfo
import org.neo4j.cypher.internal.runtime.Expander
import org.neo4j.cypher.internal.runtime.IndexInfo
import org.neo4j.cypher.internal.runtime.IndexStatus
import org.neo4j.cypher.internal.runtime.IsNoValue
import org.neo4j.cypher.internal.runtime.KernelAPISupport.RANGE_SEEKABLE_VALUE_GROUPS
import org.neo4j.cypher.internal.runtime.KernelAPISupport.asKernelIndexOrder
import org.neo4j.cypher.internal.runtime.KernelPredicate
import org.neo4j.cypher.internal.runtime.NodeValueHit
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.RelationshipIterator
import org.neo4j.cypher.internal.runtime.ResourceManager
import org.neo4j.cypher.internal.runtime.UserDefinedAggregator
import org.neo4j.cypher.internal.runtime.ValuedNodeIndexCursor
import org.neo4j.cypher.internal.runtime.ValuedRelationshipIndexCursor
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.CursorIterator
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.RelationshipCursorIterator
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.RelationshipTypeCursorIterator
import org.neo4j.cypher.internal.runtime.interpreted.commands.convert.DirectionConverter.toGraphDb
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.OnlyDirectionExpander
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.TypeAndDirectionExpander
import org.neo4j.cypher.operations.CursorUtils
import org.neo4j.exceptions.EntityNotFoundException
import org.neo4j.exceptions.FailedIndexException
import org.neo4j.graphalgo.BasicEvaluationContext
import org.neo4j.graphalgo.impl.path.ShortestPath
import org.neo4j.graphalgo.impl.path.ShortestPath.ShortestPathPredicate
import org.neo4j.graphdb.Entity
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.NotFoundException
import org.neo4j.graphdb.Path
import org.neo4j.graphdb.PathExpanderBuilder
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.security.URLAccessValidationError
import org.neo4j.internal.helpers.collection.Iterators
import org.neo4j.internal.kernel.api
import org.neo4j.internal.kernel.api.IndexQueryConstraints
import org.neo4j.internal.kernel.api.IndexQueryConstraints.ordered
import org.neo4j.internal.kernel.api.IndexReadSession
import org.neo4j.internal.kernel.api.InternalIndexState
import org.neo4j.internal.kernel.api.NodeCursor
import org.neo4j.internal.kernel.api.NodeValueIndexCursor
import org.neo4j.internal.kernel.api.PropertyCursor
import org.neo4j.internal.kernel.api.PropertyIndexQuery
import org.neo4j.internal.kernel.api.PropertyIndexQuery.ExactPredicate
import org.neo4j.internal.kernel.api.Read
import org.neo4j.internal.kernel.api.RelationshipScanCursor
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor
import org.neo4j.internal.kernel.api.RelationshipTypeIndexCursor
import org.neo4j.internal.kernel.api.RelationshipValueIndexCursor
import org.neo4j.internal.kernel.api.SchemaReadCore
import org.neo4j.internal.kernel.api.TokenPredicate
import org.neo4j.internal.kernel.api.TokenRead
import org.neo4j.internal.kernel.api.TokenReadSession
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotFoundKernelException
import org.neo4j.internal.kernel.api.helpers.Nodes
import org.neo4j.internal.kernel.api.helpers.RelationshipSelections.allCursor
import org.neo4j.internal.kernel.api.helpers.RelationshipSelections.incomingCursor
import org.neo4j.internal.kernel.api.helpers.RelationshipSelections.outgoingCursor
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext
import org.neo4j.internal.schema.ConstraintDescriptor
import org.neo4j.internal.schema.ConstraintType
import org.neo4j.internal.schema.IndexConfig
import org.neo4j.internal.schema.IndexDescriptor
import org.neo4j.internal.schema.IndexPrototype
import org.neo4j.internal.schema.IndexProviderDescriptor
import org.neo4j.internal.schema.IndexType
import org.neo4j.internal.schema.SchemaDescriptor
import org.neo4j.kernel.GraphDatabaseQueryService
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.api.StatementConstants
import org.neo4j.kernel.api.exceptions.schema.EquivalentSchemaRuleAlreadyExistsException
import org.neo4j.kernel.impl.core.TransactionalEntityFactory
import org.neo4j.kernel.impl.util.DefaultValueMapper
import org.neo4j.kernel.impl.util.ValueUtils
import org.neo4j.kernel.impl.util.ValueUtils.fromNodeEntity
import org.neo4j.kernel.impl.util.ValueUtils.fromRelationshipEntity
import org.neo4j.memory.MemoryTracker
import org.neo4j.storageengine.api.RelationshipVisitor
import org.neo4j.values.AnyValue
import org.neo4j.values.ValueMapper
import org.neo4j.values.storable.FloatingPointValue
import org.neo4j.values.storable.TextValue
import org.neo4j.values.storable.Value
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.ListValue
import org.neo4j.values.virtual.MapValue
import org.neo4j.values.virtual.MapValueBuilder
import org.neo4j.values.virtual.NodeValue
import org.neo4j.values.virtual.RelationshipValue
import org.neo4j.values.virtual.VirtualValues

import scala.collection.Iterator
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable.ArrayBuffer

sealed class TransactionBoundQueryContext(val transactionalContext: TransactionalContextWrapper,
                                          val resources: ResourceManager)
                                         (implicit indexSearchMonitor: IndexSearchMonitor)
  extends TransactionBoundTokenContext(transactionalContext.kernelTransaction) with QueryContext {

  override val nodeOps: NodeOperations = new NodeOperations
  override val relationshipOps: RelationshipOperations = new RelationshipOperations
  override lazy val entityAccessor: TransactionalEntityFactory = transactionalContext.tc.transaction()
  private lazy val valueMapper: ValueMapper[java.lang.Object] = new DefaultValueMapper(
    transactionalContext.tc.transaction())

  override def setLabelsOnNode(node: Long, labelIds: Iterator[Int]): Int = labelIds.foldLeft(0) {
    case (count, labelId) => if (writes().nodeAddLabel(node, labelId)) count + 1 else count
  }

  //We cannot assign to value because of periodic commit
  protected def reads(): Read = transactionalContext.dataRead

  private def writes() = transactionalContext.dataWrite

  private def allocateNodeCursor() = transactionalContext.cursors.allocateNodeCursor( transactionalContext.kernelTransaction.cursorContext() )

  private def allocateRelationshipScanCursor() = transactionalContext.cursors.allocateRelationshipScanCursor( transactionalContext.kernelTransaction.cursorContext() )

  private def tokenRead = transactionalContext.kernelTransaction.tokenRead()

  private def tokenWrite = transactionalContext.kernelTransaction.tokenWrite()

  override def createNode(labels: Array[Int]): NodeValue = ValueUtils
    .fromNodeEntity(entityAccessor.newNodeEntity(writes().nodeCreateWithLabels(labels)))

  override def createNodeId(labels: Array[Int]): Long = writes().nodeCreateWithLabels(labels)

  override def createRelationship(start: Long, end: Long, relType: Int): RelationshipValue = {
    val relId = transactionalContext.kernelTransaction.dataWrite().relationshipCreate(start, relType, end)
    fromRelationshipEntity(entityAccessor.newRelationshipEntity(relId, start, relType, end))
  }

  override def singleRelationship(id: Long, cursor: RelationshipScanCursor): Unit = {
    reads().singleRelationship(id, cursor)
  }

  override def getOrCreateRelTypeId(relTypeName: String): Int =
    transactionalContext.kernelTransaction.tokenWrite().relationshipTypeGetOrCreateForName(relTypeName)

  override def getLabelsForNode(node: Long, nodeCursor: NodeCursor): ListValue = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) {
      if (nodeOps.isDeletedInThisTx(node))
        throw new EntityNotFoundException(s"Node with id $node has been deleted in this transaction")
      else
        VirtualValues.EMPTY_LIST
    }
    val labelSet = nodeCursor.labels()
    val labelArray = new Array[TextValue](labelSet.numberOfTokens())
    var i = 0
    while (i < labelSet.numberOfTokens()) {
      labelArray(i) = Values.stringValue(tokenRead.nodeLabelName(labelSet.token(i)))
      i += 1
    }
    VirtualValues.list(labelArray: _*)
  }

  override def getTypeForRelationship(id: Long, cursor: RelationshipScanCursor): TextValue = {
    reads().singleRelationship(id, cursor)
    if (!cursor.next() && !relationshipOps.isDeletedInThisTx(id)) {
      // we are allowed to read the type of relationships we have deleted, but
      // if we have a concurrent delete by another tx we resort to NO_VALUE
      Values.NO_VALUE
    }
    Values.stringValue(tokenRead.relationshipTypeName(cursor.`type`()))
  }

  override def isLabelSetOnNode(label: Int, node: Long, nodeCursor: NodeCursor): Boolean = {
    CursorUtils.nodeHasLabel(reads(), nodeCursor, node, label)
  }

  override def isTypeSetOnRelationship(typ: Int, id: Long, relationshipCursor: RelationshipScanCursor): Boolean = {
    CursorUtils.relationshipHasType(reads(), relationshipCursor, id, typ)
  }

  override def getOrCreateLabelId(labelName: String): Int = {
    val id = tokenRead.nodeLabel(labelName)
    if (id != TokenRead.NO_TOKEN) id
    else tokenWrite.labelGetOrCreateForName(labelName)
  }

  override def getOrCreateTypeId(relTypeName: String): Int = {
    val id = tokenRead.relationshipType(relTypeName)
    if (id != TokenRead.NO_TOKEN) id
    else tokenWrite.relationshipTypeGetOrCreateForName(relTypeName)
  }

  def getRelationshipsForIds(node: Long, dir: SemanticDirection, types: Array[Int]): ClosingIterator[RelationshipValue] = {

    val cursor = allocateNodeCursor()
    val cursors = transactionalContext.cursors
    val cursorContext = transactionalContext.kernelTransaction.cursorContext()

    try {
      val read = reads()
      read.singleNode(node, cursor)
      if (!cursor.next()) ClosingIterator.empty
      else {
        val selectionCursor = dir match {
          case OUTGOING => outgoingCursor(cursors, cursor, types, cursorContext)
          case INCOMING => incomingCursor(cursors, cursor, types, cursorContext)
          case BOTH => allCursor(cursors, cursor, types, cursorContext)
        }
        resources.trace(selectionCursor)
        new CursorIterator[RelationshipValue] {
          override protected def closeMore(): Unit = {
            selectionCursor.close()
          }

          override protected def fetchNext(): RelationshipValue =
            if (selectionCursor.next())
              fromRelationshipEntity(entityAccessor.newRelationshipEntity(selectionCursor.relationshipReference(),
                selectionCursor.sourceNodeReference(),
                selectionCursor.`type`(),
                selectionCursor.targetNodeReference()))
            else null
        }
      }
    } finally {
      cursor.close()
    }
  }

  override def getRelationshipsForIdsPrimitive(node: Long, dir: SemanticDirection,
                                               types: Array[Int]): ClosingLongIterator with RelationshipIterator = {
    val cursor = allocateNodeCursor()
    try {
      val read = reads()
      val cursors = transactionalContext.cursors
      val cursorContext = transactionalContext.kernelTransaction.cursorContext()
      read.singleNode(node, cursor)
      if (!cursor.next()) ClosingLongIterator.emptyClosingRelationshipIterator
      else {
        val selectionCursor = dir match {
          case OUTGOING => outgoingCursor(cursors, cursor, types, cursorContext)
          case INCOMING => incomingCursor(cursors, cursor, types, cursorContext)
          case BOTH => allCursor(cursors, cursor, types, cursorContext)
        }
        resources.trace(selectionCursor)
        new RelationshipCursorIterator(selectionCursor)
      }
    } finally {
      cursor.close()
    }
  }

  override def getRelationshipsByType(session: TokenReadSession, relType: Int, indexOrder: IndexOrder): ClosingLongIterator with RelationshipIterator = {
    val read = reads()
    val typeCursor = transactionalContext.cursors.allocateRelationshipTypeIndexCursor(transactionalContext.kernelTransaction.cursorContext)
    val relCursor = transactionalContext.cursors.allocateRelationshipScanCursor(transactionalContext.kernelTransaction.cursorContext)
    read.relationshipTypeScan(session, typeCursor, ordered(asKernelIndexOrder(indexOrder)), new TokenPredicate(relType))
    resources.trace(typeCursor)
    resources.trace(relCursor)
    new RelationshipTypeCursorIterator(read, typeCursor, relCursor)
  }

  override def nodeCursor(): NodeCursor =
    transactionalContext.cursors.allocateNodeCursor(transactionalContext.kernelTransaction.cursorContext)

  override def traversalCursor(): RelationshipTraversalCursor =
    transactionalContext.cursors.allocateRelationshipTraversalCursor(transactionalContext.kernelTransaction.cursorContext)

  override def relationshipById(relationshipId: Long,
                                startNodeId: Long,
                                endNodeId: Long,
                                typeId: Int): RelationshipValue =
    try {
      fromRelationshipEntity(entityAccessor.newRelationshipEntity(relationshipId, startNodeId, typeId, endNodeId))
    } catch {
      case e: NotFoundException => throw new EntityNotFoundException(s"Relationship with id $relationshipId", e)
    }

  override def nodeIndexSeek(index: IndexReadSession,
                             needsValues: Boolean,
                             indexOrder: IndexOrder,
                             predicates: Seq[PropertyIndexQuery]): NodeValueIndexCursor = {
    val impossiblePredicate =
      predicates.exists {
        case p: PropertyIndexQuery.ExactPredicate => (p.value() eq Values.NO_VALUE) || (p.value().isInstanceOf[FloatingPointValue] && p.value().asInstanceOf[FloatingPointValue].isNaN)
        case _: PropertyIndexQuery.ExistsPredicate => predicates.length <= 1
        case p: PropertyIndexQuery.RangePredicate[_] =>
          !RANGE_SEEKABLE_VALUE_GROUPS.contains(p.valueGroup())
        case _ => false
      }

    if (impossiblePredicate) {
      NodeValueIndexCursor.EMPTY
    } else {
      innerNodeIndexSeek(index, needsValues, indexOrder, predicates: _*)
    }
  }

  override def relationshipIndexSeek(index: IndexReadSession,
                                     needsValues: Boolean,
                                     indexOrder: IndexOrder,
                                     predicates: Seq[PropertyIndexQuery]): RelationshipValueIndexCursor = {

    val impossiblePredicate =
      predicates.exists {
        case p: PropertyIndexQuery.ExactPredicate => (p.value() eq Values.NO_VALUE) || (p.value().isInstanceOf[FloatingPointValue] && p.value().asInstanceOf[FloatingPointValue].isNaN)
        case _: PropertyIndexQuery.ExistsPredicate => predicates.length <= 1
        case p: PropertyIndexQuery.RangePredicate[_] =>
          !RANGE_SEEKABLE_VALUE_GROUPS.contains(p.valueGroup())
        case _ => false
      }

    if (impossiblePredicate) {
      RelationshipValueIndexCursor.EMPTY
    } else {
      innerRelationshipIndexSeek(index, needsValues, indexOrder, predicates: _*)
    }
  }

  override def relationshipIndexSeekByContains(index: IndexReadSession,
                                               needsValues: Boolean,
                                               indexOrder: IndexOrder,
                                               value: TextValue): RelationshipValueIndexCursor =
    innerRelationshipIndexSeek(index, needsValues, indexOrder,
      PropertyIndexQuery.stringContains(index.reference().schema().getPropertyIds()(0), value))

  override def relationshipIndexSeekByEndsWith(index: IndexReadSession,
                                               needsValues: Boolean,
                                               indexOrder: IndexOrder,
                                               value: TextValue): RelationshipValueIndexCursor =
    innerRelationshipIndexSeek(index, needsValues, indexOrder,
      PropertyIndexQuery.stringSuffix(index.reference().schema().getPropertyIds()(0), value))

  override def relationshipIndexScan(index: IndexReadSession,
                                     needsValues: Boolean,
                                     indexOrder: IndexOrder): RelationshipValueIndexCursor = {
    val relCursor = allocateAndTraceRelationshipValueIndexCursor()
    reads().relationshipIndexScan(index, relCursor, IndexQueryConstraints.constrained(asKernelIndexOrder(indexOrder), needsValues))
    relCursor
  }

  override def btreeIndexReference(entityId: Int, entityType: EntityType, properties: Int*): IndexDescriptor = {
    val descriptor = entityType match {
      case EntityType.NODE         => SchemaDescriptor.forLabel(entityId, properties: _*)
      case EntityType.RELATIONSHIP => SchemaDescriptor.forRelType(entityId, properties: _*)
    }
    Iterators.single(transactionalContext.kernelTransaction.schemaRead().index(descriptor))
  }

  override def lookupIndexReference(entityType: EntityType): IndexDescriptor = {
    val descriptor = SchemaDescriptor.forAnyEntityTokens(entityType)
    Iterators.single(transactionalContext.kernelTransaction.schemaRead().index(descriptor))
  }

  override def fulltextIndexReference(entityIds: List[Int], entityType: EntityType, properties: Int*): IndexDescriptor = {
    val descriptor = SchemaDescriptor.fulltext(entityType, entityIds.toArray, properties.toArray)
    Iterators.single(transactionalContext.kernelTransaction.schemaRead().index(descriptor))
  }

  private def innerNodeIndexSeek(index: IndexReadSession,
                                 needsValues: Boolean,
                                 indexOrder: IndexOrder,
                                 queries: PropertyIndexQuery*): NodeValueIndexCursor = {

    val nodeCursor: NodeValueIndexCursor = allocateAndTraceNodeValueIndexCursor()
    val actualValues =
      if (needsValues && queries.forall(_.isInstanceOf[ExactPredicate]))
      // We don't need property values from the index for an exact seek
      {
        queries.map(_.asInstanceOf[ExactPredicate].value()).toArray
      } else {
        null
      }
    val needsValuesFromIndexSeek = actualValues == null && needsValues
    reads().nodeIndexSeek(index, nodeCursor, IndexQueryConstraints.constrained(asKernelIndexOrder(indexOrder), needsValuesFromIndexSeek), queries: _*)
    if (needsValues && actualValues != null) {
      new ValuedNodeIndexCursor(nodeCursor, actualValues)
    } else {
      nodeCursor
    }
  }

  private def innerRelationshipIndexSeek(index: IndexReadSession,
                                         needsValues: Boolean,
                                         indexOrder: IndexOrder,
                                         queries: PropertyIndexQuery*): RelationshipValueIndexCursor = {

    val relCursor = allocateAndTraceRelationshipValueIndexCursor()
    val actualValues =
      if (needsValues && queries.forall(_.isInstanceOf[ExactPredicate]))
      // We don't need property values from the index for an exact seek
      {
        queries.map(_.asInstanceOf[ExactPredicate].value()).toArray
      } else {
        null
      }
    val needsValuesFromIndexSeek = actualValues == null && needsValues
    reads().relationshipIndexSeek(index, relCursor, IndexQueryConstraints.constrained(asKernelIndexOrder(indexOrder), needsValuesFromIndexSeek), queries: _*)
    if (needsValues && actualValues != null) {
      new ValuedRelationshipIndexCursor(relCursor, actualValues)
    } else {
      relCursor
    }
  }

  override def nodeIndexScan(index: IndexReadSession,
                             needsValues: Boolean,
                             indexOrder: IndexOrder): NodeValueIndexCursor = {
    val nodeCursor = allocateAndTraceNodeValueIndexCursor()
    reads().nodeIndexScan(index, nodeCursor, IndexQueryConstraints.constrained(asKernelIndexOrder(indexOrder), needsValues))
    nodeCursor
  }


  override def nodeIndexSeekByContains(index: IndexReadSession,
                                       needsValues: Boolean,
                                       indexOrder: IndexOrder,
                                       value: TextValue): NodeValueIndexCursor =
    innerNodeIndexSeek(index, needsValues, indexOrder,
      PropertyIndexQuery.stringContains(index.reference().schema().getPropertyIds()(0), value))

  override def nodeIndexSeekByEndsWith(index: IndexReadSession,
                                       needsValues: Boolean,
                                       indexOrder: IndexOrder,
                                       value: TextValue): NodeValueIndexCursor =
    innerNodeIndexSeek(index, needsValues, indexOrder, PropertyIndexQuery.stringSuffix(index.reference().schema().getPropertyIds()(0), value))

  override def nodeLockingUniqueIndexSeek(index: IndexDescriptor,
                                          queries: Seq[PropertyIndexQuery.ExactPredicate]): NodeValueIndexCursor = {

    val cursor = transactionalContext.cursors.allocateNodeValueIndexCursor(transactionalContext.kernelTransaction.cursorContext, transactionalContext.tc.kernelTransaction().memoryTracker())
    try {
      indexSearchMonitor.lockingUniqueIndexSeek(index, queries)
      if (queries.exists(q => q.value() eq Values.NO_VALUE)) {
        NodeValueHit.EMPTY
      } else {
        val resultNodeId = reads().lockingNodeUniqueIndexSeek(index, cursor, queries: _*)
        if (StatementConstants.NO_SUCH_NODE == resultNodeId) {
          NodeValueHit.EMPTY
        } else {
          val values = queries.map(_.value()).toArray
          new NodeValueHit(resultNodeId, values, reads())
        }
      }
    } finally {
      cursor.close()
    }
  }

  override def removeLabelsFromNode(node: Long, labelIds: Iterator[Int]): Int = labelIds.foldLeft(0) {
    case (count, labelId) =>
      if (transactionalContext.kernelTransaction.dataWrite().nodeRemoveLabel(node, labelId)) count + 1 else count
  }

  override def getNodesByLabel(tokenReadSession: TokenReadSession, id: Int, indexOrder: IndexOrder): ClosingIterator[NodeValue] = {
    val cursor = allocateAndTraceNodeLabelIndexCursor()
    reads().nodeLabelScan(tokenReadSession, cursor, ordered(asKernelIndexOrder(indexOrder)), new TokenPredicate(id))
    new CursorIterator[NodeValue] {
      override protected def fetchNext(): NodeValue = {
        if (cursor.next()) fromNodeEntity(entityAccessor.newNodeEntity(cursor.nodeReference()))
        else null
      }

      override protected def closeMore(): Unit = {
        cursor.close()
      }
    }
  }

  override def nodeAsMap(id: Long, nodeCursor: NodeCursor, propertyCursor: PropertyCursor): MapValue = {
    reads().singleNode(id, nodeCursor)
    if (!nodeCursor.next()) VirtualValues.EMPTY_MAP
    else {
      val tokens = tokenRead
      nodeCursor.properties(propertyCursor)
      val builder = new MapValueBuilder()
      while (propertyCursor.next()) {
        builder.add(tokens.propertyKeyName(propertyCursor.propertyKey()), propertyCursor.propertyValue())
      }
      builder.build()
    }
  }

  override def relationshipAsMap(id: Long, relationshipCursor: RelationshipScanCursor,
                                 propertyCursor: PropertyCursor): MapValue = {
    reads().singleRelationship(id, relationshipCursor)
    if (!relationshipCursor.next()) VirtualValues.EMPTY_MAP
    else {
      val tokens = tokenRead
      relationshipCursor.properties(propertyCursor)
      val builder = new MapValueBuilder()
      while (propertyCursor.next()) {
        builder.add(tokens.propertyKeyName(propertyCursor.propertyKey()), propertyCursor.propertyValue())
      }
      builder.build()
    }
  }

  override def getNodesByLabelPrimitive(tokenReadSession: TokenReadSession, id: Int, indexOrder: IndexOrder): ClosingLongIterator = {
    val cursor = allocateAndTraceNodeLabelIndexCursor()
    reads().nodeLabelScan(tokenReadSession, cursor, ordered(asKernelIndexOrder(indexOrder)), new TokenPredicate(id))
    new PrimitiveCursorIterator {
      override protected def fetchNext(): Long = if (cursor.next()) cursor.nodeReference() else -1L

      override def close(): Unit = {
        cursor.close()
      }
    }
  }

  override def nodeGetOutgoingDegreeWithMax(maxDegree: Int, node: Long, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else {
      Nodes.countWithMax(maxDegree, nodeCursor, org.neo4j.graphdb.Direction.OUTGOING)
    }
  }

  override def nodeGetIncomingDegreeWithMax(maxDegree: Int, node: Long, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else {
      Nodes.countWithMax(maxDegree, nodeCursor, org.neo4j.graphdb.Direction.INCOMING)
    }
  }

  override def nodeGetTotalDegreeWithMax(maxDegree: Int, node: Long, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else {
      Nodes.countWithMax(maxDegree, nodeCursor, org.neo4j.graphdb.Direction.BOTH)
    }
  }

  override def nodeGetOutgoingDegree(node: Long, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else Nodes.countOutgoing(nodeCursor)
  }

  override def nodeGetIncomingDegree(node: Long, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else Nodes.countIncoming(nodeCursor)
  }

  override def nodeGetTotalDegree(node: Long, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else Nodes.countAll(nodeCursor)
  }

  override def nodeGetOutgoingDegreeWithMax(maxDegree: Int, node: Long, relationship: Int, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else {
      Nodes.countWithMax(maxDegree, nodeCursor, relationship, org.neo4j.graphdb.Direction.OUTGOING)
    }
  }

  override def nodeGetIncomingDegreeWithMax(maxDegree: Int, node: Long, relationship: Int, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else {
      Nodes.countWithMax(maxDegree, nodeCursor, relationship, org.neo4j.graphdb.Direction.INCOMING)
    }
  }

  override def nodeGetTotalDegreeWithMax(maxDegree: Int, node: Long, relationship: Int, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else {
      Nodes.countWithMax(maxDegree, nodeCursor, relationship, org.neo4j.graphdb.Direction.BOTH)
    }
  }

  override def nodeGetOutgoingDegree(node: Long, relationship: Int, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else Nodes.countOutgoing(nodeCursor, relationship)
  }

  override def nodeGetIncomingDegree(node: Long, relationship: Int, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else Nodes.countIncoming(nodeCursor, relationship)
  }

  override def nodeGetTotalDegree(node: Long, relationship: Int, nodeCursor: NodeCursor): Int = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) 0
    else Nodes.countAll(nodeCursor, relationship)
  }

  override def nodeHasCheapDegrees(node: Long, nodeCursor: NodeCursor): Boolean = {
    reads().singleNode(node, nodeCursor)
    if (!nodeCursor.next()) false
    else nodeCursor.supportsFastDegreeLookup
  }

  override def asObject(value: AnyValue): AnyRef = value.map(valueMapper)

  override def getTxStateNodePropertyOrNull(nodeId: Long,
                                            propertyKey: Int): Value = {
    val ops = reads()
    if (ops.nodeDeletedInTransaction(nodeId)) {
      throw new EntityNotFoundException(
        s"Node with id $nodeId has been deleted in this transaction")
    }

    ops.nodePropertyChangeInTransactionOrNull(nodeId, propertyKey)
  }

  override def getTxStateRelationshipPropertyOrNull(relId: Long,
                                                    propertyKey: Int): Value = {
    val ops = reads()
    if (ops.relationshipDeletedInTransaction(relId)) {
      throw new EntityNotFoundException(
        s"Relationship with id $relId has been deleted in this transaction")
    }

    ops.relationshipPropertyChangeInTransactionOrNull(relId, propertyKey)
  }

  class NodeOperations
    extends org.neo4j.cypher.internal.runtime.NodeOperations {

    override def delete(id: Long): Boolean = {
      writes().nodeDelete(id)
    }

    override def propertyKeyIds(id: Long, nodeCursor: NodeCursor, propertyCursor: PropertyCursor): Array[Int] = {
      reads().singleNode(id, nodeCursor)
      if (!nodeCursor.next()) Array.empty
      else {
        val buffer = ArrayBuffer[Int]()
        nodeCursor.properties(propertyCursor)
        while (propertyCursor.next()) {
          buffer.append(propertyCursor.propertyKey())
        }
        buffer.toArray
      }
    }

    override def getProperty(id: Long, propertyKeyId: Int, nodeCursor: NodeCursor, propertyCursor: PropertyCursor,
                             throwOnDeleted: Boolean): Value = {
      CursorUtils.nodeGetProperty(reads(), nodeCursor, id, propertyCursor, propertyKeyId, throwOnDeleted)
    }

    override def getTxStateProperty(nodeId: Long, propertyKeyId: Int): Value =
      getTxStateNodePropertyOrNull(nodeId, propertyKeyId)

    override def hasProperty(id: Long, propertyKey: Int, nodeCursor: NodeCursor,
                             propertyCursor: PropertyCursor): Boolean = {
      CursorUtils.nodeHasProperty(reads(), nodeCursor, id, propertyCursor, propertyKey)
    }

    override def hasTxStatePropertyForCachedProperty(nodeId: Long, propertyKeyId: Int): Option[Boolean] = {
      if (isDeletedInThisTx(nodeId)) {
        // Node deleted in TxState
        Some(false)
      } else {
        val nodePropertyInTx = reads().nodePropertyChangeInTransactionOrNull(nodeId, propertyKeyId)
        nodePropertyInTx match {
          case null => None // no changes in TxState.
          case IsNoValue() => Some(false) // property removed in TxState
          case _ => Some(true) // property changed in TxState
        }
      }
    }

    override def removeProperty(id: Long, propertyKeyId: Int): Boolean = {
      try {
        !(writes().nodeRemoveProperty(id, propertyKeyId) eq Values.NO_VALUE)
      } catch {
        case _: api.exceptions.EntityNotFoundException => false
      }
    }

    override def setProperty(id: Long, propertyKeyId: Int, value: Value): Unit = {
      try {
        writes().nodeSetProperty(id, propertyKeyId, value)
      } catch {
        case _: api.exceptions.EntityNotFoundException => //ignore
      }
    }

    override def getById(id: Long): NodeValue = try {
      fromNodeEntity(entityAccessor.newNodeEntity(id))
    } catch {
      case e: NotFoundException => throw new EntityNotFoundException(s"Node with id $id", e)
    }

    override def all: ClosingIterator[NodeValue] = {
      val nodeCursor = allocateAndTraceNodeCursor()
      reads().allNodesScan(nodeCursor)
      new CursorIterator[NodeValue] {
        override protected def fetchNext(): NodeValue = {
          if (nodeCursor.next()) fromNodeEntity(entityAccessor.newNodeEntity(nodeCursor.nodeReference()))
          else null
        }

        override protected def closeMore(): Unit = nodeCursor.close()
      }
    }

    override def allPrimitive: ClosingLongIterator = {
      val nodeCursor = allocateAndTraceNodeCursor()
      reads().allNodesScan(nodeCursor)
      new PrimitiveCursorIterator {
        override protected def fetchNext(): Long = if (nodeCursor.next()) nodeCursor.nodeReference() else -1L

        override def close(): Unit = nodeCursor.close()
      }
    }

    override def isDeletedInThisTx(id: Long): Boolean = reads().nodeDeletedInTransaction(id)

    override def acquireExclusiveLock(obj: Long): Unit =
      transactionalContext.kernelTransaction.locks().acquireExclusiveNodeLock(obj)

    override def releaseExclusiveLock(obj: Long): Unit =
      transactionalContext.kernelTransaction.locks().releaseExclusiveNodeLock(obj)

    override def getByIdIfExists(id: Long): Option[NodeValue] =
      if (id >= 0 && reads().nodeExists(id))
        Some(fromNodeEntity(entityAccessor.newNodeEntity(id)))
      else
        None
  }

  class RelationshipOperations extends org.neo4j.cypher.internal.runtime.RelationshipOperations {

    override def delete(id: Long): Boolean = {
      writes().relationshipDelete(id)
    }

    override def propertyKeyIds(id: Long, relationshipScanCursor: RelationshipScanCursor,
                                propertyCursor: PropertyCursor): Array[Int] = {
      reads().singleRelationship(id, relationshipScanCursor)
      if (!relationshipScanCursor.next()) Array.empty
      else {
        val buffer = ArrayBuffer[Int]()
        relationshipScanCursor.properties(propertyCursor)
        while (propertyCursor.next()) {
          buffer.append(propertyCursor.propertyKey())
        }
        buffer.toArray
      }
    }

    override def getProperty(id: Long, propertyKeyId: Int, relationshipCursor: RelationshipScanCursor,
                             propertyCursor: PropertyCursor, throwOnDeleted: Boolean): Value = {
      CursorUtils
        .relationshipGetProperty(reads(), relationshipCursor, id, propertyCursor, propertyKeyId, throwOnDeleted)
    }

    override def hasProperty(id: Long, propertyKey: Int, relationshipCursor: RelationshipScanCursor,
                             propertyCursor: PropertyCursor): Boolean = {
      CursorUtils.relationshipHasProperty(reads(), relationshipCursor, id, propertyCursor, propertyKey)
    }

    override def removeProperty(id: Long, propertyKeyId: Int): Boolean = {
      try {
        !(writes().relationshipRemoveProperty(id, propertyKeyId) eq Values.NO_VALUE)
      } catch {
        case _: api.exceptions.EntityNotFoundException => false
      }
    }

    override def setProperty(id: Long, propertyKeyId: Int, value: Value): Unit = {
      try {
        writes().relationshipSetProperty(id, propertyKeyId, value)
      } catch {
        case _: api.exceptions.EntityNotFoundException => //ignore
      }
    }

    override def getById(id: Long): RelationshipValue = try {
      fromRelationshipEntity(entityAccessor.newRelationshipEntity(id))
    } catch {
      case e: NotFoundException => throw new EntityNotFoundException(s"Relationship with id $id", e)
    }

    override def getByIdIfExists(id: Long): Option[RelationshipValue] = {
      if (id < 0)
        None
      else {
        val cursor = allocateRelationshipScanCursor()
        try {
          reads().singleRelationship(id, cursor)
          if (cursor.next()) {
            val src = cursor.sourceNodeReference()
            val dst = cursor.targetNodeReference()
            val relProxy = entityAccessor.newRelationshipEntity(id, src, cursor.`type`(), dst)
            Some(fromRelationshipEntity(relProxy))
          }
          else
            None
        } finally {
          cursor.close()
        }
      }
    }

    override def all: ClosingIterator[RelationshipValue] = {
      val relCursor = allocateAndTraceRelationshipScanCursor()
      reads().allRelationshipsScan(relCursor)
      new CursorIterator[RelationshipValue] {
        override protected def fetchNext(): RelationshipValue = {
          if (relCursor.next())
            fromRelationshipEntity(entityAccessor.newRelationshipEntity(relCursor.relationshipReference(),
              relCursor.sourceNodeReference(),
              relCursor.`type`(),
              relCursor.targetNodeReference()))
          else null
        }

        override protected def closeMore(): Unit = relCursor.close()
      }
    }

    override def allPrimitive: ClosingLongIterator = {
      val relCursor = allocateAndTraceRelationshipScanCursor()
      reads().allRelationshipsScan(relCursor)
      new PrimitiveCursorIterator {
        override protected def fetchNext(): Long = if (relCursor.next()) relCursor.relationshipReference() else -1L

        override def close(): Unit = relCursor.close()
      }
    }

    override def isDeletedInThisTx(id: Long): Boolean =
      reads().relationshipDeletedInTransaction(id)

    override def acquireExclusiveLock(obj: Long): Unit =
      transactionalContext.kernelTransaction.locks().acquireExclusiveRelationshipLock(obj)

    override def releaseExclusiveLock(obj: Long): Unit =
      transactionalContext.kernelTransaction.locks().releaseExclusiveRelationshipLock(obj)

    override def getTxStateProperty(relId: Long, propertyKeyId: Int): Value =
      getTxStateRelationshipPropertyOrNull(relId, propertyKeyId)

    override def hasTxStatePropertyForCachedProperty(relId: Long, propertyKeyId: Int): Option[Boolean] = {
      if (isDeletedInThisTx(relId)) {
        // Relationship deleted in TxState
        Some(false)
      } else {
        val relPropertyInTx = reads().relationshipPropertyChangeInTransactionOrNull(relId, propertyKeyId)
        relPropertyInTx match {
          case null => None // no changes in TxState.
          case IsNoValue() => Some(false) // property removed in TxState
          case _ => Some(true) // property changed in TxState
        }
      }
    }
  }

  override def getOrCreatePropertyKeyId(propertyKey: String): Int =
    tokenWrite.propertyKeyGetOrCreateForName(propertyKey)

  override def getOrCreatePropertyKeyIds(propertyKeys: Array[String]): Array[Int] = {
    val ids = new Array[Int](propertyKeys.length)
    tokenWrite.propertyKeyGetOrCreateForNames(propertyKeys, ids)
    ids
  }

  override def addBtreeIndexRule(entityId: Int, entityType: EntityType, propertyKeyIds: Seq[Int], name: Option[String], provider: Option[String], indexConfig: IndexConfig): IndexDescriptor = {
    val ktx = transactionalContext.kernelTransaction
    val descriptor = entityType match {
      case EntityType.NODE         => SchemaDescriptor.forLabel(entityId, propertyKeyIds: _*)
      case EntityType.RELATIONSHIP => SchemaDescriptor.forRelType(entityId, propertyKeyIds: _*)
    }
    try {
      if (provider.isEmpty)
        ktx.schemaWrite().indexCreate(descriptor, indexConfig, name.orNull)
      else
        ktx.schemaWrite().indexCreate(descriptor, provider.get, indexConfig, name.orNull)
    } catch {
      case e: EquivalentSchemaRuleAlreadyExistsException => handleEquivalentSchema(ktx, descriptor, e)
    }
  }

  override def addLookupIndexRule(entityType: EntityType, name: Option[String]): IndexDescriptor = {
    val ktx = transactionalContext.kernelTransaction
    val descriptor = SchemaDescriptor.forAnyEntityTokens(entityType)
    val prototype = IndexPrototype.forSchema(descriptor).withIndexType(IndexType.LOOKUP)
    val namedPrototype = name.map(n => prototype.withName(n)).getOrElse(prototype)
    try {
      ktx.schemaWrite().indexCreate(namedPrototype)
    } catch {
      case e: EquivalentSchemaRuleAlreadyExistsException => handleEquivalentSchema(ktx, descriptor, e)
    }
  }

  override def addFulltextIndexRule(entityIds: List[Int],
                                    entityType: EntityType,
                                    propertyKeyIds: Seq[Int],
                                    name: Option[String],
                                    provider: Option[IndexProviderDescriptor],
                                    indexConfig: IndexConfig): IndexDescriptor = {
    val ktx = transactionalContext.kernelTransaction
    val descriptor = SchemaDescriptor.fulltext(entityType, entityIds.toArray, propertyKeyIds.toArray)
    val prototype =
      provider.map(p => IndexPrototype.forSchema(descriptor, p)).getOrElse(IndexPrototype.forSchema(descriptor))
        .withIndexType(IndexType.FULLTEXT)
        .withIndexConfig(indexConfig)
    val namedPrototype = name.map(n => prototype.withName(n)).getOrElse(prototype)
    try {
      ktx.schemaWrite().indexCreate(namedPrototype)
    } catch {
      case e: EquivalentSchemaRuleAlreadyExistsException => handleEquivalentSchema(ktx, descriptor, e)
    }
  }

  private def handleEquivalentSchema(ktx: KernelTransaction, descriptor: SchemaDescriptor, e: EquivalentSchemaRuleAlreadyExistsException): Nothing = {
    val indexReference = ktx.schemaRead().index(descriptor).next()
    if (ktx.schemaRead().indexGetState(indexReference) == InternalIndexState.FAILED) {
      val message = ktx.schemaRead().indexGetFailure(indexReference)
      throw new FailedIndexException(indexReference.userDescription(ktx.tokenRead()), message)
    }
    throw e
  }

  override def dropIndexRule(labelId: Int, propertyKeyIds: Seq[Int]): Unit =
    transactionalContext.kernelTransaction.schemaWrite()
      .indexDrop(SchemaDescriptor.forLabel(labelId, propertyKeyIds: _*))

  override def dropIndexRule(name: String): Unit =
    transactionalContext.kernelTransaction.schemaWrite().indexDrop(name)

  override def getAllIndexes(): Map[IndexDescriptor, IndexInfo] = {
    val schemaRead: SchemaReadCore = transactionalContext.kernelTransaction.schemaRead().snapshot()
    val indexes = schemaRead.indexesGetAll().asScala.toList

    indexes.foldLeft(Map[IndexDescriptor, IndexInfo]()) {
      (map, index) =>
        val indexStatus = getIndexStatus( schemaRead, index )
        val schema = index.schema
        val labelsOrTypes = tokenRead.entityTokensGetNames( schema.entityType(), schema.getEntityTokenIds).toList
        val properties = schema.getPropertyIds.map( id => tokenRead.propertyKeyGetName(id)).toList
        map + (index -> runtime.IndexInfo(indexStatus, labelsOrTypes, properties))
    }
  }

  private def getIndexStatus(schemaRead: SchemaReadCore, index: IndexDescriptor): IndexStatus = {
    val (state: String, failureMessage: String, populationProgress: Double, maybeConstraint: Option[ConstraintDescriptor]) =
    try {
      val internalIndexState: InternalIndexState = schemaRead.indexGetState(index)
      val progress = schemaRead.indexGetPopulationProgress(index).toIndexPopulationProgress.getCompletedPercentage.toDouble
      val message: String = if (internalIndexState == InternalIndexState.FAILED) schemaRead.indexGetFailure(index) else ""
      val constraint: ConstraintDescriptor = schemaRead.constraintGetForName(index.getName)
      (internalIndexState.toString, message,  progress, Option(constraint))
    } catch {
      case _: IndexNotFoundKernelException =>
        val errorMessage = "Index not found. It might have been concurrently dropped."
        ("NOT FOUND", errorMessage, 0.0, None)
    }
    IndexStatus(state, failureMessage, populationProgress, maybeConstraint)
  }

  override def indexExists(name: String): Boolean =
    transactionalContext.kernelTransaction.schemaRead().indexGetForName(name) != IndexDescriptor.NO_INDEX

  override def constraintExists(name: String): Boolean =
    transactionalContext.kernelTransaction.schemaRead().constraintGetForName(name) != null

  override def constraintExists(matchFn: ConstraintDescriptor => Boolean, entityId: Int, properties: Int*): Boolean =
    transactionalContext.kernelTransaction.schemaRead().constraintsGetForSchema(SchemaDescriptor.forLabel(entityId, properties: _*)).asScala.exists(matchFn) ||
      transactionalContext.kernelTransaction.schemaRead().constraintsGetForSchema(SchemaDescriptor.forRelType(entityId, properties: _*)).asScala.exists(matchFn)

  override def createNodeKeyConstraint(labelId: Int,
                                       propertyKeyIds: Seq[Int],
                                       name: Option[String],
                                       provider: Option[String],
                                       indexConfig: IndexConfig): Unit = {
    val schemaWrite = transactionalContext.kernelTransaction.schemaWrite()
    val indexPrototype = if (provider.isEmpty) IndexPrototype.uniqueForSchema(SchemaDescriptor.forLabel(labelId, propertyKeyIds: _*))
                         else IndexPrototype.uniqueForSchema(SchemaDescriptor.forLabel(labelId, propertyKeyIds: _*), schemaWrite.indexProviderByName(provider.get))
    schemaWrite.nodeKeyConstraintCreate(indexPrototype.withName(name.orNull).withIndexConfig(indexConfig))
  }

  override def dropNodeKeyConstraint(labelId: Int, propertyKeyIds: Seq[Int]): Unit =
    transactionalContext.kernelTransaction.schemaWrite()
      .constraintDrop(SchemaDescriptor.forLabel(labelId, propertyKeyIds: _*), ConstraintType.UNIQUE_EXISTS)

  override def createUniqueConstraint(labelId: Int,
                                      propertyKeyIds: Seq[Int],
                                      name: Option[String],
                                      provider: Option[String],
                                      indexConfig: IndexConfig): Unit = {
    val schemaWrite = transactionalContext.kernelTransaction.schemaWrite()
    val indexPrototype = if (provider.isEmpty) IndexPrototype.uniqueForSchema(SchemaDescriptor.forLabel(labelId, propertyKeyIds: _*))
                         else IndexPrototype.uniqueForSchema(SchemaDescriptor.forLabel(labelId, propertyKeyIds: _*), schemaWrite.indexProviderByName(provider.get))
    schemaWrite.uniquePropertyConstraintCreate(indexPrototype.withName(name.orNull).withIndexConfig(indexConfig))
  }

  override def dropUniqueConstraint(labelId: Int, propertyKeyIds: Seq[Int]): Unit =
    transactionalContext.kernelTransaction.schemaWrite()
      .constraintDrop(SchemaDescriptor.forLabel(labelId, propertyKeyIds: _*), ConstraintType.UNIQUE)

  override def createNodePropertyExistenceConstraint(labelId: Int, propertyKeyId: Int, name: Option[String]): Unit =
    transactionalContext.kernelTransaction.schemaWrite().nodePropertyExistenceConstraintCreate(
      SchemaDescriptor.forLabel(labelId, propertyKeyId), name.orNull)

  override def dropNodePropertyExistenceConstraint(labelId: Int, propertyKeyId: Int): Unit =
    transactionalContext.kernelTransaction.schemaWrite()
      .constraintDrop(SchemaDescriptor.forLabel(labelId, propertyKeyId), ConstraintType.EXISTS)

  override def createRelationshipPropertyExistenceConstraint(relTypeId: Int, propertyKeyId: Int,
                                                             name: Option[String]): Unit =
    transactionalContext.kernelTransaction.schemaWrite().relationshipPropertyExistenceConstraintCreate(
      SchemaDescriptor.forRelType(relTypeId, propertyKeyId), name.orNull)

  override def dropRelationshipPropertyExistenceConstraint(relTypeId: Int, propertyKeyId: Int): Unit =
    transactionalContext.kernelTransaction.schemaWrite()
      .constraintDrop(SchemaDescriptor.forRelType(relTypeId, propertyKeyId), ConstraintType.EXISTS)

  override def dropNamedConstraint(name: String): Unit =
    transactionalContext.kernelTransaction.schemaWrite().constraintDrop(name)

  override def getAllConstraints(): Map[ConstraintDescriptor, ConstraintInfo] = {
    val schemaRead: SchemaReadCore = transactionalContext.kernelTransaction.schemaRead().snapshot()
    val constraints = schemaRead.constraintsGetAll().asScala.toList

    constraints.foldLeft(Map[ConstraintDescriptor, ConstraintInfo]()) {
      (map, constraint) =>
        val schema = constraint.schema
        val labelsOrTypes = tokenRead.entityTokensGetNames(schema.entityType(), schema.getEntityTokenIds).toList
        val properties = schema.getPropertyIds.map(id => tokenRead.propertyKeyGetName(id)).toList
        val maybeIndex =
          try {
            Some(schemaRead.indexGetForName(constraint.getName))
          } catch {
            case _: IndexNotFoundKernelException => None
          }
        map + (constraint -> runtime.ConstraintInfo(labelsOrTypes, properties, maybeIndex))
    }
  }

  override def getImportURL(url: URL): Either[String, URL] = transactionalContext.graph match {
    case db: GraphDatabaseQueryService =>
      try {
        Right(db.validateURLAccess(url))
      } catch {
        case error: URLAccessValidationError => Left(error.getMessage)
      }
  }

  private def getDatabaseService = {
    transactionalContext.graph
      .asInstanceOf[GraphDatabaseCypherService]
      .getGraphDatabaseService
  }

  override def nodeCountByCountStore(labelId: Int): Long = {
    reads().countsForNode(labelId)
  }

  override def relationshipCountByCountStore(startLabelId: Int, typeId: Int, endLabelId: Int): Long = {
    reads().countsForRelationship(startLabelId, typeId, endLabelId)
  }

  override def lockNodes(nodeIds: Long*): Unit =
    nodeIds.sorted.foreach(transactionalContext.kernelTransaction.locks().acquireExclusiveNodeLock(_))

  override def lockRelationships(relIds: Long*): Unit =
    relIds.sorted
      .foreach(transactionalContext.kernelTransaction.locks().acquireExclusiveRelationshipLock(_))

  override def singleShortestPath(left: Long, right: Long, depth: Int, expander: Expander,
                                  pathPredicate: KernelPredicate[Path],
                                  filters: Seq[KernelPredicate[Entity]],
                                  memoryTracker: MemoryTracker): Option[Path] = {
    val pathFinder = buildPathFinder(depth, expander, pathPredicate, filters, memoryTracker)

    //could probably do without node proxies here
    Option(pathFinder.findSinglePath(entityAccessor.newNodeEntity(left), entityAccessor.newNodeEntity(right)))
  }

  override def allShortestPath(left: Long, right: Long, depth: Int, expander: Expander,
                               pathPredicate: KernelPredicate[Path],
                               filters: Seq[KernelPredicate[Entity]], memoryTracker: MemoryTracker): ClosingIterator[Path] = {
    val pathFinder = buildPathFinder(depth, expander, pathPredicate, filters, memoryTracker)

    pathFinder.findAllPathsAutoCloseableIterator(entityAccessor.newNodeEntity(left), entityAccessor.newNodeEntity(right))
  }

  override def callReadOnlyProcedure(id: Int, args: Array[AnyValue], allowed: Array[String],
                                     context: ProcedureCallContext): Iterator[Array[AnyValue]] =
    CallSupport.callReadOnlyProcedure(transactionalContext.tc, id, args, allowed, context)

  override def callReadWriteProcedure(id: Int, args: Array[AnyValue], allowed: Array[String],
                                      context: ProcedureCallContext): Iterator[Array[AnyValue]] =
    CallSupport.callReadWriteProcedure(transactionalContext.tc, id, args, allowed, context)

  override def callSchemaWriteProcedure(id: Int, args: Array[AnyValue], allowed: Array[String],
                                        context: ProcedureCallContext): Iterator[Array[AnyValue]] =
    CallSupport.callSchemaWriteProcedure(transactionalContext.tc, id, args, allowed, context)

  override def callDbmsProcedure(id: Int, args: Array[AnyValue], allowed: Array[String],
                                 context: ProcedureCallContext): Iterator[Array[AnyValue]] =
    CallSupport.callDbmsProcedure(transactionalContext.tc, id, args, allowed, context)

  override def callFunction(id: Int, args: Array[AnyValue], allowed: Array[String]): AnyValue =
    CallSupport.callFunction(transactionalContext.tc, id, args, allowed)

  override def aggregateFunction(id: Int, allowed: Array[String]): UserDefinedAggregator =
    CallSupport.aggregateFunction(transactionalContext.tc, id, allowed)

  private def buildPathFinder(depth: Int, expander: Expander, pathPredicate: KernelPredicate[Path],
                              filters: Seq[KernelPredicate[Entity]], memoryTracker: MemoryTracker): ShortestPath = {
    val startExpander = expander match {
      case OnlyDirectionExpander(_, _, dir) =>
        PathExpanderBuilder.allTypes(toGraphDb(dir))
      case TypeAndDirectionExpander(_, _, typDirs) =>
        typDirs.foldLeft(PathExpanderBuilder.empty()) {
          case (acc, (typ, dir)) => acc.add(RelationshipType.withName(typ), toGraphDb(dir))
        }
    }

    val expanderWithNodeFilters = expander.nodeFilters.foldLeft(startExpander) {
      case (acc, filter) => acc.addNodeFilter((t: Entity) => filter.test(t))
    }
    val expanderWithAllPredicates = expander.relFilters.foldLeft(expanderWithNodeFilters) {
      case (acc, filter) => acc.addRelationshipFilter((t: Entity) => filter.test(t))
    }
    val shortestPathPredicate = new ShortestPathPredicate {
      override def test(path: Path): Boolean = pathPredicate.test(path)
    }

    new ShortestPath(new BasicEvaluationContext(transactionalContext.tc.transaction(), getDatabaseService), depth,
      expanderWithAllPredicates.build(), shortestPathPredicate, memoryTracker) {
      override protected def filterNextLevelNodes(nextNode: Node): Node =
        if (filters.isEmpty) nextNode
        else if (filters.forall(filter => filter test nextNode)) nextNode
        else null
    }
  }

  override def detachDeleteNode(node: Long): Int = transactionalContext.dataWrite.nodeDetachDelete(node)

  override def assertSchemaWritesAllowed(): Unit =
    transactionalContext.kernelTransaction.schemaWrite()

  override def assertShowIndexAllowed(): Unit = {
    val ktx = transactionalContext.kernelTransaction
    ktx.securityAuthorizationHandler().assertShowIndexAllowed(transactionalContext.kernelTransaction.securityContext())
  }

  override def assertShowConstraintAllowed(): Unit = {
    val ktx = transactionalContext.kernelTransaction
    ktx.securityAuthorizationHandler().assertShowConstraintAllowed(transactionalContext.kernelTransaction.securityContext())
  }

  override def graph(): GraphDatabaseQueryService = {
    transactionalContext.tc.graph()
  }

  private def allocateAndTraceNodeCursor() = {
    val cursor = transactionalContext.cursors.allocateNodeCursor(transactionalContext.kernelTransaction.cursorContext)
    resources.trace(cursor)
    cursor
  }

  private def allocateAndTraceRelationshipScanCursor() = {
    val cursor = transactionalContext.cursors.allocateRelationshipScanCursor(transactionalContext.kernelTransaction.cursorContext)
    resources.trace(cursor)
    cursor
  }

  private def allocateAndTraceNodeValueIndexCursor() = {
    val cursor = transactionalContext.cursors.allocateNodeValueIndexCursor(transactionalContext.kernelTransaction.cursorContext, transactionalContext.kernelTransaction.memoryTracker())
    resources.trace(cursor)
    cursor
  }

  private def allocateAndTraceRelationshipValueIndexCursor() = {
    val cursor = transactionalContext.cursors.allocateRelationshipValueIndexCursor(transactionalContext.kernelTransaction.cursorContext, transactionalContext.kernelTransaction.memoryTracker())
    resources.trace(cursor)
    cursor
  }

  private def allocateAndTraceNodeLabelIndexCursor() = {
    val cursor = transactionalContext.cursors.allocateNodeLabelIndexCursor(transactionalContext.kernelTransaction.cursorContext)
    resources.trace(cursor)
    cursor
  }

  abstract class PrimitiveCursorIterator extends ClosingLongIterator {
    private var _next: Long = fetchNext()

    protected def fetchNext(): Long

    override def innerHasNext: Boolean = _next >= 0

    override def next(): Long = {
      if (!hasNext) {
        Iterator.empty.next()
      }

      val current = _next
      _next = fetchNext()
      current
    }
  }
}

object TransactionBoundQueryContext {

  abstract class CursorIterator[T] extends ClosingIterator[T] {
    private var _next: T = fetchNext()

    protected def fetchNext(): T

    override def innerHasNext: Boolean = _next != null

    override def next(): T = {
      if (!hasNext) {
        Iterator.empty.next()
      }

      val current = _next
      _next = fetchNext()
      current
    }
  }

  abstract class BaseRelationshipCursorIterator extends ClosingLongIterator with RelationshipIterator {

    import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.BaseRelationshipCursorIterator.NOT_INITIALIZED
    import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.BaseRelationshipCursorIterator.NO_ID

    private var _next = NOT_INITIALIZED
    protected var relTypeId: Int = NO_ID
    protected var source: Long = NO_ID
    protected var target: Long = NO_ID

    override def relationshipVisit[EXCEPTION <: Exception](relationshipId: Long,
                                                           visitor: RelationshipVisitor[EXCEPTION]): Boolean = {
      visitor.visit(relationshipId, relTypeId, source, target)
      true
    }

    protected def fetchNext(): Long

    override def innerHasNext: Boolean = {
      if (_next == NOT_INITIALIZED) {
        _next = fetchNext()
      }

      _next >= 0
    }

    override def startNodeId(): Long = source

    override def endNodeId(): Long = target

    override def typeId(): Int = relTypeId

    /**
     * Store the current state in case the underlying cursor is closed when calling next.
     */
    protected def storeState(): Unit

    override def next(): Long = {
      if (!hasNext) {
        close()
        Iterator.empty.next()
      }

      val current = _next
      storeState()
      // Note that if no more elements are found cursors
      // will be closed so no need to do an extra check after fetching
      _next = fetchNext()

      current
    }

    override def close(): Unit
  }

  class RelationshipCursorIterator(selectionCursor: RelationshipTraversalCursor) extends BaseRelationshipCursorIterator {

    override protected def fetchNext(): Long =
      if (selectionCursor.next()) selectionCursor.relationshipReference()
      else {
        selectionCursor.close()
        -1L
      }

    override protected def storeState(): Unit = {
      relTypeId = selectionCursor.`type`()
      source = selectionCursor.sourceNodeReference()
      target = selectionCursor.targetNodeReference()
    }

    override def close(): Unit = selectionCursor.close()
  }

  class RelationshipTypeCursorIterator(read: Read, typeIndexCursor: RelationshipTypeIndexCursor, scanCursor: RelationshipScanCursor) extends BaseRelationshipCursorIterator {

    override def relationshipVisit[EXCEPTION <: Exception](relationshipId: Long,
                                                           visitor: RelationshipVisitor[EXCEPTION]): Boolean = {
      visitor.visit(relationshipId, relTypeId, source, target)
      true
    }

    private def nextFromRelStore(): Boolean = {
      read.singleRelationship(typeIndexCursor.relationshipReference(), scanCursor)
      scanCursor.next()
    }

    override protected def fetchNext(): Long = {
      while (typeIndexCursor.next()) {
        // check that relationship was successfully retrieved from store (protect against concurrent deletes)
        if (nextFromRelStore()) {
          return scanCursor.relationshipReference()
        }
      }
      typeIndexCursor.close()
      scanCursor.close()
      -1L
    }

    override protected def storeState(): Unit = {
      relTypeId = scanCursor.`type`()
      source = scanCursor.sourceNodeReference()
      target = scanCursor.targetNodeReference()
    }

    override def close(): Unit = {
      typeIndexCursor.close()
      scanCursor.close()
    }
  }

  object BaseRelationshipCursorIterator {
    private val NOT_INITIALIZED = -2L
    private val NO_ID = -1
  }

  trait IndexSearchMonitor {

    def indexSeek(index: IndexDescriptor, values: Seq[Any]): Unit

    def lockingUniqueIndexSeek(index: IndexDescriptor, values: Seq[Any]): Unit
  }

  object IndexSearchMonitor {
    val NOOP: IndexSearchMonitor = new IndexSearchMonitor {
      override def indexSeek(index: IndexDescriptor, values: Seq[Any]): Unit = {}

      override def lockingUniqueIndexSeek(index: IndexDescriptor,
                                          values: Seq[Any]): Unit = {}
    }
  }
}
