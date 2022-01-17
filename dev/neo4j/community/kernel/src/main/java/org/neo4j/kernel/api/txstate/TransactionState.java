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
package org.neo4j.kernel.api.txstate;

import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.constraints.IndexBackedConstraintDescriptor;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueTuple;

/**
 * Kernel transaction state, please see {@link org.neo4j.kernel.impl.api.state.TxState} for implementation details.
 *
 * This interface defines the mutating methods for the transaction state, methods for reading are defined in
 * {@link ReadableTransactionState}. These mutating methods follow the rule that they all contain the word "Do" in the name.
 * This naming convention helps deciding where to set {@link #hasChanges()} in the
 * {@link org.neo4j.kernel.impl.api.state.TxState main implementation class}.
 */
public interface TransactionState extends ReadableTransactionState
{
    // ENTITY RELATED

    void relationshipDoCreate( long id, int relationshipTypeId, long startNodeId, long endNodeId );

    void nodeDoCreate( long id );

    void relationshipDoDelete( long relationshipId, int type, long startNode, long endNode );

    void relationshipDoDeleteAddedInThisTx( long relationshipId );

    void nodeDoDelete( long nodeId );

    void nodeDoAddProperty( long nodeId, int newPropertyKeyId, Value value );

    void nodeDoChangeProperty( long nodeId, int propertyKeyId, Value newValue );

    void relationshipDoReplaceProperty( long relationshipId, int propertyKeyId, Value replacedValue, Value newValue );

    void nodeDoRemoveProperty( long nodeId, int propertyKeyId );

    void relationshipDoRemoveProperty( long relationshipId, int propertyKeyId );

    void nodeDoAddLabel( long labelId, long nodeId );

    void nodeDoRemoveLabel( long labelId, long nodeId );

    // TOKEN RELATED

    void labelDoCreateForName( String labelName, boolean internal, long id );

    void propertyKeyDoCreateForName( String propertyKeyName, boolean internal, int id );

    void relationshipTypeDoCreateForName( String relationshipTypeName, boolean internal, int id );

    // SCHEMA RELATED

    void indexDoAdd( IndexDescriptor index );

    void indexDoDrop( IndexDescriptor index );

    boolean indexDoUnRemove( IndexDescriptor index );

    void constraintDoAdd( ConstraintDescriptor constraint );

    void constraintDoAdd( IndexBackedConstraintDescriptor constraint, IndexDescriptor index );

    void constraintDoDrop( ConstraintDescriptor constraint );

    boolean constraintDoUnRemove( ConstraintDescriptor constraint );

    void indexDoUpdateEntry( SchemaDescriptor descriptor, long nodeId, ValueTuple before, ValueTuple after );

    // MEMORY TRACKING

    MemoryTracker memoryTracker();
}