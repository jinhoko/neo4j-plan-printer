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
package org.neo4j.graphdb.schema;

import org.assertj.core.util.Streams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.recordstorage.RecordStorageEngine;
import org.neo4j.internal.recordstorage.SchemaRuleAccess;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.extension.DbmsExtension;
import org.neo4j.test.extension.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.neo4j.internal.helpers.collection.Iterators.loop;
import static org.neo4j.internal.helpers.collection.Iterators.single;
import static org.neo4j.io.pagecache.context.CursorContext.NULL;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;

@DbmsExtension
class DropBrokenUniquenessConstraintIT
{
    private final Label label = Label.label( "Label" );
    private final String key = "key";

    @Inject
    private GraphDatabaseAPI db;
    @Inject
    private RecordStorageEngine storageEngine;
    private long initialConstraintCount;
    private long initialIndexCount;

    @BeforeEach
    void getInitialCounts()
    {
        try ( Transaction tx = db.beginTx() )
        {
            initialConstraintCount = Streams.stream( tx.schema().getConstraints() ).count();
            initialIndexCount = Streams.stream( tx.schema().getIndexes() ).count();
        }
    }

    @AfterEach
    void assertNoAdditionalConstraintsOrIndexes()
    {
        try ( Transaction tx = db.beginTx() )
        {
            assertEquals( initialConstraintCount, Streams.stream( tx.schema().getConstraints() ).count() );
            assertEquals( initialIndexCount, Streams.stream( tx.schema().getIndexes() ).count() );
        }
    }

    @Test
    void shouldDropUniquenessConstraintWithBackingIndexNotInUse()
    {
        // given
        String backingIndexName;
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().constraintFor( label ).assertPropertyIsUnique( key ).create();
            backingIndexName = single( tx.schema().getIndexes( label ).iterator() ).getName();
            tx.commit();
        }

        // when intentionally breaking the schema by setting the backing index rule to unused
        SchemaRuleAccess schemaRules = storageEngine.testAccessSchemaRules();
        schemaRules.deleteSchemaRule( schemaRules.indexGetForName( backingIndexName, NULL ), NULL );
        // At this point the SchemaCache doesn't know about this change so we have to reload it
        storageEngine.loadSchemaCache();
        try ( Transaction tx = db.beginTx() )
        {
            single( tx.schema().getConstraints( label ).iterator() ).drop();
            tx.commit();
        }

        // then
        // AfterEach
    }

    @Test
    void shouldDropUniquenessConstraintWithBackingIndexHavingNoOwner() throws KernelException
    {
        // given
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().constraintFor( label ).assertPropertyIsUnique( key ).create();
            tx.commit();
        }

        // when intentionally breaking the schema by setting the backing index rule to unused
        SchemaRuleAccess schemaRules = storageEngine.testAccessSchemaRules();
        writeSchemaRulesWithoutConstraint( schemaRules );
        // At this point the SchemaCache doesn't know about this change so we have to reload it
        storageEngine.loadSchemaCache();
        try ( Transaction tx = db.beginTx() )
        {
            single( tx.schema().getConstraints( label ).iterator() ).drop();
            tx.commit();
        }

        // then
        // AfterEach
    }

    @Test
    void shouldDropUniquenessConstraintWhereConstraintRecordIsMissing()
    {
        // given
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().constraintFor( label ).assertPropertyIsUnique( key ).create();
            tx.commit();
        }

        // when intentionally breaking the schema by setting the backing index rule to unused
        SchemaRuleAccess schemaRules = storageEngine.testAccessSchemaRules();
        schemaRules.constraintsGetAllIgnoreMalformed( NULL ).forEachRemaining( rule -> schemaRules.deleteSchemaRule( rule, NULL ) );

        // At this point the SchemaCache doesn't know about this change so we have to reload it
        storageEngine.loadSchemaCache();
        try ( Transaction tx = db.beginTx() )
        {
            // We don't use single() here, because it is okay for the schema cache reload to clean up after us.
            tx.schema().getConstraints( label ).forEach( ConstraintDefinition::drop );
            tx.schema().getIndexes( label ).forEach( IndexDefinition::drop );
            tx.commit();
        }

        // then
        // AfterEach
    }

    @Test
    void shouldDropUniquenessConstraintWhereConstraintRecordIsMissingAndIndexHasNoOwner() throws KernelException
    {
        // given
        try ( Transaction tx = db.beginTx() )
        {
            tx.schema().constraintFor( label ).assertPropertyIsUnique( key ).create();
            tx.commit();
        }

        // when intentionally breaking the schema by setting the backing index rule to unused
        SchemaRuleAccess schemaRules = storageEngine.testAccessSchemaRules();
        schemaRules.constraintsGetAllIgnoreMalformed( NULL ).forEachRemaining( rule -> schemaRules.deleteSchemaRule( rule, NULL ) );
        writeSchemaRulesWithoutConstraint( schemaRules );

        // At this point the SchemaCache doesn't know about this change so we have to reload it
        storageEngine.loadSchemaCache();
        try ( Transaction tx = db.beginTx() )
        {
            // We don't use single() here, because it is okay for the schema cache reload to clean up after us.
            tx.schema().getConstraints( label ).forEach( ConstraintDefinition::drop );
            tx.schema().getIndexes( label ).forEach( IndexDefinition::drop );
            tx.commit();
        }

        // then
        // AfterEach
    }

    private void writeSchemaRulesWithoutConstraint( SchemaRuleAccess schemaRules ) throws KernelException
    {
        for ( IndexDescriptor rule : loop( schemaRules.indexesGetAll( NULL ) ) )
        {
            schemaRules.writeSchemaRule( rule, NULL, INSTANCE );
        }
    }
}
