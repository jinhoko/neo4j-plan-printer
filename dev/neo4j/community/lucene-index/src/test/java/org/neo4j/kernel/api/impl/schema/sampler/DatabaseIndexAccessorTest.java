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
package org.neo4j.kernel.api.impl.schema.sampler;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import org.neo4j.collection.PrimitiveLongCollections;
import org.neo4j.configuration.Config;
import org.neo4j.function.IOFunction;
import org.neo4j.internal.kernel.api.PropertyIndexQuery;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotApplicableKernelException;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotFoundKernelException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.impl.index.storage.DirectoryFactory;
import org.neo4j.kernel.api.impl.schema.LuceneIndexAccessor;
import org.neo4j.kernel.api.impl.schema.LuceneSchemaIndexBuilder;
import org.neo4j.kernel.api.impl.schema.SchemaIndex;
import org.neo4j.kernel.api.impl.schema.TaskCoordinator;
import org.neo4j.kernel.api.index.IndexQueryHelper;
import org.neo4j.kernel.api.index.IndexSampler;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.kernel.impl.index.schema.NodeValueIterator;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.test.rule.concurrent.ThreadingRule;
import org.neo4j.test.rule.fs.EphemeralFileSystemRule;
import org.neo4j.util.concurrent.BinaryLatch;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.neo4j.collection.PrimitiveLongCollections.toSet;
import static org.neo4j.configuration.helpers.DatabaseReadOnlyChecker.writable;
import static org.neo4j.internal.helpers.collection.Iterators.asSet;
import static org.neo4j.internal.kernel.api.IndexQueryConstraints.unconstrained;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.exact;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.range;
import static org.neo4j.internal.kernel.api.QueryContext.NULL_CONTEXT;
import static org.neo4j.internal.schema.SchemaDescriptor.forLabel;
import static org.neo4j.io.IOUtils.closeAll;
import static org.neo4j.io.pagecache.context.CursorContext.NULL;
import static org.neo4j.kernel.api.impl.schema.LuceneTestTokenNameLookup.SIMPLE_TOKEN_LOOKUP;
import static org.neo4j.test.rule.concurrent.ThreadingRule.waitingWhileIn;

@RunWith( Parameterized.class )
public class DatabaseIndexAccessorTest
{
    private static final int PROP_ID = 1;

    @Rule
    public final ThreadingRule threading = new ThreadingRule();
    @ClassRule
    public static final EphemeralFileSystemRule fileSystemRule = new EphemeralFileSystemRule();

    @Parameterized.Parameter( 0 )
    public IndexDescriptor index;
    @Parameterized.Parameter( 1 )
    public IOFunction<DirectoryFactory,LuceneIndexAccessor> accessorFactory;

    private LuceneIndexAccessor accessor;
    private final long nodeId = 1;
    private final long nodeId2 = 2;
    private final Object value = "value";
    private final Object value2 = "40";
    private DirectoryFactory.InMemoryDirectoryFactory dirFactory;
    private static final IndexDescriptor GENERAL_INDEX = IndexPrototype.forSchema( forLabel( 0, PROP_ID ) ).withName( "a" ).materialise( 0 );
    private static final IndexDescriptor UNIQUE_INDEX = IndexPrototype.uniqueForSchema( forLabel( 1, PROP_ID ) ).withName( "b" ).materialise( 1 );
    private static final Config CONFIG = Config.defaults();

    @Parameterized.Parameters( name = "{0}" )
    public static Collection<Object[]> implementations()
    {
        final Path dir = Path.of( "dir" );
        return Arrays.asList(
                arg( GENERAL_INDEX, dirFactory1 ->
                {
                    SchemaIndex index = LuceneSchemaIndexBuilder.create( GENERAL_INDEX, writable(), CONFIG )
                            .withFileSystem( fileSystemRule.get() )
                            .withDirectoryFactory( dirFactory1 )
                            .withIndexRootFolder( dir.resolve( "1" ) )
                            .build();

                    index.create();
                    index.open();
                    return new LuceneIndexAccessor( index, GENERAL_INDEX, SIMPLE_TOKEN_LOOKUP );
                } ),
                arg( UNIQUE_INDEX, dirFactory1 ->
                {
                    SchemaIndex index = LuceneSchemaIndexBuilder.create( UNIQUE_INDEX, writable(), CONFIG )
                            .withFileSystem( fileSystemRule.get() )
                            .withDirectoryFactory( dirFactory1 )
                            .withIndexRootFolder( dir.resolve( "testIndex" ) )
                            .build();

                    index.create();
                    index.open();
                    return new LuceneIndexAccessor( index, UNIQUE_INDEX, SIMPLE_TOKEN_LOOKUP );
                } )
        );
    }

    private static Object[] arg(
            IndexDescriptor index,
            IOFunction<DirectoryFactory,LuceneIndexAccessor> foo )
    {
        return new Object[]{index, foo};
    }

    @Before
    public void before() throws IOException
    {
        dirFactory = new DirectoryFactory.InMemoryDirectoryFactory();
        accessor = accessorFactory.apply( dirFactory );
    }

    @After
    public void after() throws IOException
    {
        closeAll( accessor, dirFactory );
    }

    @Test
    public void indexReaderShouldSupportScan() throws Exception
    {
        // GIVEN
        updateAndCommit( asList( add( nodeId, value ), add( nodeId2, value2 ) ) );
        var reader = accessor.newValueReader();

        // WHEN
        Set<Long> results = resultSet( reader, PropertyIndexQuery.exists( PROP_ID ) );
        Set<Long> results2 = resultSet( reader, exact( PROP_ID, value ) );

        // THEN
        assertEquals( asSet( nodeId, nodeId2 ), results );
        assertEquals( asSet( nodeId ), results2 );
        reader.close();
    }

    @Test
    public void indexStringRangeQuery() throws Exception
    {
        updateAndCommit( asList( add( PROP_ID, "A" ), add( 2, "B" ), add( 3, "C" ), add( 4, "" ) ) );

        var reader = accessor.newValueReader();

        long[] rangeFromBInclusive = resultsArray( reader, range( PROP_ID, "B", true, null, false ) );
        assertThat( rangeFromBInclusive ).contains( 2, 3 );

        long[] rangeFromANonInclusive = resultsArray( reader, range( PROP_ID, "A", false, null, false ) );
        assertThat( rangeFromANonInclusive ).contains( 2, 3 );

        long[] emptyLowInclusive = resultsArray( reader, range( PROP_ID, "", true, null, false ) );
        assertThat( emptyLowInclusive ).contains( PROP_ID, 2, 3, 4 );

        long[] emptyUpperNonInclusive = resultsArray( reader, range( PROP_ID, "B", true, "", false ) );
        assertThat( emptyUpperNonInclusive ).isEmpty();

        long[] emptyInterval = resultsArray( reader, range( PROP_ID, "", true, "", true ) );
        assertThat( emptyInterval ).contains( 4 );

        long[] emptyAllNonInclusive = resultsArray( reader, range( PROP_ID, "", false, null, false ) );
        assertThat( emptyAllNonInclusive ).contains( PROP_ID, 2, 3 );

        long[] nullNonInclusive = resultsArray( reader, range( PROP_ID, (String) null, false, null, false ) );
        assertThat( nullNonInclusive ).contains( PROP_ID, 2, 3, 4 );

        long[] nullInclusive = resultsArray( reader, range( PROP_ID, (String) null, false, null, false ) );
        assertThat( nullInclusive ).contains( PROP_ID, 2, 3, 4 );
    }

    @Test
    public void indexNumberRangeQueryMustThrow() throws Exception
    {
        updateAndCommit( asList( add( 1, "1" ), add( 2, "2" ), add( 3, "3" ), add( 4, "4" ), add( 5, "Double.NaN" ) ) );

        var reader = accessor.newValueReader();

        try
        {
            resultsArray( reader, range( PROP_ID, 2, true, 3, true ) );
            fail( "Expected to throw" );
        }
        catch ( UnsupportedOperationException e )
        {
            assertEquals( "Range scans of value group NUMBER are not supported", e.getMessage() );
        }
    }

    @Test
    public void indexReaderShouldHonorRepeatableReads() throws Exception
    {
        // GIVEN
        updateAndCommit( singletonList( add( nodeId, value ) ) );
        var reader = accessor.newValueReader();

        // WHEN
        updateAndCommit( singletonList( remove( nodeId, value ) ) );

        // THEN
        assertEquals( asSet( nodeId ), resultSet( reader, exact( PROP_ID, value ) ) );
        reader.close();
    }

    @Test
    public void multipleIndexReadersFromDifferentPointsInTimeCanSeeDifferentResults() throws Exception
    {
        // WHEN
        updateAndCommit( singletonList( add( nodeId, value ) ) );
        var firstReader = accessor.newValueReader();
        updateAndCommit( singletonList( add( nodeId2, value2 ) ) );
        var secondReader = accessor.newValueReader();

        // THEN
        assertEquals( asSet( nodeId ), resultSet( firstReader, exact( PROP_ID, value ) ) );
        assertEquals( asSet(), resultSet( firstReader, exact( PROP_ID, value2 ) ) );
        assertEquals( asSet( nodeId ), resultSet( secondReader, exact( PROP_ID, value ) ) );
        assertEquals( asSet( nodeId2 ), resultSet( secondReader, exact( PROP_ID, value2 ) ) );
        firstReader.close();
        secondReader.close();
    }

    @Test
    public void canAddNewData() throws Exception
    {
        // WHEN
        updateAndCommit( asList( add( nodeId, value ), add( nodeId2, value2 ) ) );
        var reader = accessor.newValueReader();

        // THEN
        assertEquals( asSet( nodeId ), resultSet( reader, exact( PROP_ID, value ) ) );
        reader.close();
    }

    @Test
    public void canChangeExistingData() throws Exception
    {
        // GIVEN
        updateAndCommit( singletonList( add( nodeId, value ) ) );

        // WHEN
        updateAndCommit( singletonList( change( nodeId, value, value2 ) ) );
        var reader = accessor.newValueReader();

        // THEN
        assertEquals( asSet( nodeId ), resultSet( reader, exact( PROP_ID, value2 ) ) );
        assertEquals( emptySet(), resultSet( reader, exact( PROP_ID, value ) ) );
        reader.close();
    }

    @Test
    public void canRemoveExistingData() throws Exception
    {
        // GIVEN
        updateAndCommit( asList( add( nodeId, value ), add( nodeId2, value2 ) ) );

        // WHEN
        updateAndCommit( singletonList( remove( nodeId, value ) ) );
        var reader = accessor.newValueReader();

        // THEN
        assertEquals( asSet( nodeId2 ), resultSet( reader, exact( PROP_ID, value2 ) ) );
        assertEquals( asSet(), resultSet( reader, exact( PROP_ID, value ) ) );
        reader.close();
    }

    @Test
    public void shouldStopSamplingWhenIndexIsDropped() throws Exception
    {
        // given
        updateAndCommit( asList( add( nodeId, value ), add( nodeId2, value2 ) ) );

        // when
        var indexReader = accessor.newValueReader();
        BinaryLatch dropLatch = new BinaryLatch();
        BinaryLatch sampleLatch = new BinaryLatch();

        LuceneIndexSampler indexSampler = spy( (LuceneIndexSampler) indexReader.createSampler() );
        doAnswer( inv ->
        {
            var obj = inv.callRealMethod();
            dropLatch.release(); //We have now started the sampling, let the index try to drop
            sampleLatch.await(); //Wait for the drop to be blocked
            return obj;
        } ).when( indexSampler ).newTask();

        List<Future<?>> futures = new ArrayList<>();
        try ( var reader = indexReader /* do not inline! */;
              IndexSampler sampler = indexSampler /* do not inline! */ )
        {
            futures.add( threading.execute( (IOFunction<Void,Void>) nothing ->
            {
                try
                {
                    indexSampler.sampleIndex( NULL );
                    fail( "expected exception" );
                }
                catch ( IndexNotFoundKernelException e )
                {
                    assertEquals( "Index dropped while sampling.", e.getMessage() );
                }
                finally
                {
                    dropLatch.release(); //if something goes wrong we do not want to block the drop
                }
                return nothing;
            }, null ) );

            futures.add( threading.executeAndAwait( (IOFunction<Void,Void>) nothing ->
            {
                dropLatch.await(); //need to wait for the sampling to start before we drop
                accessor.drop();
                return nothing;
            }, null, waitingWhileIn( TaskCoordinator.class, "awaitCompletion" ), 10, MINUTES ) );

        }
        finally
        {
            sampleLatch.release(); //drop is blocked, okay to finish sampling (will fail since index is dropped)
            for ( Future<?> future : futures )
            {
                future.get();
            }
        }
    }

    private static Set<Long> resultSet( ValueIndexReader reader, PropertyIndexQuery... queries ) throws IndexNotApplicableKernelException
    {
        return toSet( results( reader, queries ) );
    }

    private static NodeValueIterator results( ValueIndexReader reader, PropertyIndexQuery... queries ) throws IndexNotApplicableKernelException
    {
        NodeValueIterator results = new NodeValueIterator();
        reader.query( NULL_CONTEXT, results, unconstrained(), queries );
        return results;
    }

    private static long[] resultsArray( ValueIndexReader reader, PropertyIndexQuery... queries ) throws IndexNotApplicableKernelException
    {
        try ( NodeValueIterator iterator = results( reader, queries ) )
        {
            return PrimitiveLongCollections.asArray( iterator );
        }
    }

    private IndexEntryUpdate<?> add( long nodeId, Object value )
    {
        return IndexQueryHelper.add( nodeId, index.schema(), value );
    }

    private IndexEntryUpdate<?> remove( long nodeId, Object value )
    {
        return IndexQueryHelper.remove( nodeId, index.schema(), value );
    }

    private IndexEntryUpdate<?> change( long nodeId, Object valueBefore, Object valueAfter )
    {
        return IndexQueryHelper.change( nodeId, index.schema(), valueBefore, valueAfter );
    }

    private void updateAndCommit( List<IndexEntryUpdate<?>> nodePropertyUpdates ) throws IndexEntryConflictException
    {
        try ( IndexUpdater updater = accessor.newUpdater( IndexUpdateMode.ONLINE, NULL ) )
        {
            for ( IndexEntryUpdate<?> update : nodePropertyUpdates )
            {
                updater.process( update );
            }
        }
    }
}
