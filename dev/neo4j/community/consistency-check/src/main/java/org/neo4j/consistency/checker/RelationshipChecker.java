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
package org.neo4j.consistency.checker;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.neo4j.consistency.checking.cache.CacheAccess;
import org.neo4j.consistency.checking.cache.CacheSlots;
import org.neo4j.consistency.checking.full.ConsistencyFlags;
import org.neo4j.consistency.report.ConsistencyReport;
import org.neo4j.consistency.store.synthetic.TokenScanDocument;
import org.neo4j.internal.helpers.collection.BoundedIterable;
import org.neo4j.internal.helpers.collection.LongRange;
import org.neo4j.internal.helpers.progress.ProgressListener;
import org.neo4j.internal.recordstorage.RelationshipCounter;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.PropertySchemaType;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.impl.index.schema.EntityTokenRange;
import org.neo4j.kernel.impl.index.schema.EntityTokenRangeImpl;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.token.TokenHolders;
import org.neo4j.values.storable.Value;

import static org.neo4j.common.EntityType.RELATIONSHIP;
import static org.neo4j.consistency.checker.NodeChecker.compareTwoSortedLongArrays;
import static org.neo4j.consistency.checker.RecordLoading.checkValidToken;
import static org.neo4j.consistency.checker.RecordLoading.lightClear;
import static org.neo4j.kernel.impl.index.schema.EntityTokenRangeImpl.NO_TOKENS;
import static org.neo4j.kernel.impl.store.record.Record.NULL_REFERENCE;

/**
 * Checks relationships and their properties, type and schema indexes.
 */
class RelationshipChecker implements Checker
{
    private static final String UNUSED_RELATIONSHIP_CHECKER_TAG = "unusedRelationshipChecker";
    private static final String RELATIONSHIP_RANGE_CHECKER_TAG = "relationshipRangeChecker";
    private final NeoStores neoStores;
    private final ParallelExecution execution;
    private final ConsistencyReport.Reporter reporter;
    private final CacheAccess cacheAccess;
    private final TokenHolders tokenHolders;
    private final RecordLoading recordLoader;
    private final CountsState observedCounts;
    private final CheckerContext context;
    private final MutableIntObjectMap<MutableIntSet> mandatoryProperties;
    private final List<IndexDescriptor> indexes;
    private final ProgressListener progress;

    RelationshipChecker( CheckerContext context, MutableIntObjectMap<MutableIntSet> mandatoryProperties )
    {
        this.context = context;
        this.neoStores = context.neoStores;
        this.execution = context.execution;
        this.reporter = context.reporter;
        this.cacheAccess = context.cacheAccess;
        this.tokenHolders = context.tokenHolders;
        this.recordLoader = context.recordLoader;
        this.observedCounts = context.observedCounts;
        this.mandatoryProperties = mandatoryProperties;
        this.indexes = context.indexAccessors.onlineRules( RELATIONSHIP );
        this.progress = context.progressReporter( this, "Relationships", neoStores.getRelationshipStore().getHighId() );
    }

    @Override
    public boolean shouldBeChecked( ConsistencyFlags flags )
    {
        return flags.isCheckGraph() || !indexes.isEmpty() && flags.isCheckIndexes();
    }

    @Override
    public void check( LongRange nodeIdRange, boolean firstRange, boolean lastRange ) throws Exception
    {
        execution.run( getClass().getSimpleName() + "-relationships", execution.partition( neoStores.getRelationshipStore(),
                ( from, to, last ) -> () -> check( nodeIdRange, firstRange, from, to, firstRange && last ) ) );
        // Let's not report progress for this since it's so much faster than store checks, it's just scanning the cache
        execution.run( getClass().getSimpleName() + "-unusedRelationships", execution.partition( nodeIdRange,
                ( from, to, last ) -> () -> checkNodesReferencingUnusedRelationships( from, to, context.pageCacheTracer ) ) );
    }

    private void check( LongRange nodeIdRange, boolean firstRound, long fromRelationshipId, long toRelationshipId, boolean checkToEndOfIndex ) throws Exception
    {
        RelationshipCounter counter = observedCounts.instantiateRelationshipCounter();
        long[] typeHolder = new long[1];
        try ( var cursorContext = new CursorContext( context.pageCacheTracer.createPageCursorTracer( RELATIONSHIP_RANGE_CHECKER_TAG ) );
              RecordReader<RelationshipRecord> relationshipReader = new RecordReader<>( context.neoStores.getRelationshipStore(), true, cursorContext );
              BoundedIterable<EntityTokenRange> relationshipTypeReader = getRelationshipTypeIndexReader( fromRelationshipId, toRelationshipId,
                      checkToEndOfIndex, cursorContext );
              SafePropertyChainReader property = new SafePropertyChainReader( context, cursorContext );
              SchemaComplianceChecker schemaComplianceChecker = new SchemaComplianceChecker( context, mandatoryProperties, indexes, cursorContext,
                      context.memoryTracker ) )
        {
            ProgressListener localProgress = progress.threadLocalReporter();
            CacheAccess.Client client = cacheAccess.client();
            MutableIntObjectMap<Value> propertyValues = new IntObjectHashMap<>();
            Iterator<EntityTokenRange> relationshipTypeRangeIterator = relationshipTypeReader.iterator();
            EntityTokenIndexCheckState typeIndexState = new EntityTokenIndexCheckState( null, fromRelationshipId - 1 );

            for ( long relationshipId = fromRelationshipId; relationshipId < toRelationshipId && !context.isCancelled(); relationshipId++ )
            {
                localProgress.add( 1 );
                RelationshipRecord relationshipRecord = relationshipReader.read( relationshipId );
                if ( !relationshipRecord.inUse() )
                {
                    continue;
                }

                // Start/end nodes
                long startNode = relationshipRecord.getFirstNode();
                boolean startNodeIsWithinRange = nodeIdRange.isWithinRangeExclusiveTo( startNode );
                boolean startNodeIsNegativeOnFirstRound = startNode < 0 && firstRound;
                if ( startNodeIsWithinRange || startNodeIsNegativeOnFirstRound )
                {
                    checkRelationshipVsNode( client, relationshipRecord, startNode, relationshipRecord.isFirstInFirstChain(),
                            ( relationship, node ) -> reporter.forRelationship( relationship ).sourceNodeNotInUse( node ),
                            ( relationship, node ) -> reporter.forRelationship( relationship ).sourceNodeDoesNotReferenceBack( node ),
                            ( relationship, node ) -> reporter.forNode( node ).relationshipNotFirstInSourceChain( relationship ),
                            ( relationship, node ) -> reporter.forRelationship( relationship ).sourceNodeHasNoRelationships( node ),
                            relationship -> reporter.forRelationship( relationship ).illegalSourceNode(), cursorContext );
                }
                long endNode = relationshipRecord.getSecondNode();
                boolean endNodeIsWithinRange = nodeIdRange.isWithinRangeExclusiveTo( endNode );
                boolean endNodeIsNegativeOnFirstRound = endNode < 0 && firstRound;
                if ( endNodeIsWithinRange || endNodeIsNegativeOnFirstRound )
                {
                    checkRelationshipVsNode( client, relationshipRecord, endNode, relationshipRecord.isFirstInSecondChain(),
                            ( relationship, node ) -> reporter.forRelationship( relationship ).targetNodeNotInUse( node ),
                            ( relationship, node ) -> reporter.forRelationship( relationship ).targetNodeDoesNotReferenceBack( node ),
                            ( relationship, node ) -> reporter.forNode( node ).relationshipNotFirstInTargetChain( relationship ),
                            ( relationship, node ) -> reporter.forRelationship( relationship ).targetNodeHasNoRelationships( node ),
                            relationship -> reporter.forRelationship( relationship ).illegalTargetNode(), cursorContext );
                }

                if ( firstRound )
                {
                    if ( startNode >= context.highNodeId )
                    {
                        reporter.forRelationship( relationshipRecord ).sourceNodeNotInUse( context.recordLoader.node( startNode, cursorContext ) );
                    }

                    if ( endNode >= context.highNodeId )
                    {
                        reporter.forRelationship( relationshipRecord ).targetNodeNotInUse( context.recordLoader.node( endNode, cursorContext ) );
                    }

                    // Properties
                    typeHolder[0] = relationshipRecord.getType();
                    lightClear( propertyValues );
                    boolean propertyChainIsOk = property.read( propertyValues, relationshipRecord, reporter::forRelationship, cursorContext );
                    if ( propertyChainIsOk )
                    {
                        schemaComplianceChecker.checkContainsMandatoryProperties( relationshipRecord, typeHolder, propertyValues, reporter::forRelationship );
                        // Here only the very small indexes (or indexes that we can't read the values from, like fulltext indexes)
                        // gets checked this way, larger indexes will be checked in IndexChecker
                        if ( context.consistencyFlags.isCheckIndexes() )
                        {
                            schemaComplianceChecker.checkCorrectlyIndexed( (RelationshipRecord) relationshipRecord, typeHolder, propertyValues,
                                    reporter::forRelationship );
                        }
                    }

                    // Type and count
                    checkValidToken( relationshipRecord, relationshipRecord.getType(), tokenHolders.relationshipTypeTokens(),
                            neoStores.getRelationshipTypeTokenStore(), ( rel, token ) -> reporter.forRelationship( rel ).illegalRelationshipType(),
                            ( rel, token ) -> reporter.forRelationship( rel ).relationshipTypeNotInUse( token ), cursorContext );
                    observedCounts.incrementRelationshipTypeCounts( counter, relationshipRecord );

                    // Relationship type index
                    if ( relationshipTypeReader.maxCount() != 0 )
                    {
                        checkRelationshipVsRelationshipTypeIndex( relationshipRecord, relationshipTypeRangeIterator, typeIndexState, relationshipId,
                                relationshipRecord.getType(), fromRelationshipId, cursorContext );
                    }
                }
                observedCounts.incrementRelationshipNodeCounts( counter, relationshipRecord, startNodeIsWithinRange, endNodeIsWithinRange );
            }
            if ( firstRound && !context.isCancelled() && relationshipTypeReader.maxCount() != 0 )
            {
                reportRemainingRelationshipTypeIndexEntries( relationshipTypeRangeIterator, typeIndexState,
                        checkToEndOfIndex ? Long.MAX_VALUE : toRelationshipId, cursorContext );
            }
            localProgress.done();
        }
    }

    private BoundedIterable<EntityTokenRange> getRelationshipTypeIndexReader( long fromRelationshipId, long toRelationshipId, boolean last,
            CursorContext cursorContext )
    {
        if ( context.relationshipTypeIndex != null )
        {
            return context.relationshipTypeIndex.newAllEntriesTokenReader( fromRelationshipId, last ? Long.MAX_VALUE : toRelationshipId,
                    cursorContext );
        }
        return BoundedIterable.empty();
    }

    private void checkRelationshipVsRelationshipTypeIndex( RelationshipRecord relationshipRecord,
            Iterator<EntityTokenRange> relationshipTypeRangeIterator, EntityTokenIndexCheckState relationshipTypeIndexState,
            long relationshipId, int type, long fromRelationshipId, CursorContext cursorContext )
    {
        // Detect relationship-type combinations that exists in the relationship type index, but not in the store
        while ( relationshipTypeIndexState.needToMoveRangeForwardToReachEntity( relationshipId ) && !context.isCancelled() )
        {
            if ( relationshipTypeRangeIterator.hasNext() )
            {
                if ( relationshipTypeIndexState.currentRange != null )
                {
                    for ( long relationshipIdMissingFromStore = relationshipTypeIndexState.lastCheckedEntityId + 1;
                          relationshipIdMissingFromStore < relationshipId && relationshipTypeIndexState.currentRange.covers( relationshipIdMissingFromStore );
                          relationshipIdMissingFromStore++ )
                    {
                        if ( relationshipTypeIndexState.currentRange.tokens( relationshipIdMissingFromStore ).length > 0 )
                        {
                            reporter.forRelationshipTypeScan( new TokenScanDocument( relationshipTypeIndexState.currentRange ) )
                                    .relationshipNotInUse( recordLoader.relationship( relationshipIdMissingFromStore, cursorContext ) );
                        }
                    }
                }
                relationshipTypeIndexState.currentRange = relationshipTypeRangeIterator.next();
                relationshipTypeIndexState.lastCheckedEntityId = Math.max( fromRelationshipId, relationshipTypeIndexState.currentRange.entities()[0] ) - 1;
            }
            else
            {
                break;
            }
        }

        if ( relationshipTypeIndexState.currentRange != null && relationshipTypeIndexState.currentRange.covers( relationshipId ) )
        {
            for ( long relationshipIdMissingFromStore = relationshipTypeIndexState.lastCheckedEntityId + 1; relationshipIdMissingFromStore < relationshipId;
                  relationshipIdMissingFromStore++ )
            {
                if ( relationshipTypeIndexState.currentRange.tokens( relationshipIdMissingFromStore ).length > 0 )
                {
                    reporter.forRelationshipTypeScan( new TokenScanDocument( relationshipTypeIndexState.currentRange ) )
                            .relationshipNotInUse( recordLoader.relationship( relationshipIdMissingFromStore, cursorContext ) );
                }
            }
            long[] relationshipTypesInTypeIndex = relationshipTypeIndexState.currentRange.tokens( relationshipId );
            validateTypeIds( relationshipRecord, type, relationshipTypesInTypeIndex, relationshipTypeIndexState.currentRange, cursorContext );
            relationshipTypeIndexState.lastCheckedEntityId = relationshipId;
        }
        else
        {
            TokenScanDocument document = new TokenScanDocument( new EntityTokenRangeImpl( relationshipId / Long.SIZE, NO_TOKENS, RELATIONSHIP ) );
            reporter.forRelationshipTypeScan( document ).relationshipTypeNotInIndex( recordLoader.relationship( relationshipId, cursorContext ), type );
        }
    }

    private void reportRemainingRelationshipTypeIndexEntries( Iterator<EntityTokenRange> relationshipTypeRangeIterator,
            EntityTokenIndexCheckState relationshipTypeIndexState, long toRelationshipId, CursorContext cursorContext )
    {
        if ( relationshipTypeIndexState.currentRange == null && relationshipTypeRangeIterator.hasNext() )
        {
            // Seems that nobody touched this iterator before, i.e. no nodes in this whole range
            relationshipTypeIndexState.currentRange = relationshipTypeRangeIterator.next();
        }

        while ( relationshipTypeIndexState.currentRange != null && !context.isCancelled() )
        {
            for ( long relationshipIdMissingFromStore = relationshipTypeIndexState.lastCheckedEntityId + 1;
                  relationshipIdMissingFromStore < toRelationshipId &&
                          !relationshipTypeIndexState.needToMoveRangeForwardToReachEntity( relationshipIdMissingFromStore );
                  relationshipIdMissingFromStore++ )
            {
                if ( relationshipTypeIndexState.currentRange.covers( relationshipIdMissingFromStore ) &&
                        relationshipTypeIndexState.currentRange.tokens( relationshipIdMissingFromStore ).length > 0 )
                {
                    reporter.forRelationshipTypeScan( new TokenScanDocument( relationshipTypeIndexState.currentRange ) )
                            .relationshipNotInUse( recordLoader.relationship( relationshipIdMissingFromStore, cursorContext ) );
                }
                relationshipTypeIndexState.lastCheckedEntityId = relationshipIdMissingFromStore;
            }
            relationshipTypeIndexState.currentRange = relationshipTypeRangeIterator.hasNext() ? relationshipTypeRangeIterator.next() : null;
        }
    }

    private void validateTypeIds( RelationshipRecord relationshipRecord, int typeInStore, long[] relationshipTypesInTypeIndex,
            EntityTokenRange entityTokenRange, CursorContext cursorContext )
    {
        compareTwoSortedLongArrays( PropertySchemaType.COMPLETE_ALL_TOKENS, new long[]{typeInStore}, relationshipTypesInTypeIndex,
                indexType -> reporter.forRelationshipTypeScan( new TokenScanDocument( entityTokenRange ) )
                        .relationshipDoesNotHaveExpectedRelationshipType( recordLoader.relationship( relationshipRecord.getId(), cursorContext ),
                                indexType ),
                storeType -> reporter.forRelationshipTypeScan( new TokenScanDocument( entityTokenRange ) )
                        .relationshipTypeNotInIndex( recordLoader.relationship( relationshipRecord.getId(), cursorContext ), storeType )
        );
    }

    private void checkRelationshipVsNode( CacheAccess.Client client, RelationshipRecord relationshipRecord, long node, boolean firstInChain,
            BiConsumer<RelationshipRecord,NodeRecord> reportNodeNotInUse,
            BiConsumer<RelationshipRecord,NodeRecord> reportNodeDoesNotReferenceBack,
            BiConsumer<RelationshipRecord,NodeRecord> reportNodeNotFirstInChain,
            BiConsumer<RelationshipRecord,NodeRecord> reportNodeHasNoChain,
            Consumer<RelationshipRecord> reportIllegalNode, CursorContext cursorContext )
    {
        // Check validity of node reference
        if ( node < 0 )
        {
            reportIllegalNode.accept( recordLoader.relationship( relationshipRecord.getId(), cursorContext ) );
            return;
        }

        // Check if node is in use
        boolean nodeInUse = client.getBooleanFromCache( node, CacheSlots.NodeLink.SLOT_IN_USE );
        if ( !nodeInUse )
        {
            reportNodeNotInUse.accept( recordLoader.relationship( relationshipRecord.getId(), cursorContext ), recordLoader.node( node, cursorContext ) );
            return;
        }

        // Check if node has nextRel reference at all
        long nodeNextRel = client.getFromCache( node, CacheSlots.NodeLink.SLOT_RELATIONSHIP_ID );
        if ( NULL_REFERENCE.is( nodeNextRel ) )
        {
            reportNodeHasNoChain.accept( recordLoader.relationship( relationshipRecord.getId(), cursorContext ), recordLoader.node( node, cursorContext ) );
            return;
        }

        // Check the node <--> relationship references
        boolean nodeIsDense = client.getBooleanFromCache( node, CacheSlots.NodeLink.SLOT_IS_DENSE );
        if ( !nodeIsDense )
        {
            if ( firstInChain )
            {
                if ( nodeNextRel != relationshipRecord.getId() )
                {
                    // Report RELATIONSHIP -> NODE inconsistency
                    reportNodeDoesNotReferenceBack.accept( recordLoader.relationship( relationshipRecord.getId(), cursorContext ),
                            recordLoader.node( node, cursorContext ) );
                    // Before marking this node as fully checked we should also check and report any NODE -> RELATIONSHIP inconsistency
                    RelationshipRecord relationshipThatNodeActuallyReferences = recordLoader.relationship( nodeNextRel, cursorContext );
                    if ( !relationshipThatNodeActuallyReferences.inUse() )
                    {
                        reporter.forNode( recordLoader.node( node, cursorContext ) ).relationshipNotInUse( relationshipThatNodeActuallyReferences );
                    }
                    else if ( relationshipThatNodeActuallyReferences.getFirstNode() != node && relationshipThatNodeActuallyReferences.getSecondNode() != node )
                    {
                        reporter.forNode( recordLoader.node( node, cursorContext ) ).relationshipForOtherNode( relationshipThatNodeActuallyReferences );
                    }
                }
                client.putToCacheSingle( node, CacheSlots.NodeLink.SLOT_CHECK_MARK, 0 );
            }
            if ( !firstInChain && nodeNextRel == relationshipRecord.getId() )
            {
                reportNodeNotFirstInChain.accept( recordLoader.relationship( relationshipRecord.getId(), cursorContext ),
                        recordLoader.node( node, cursorContext ) );
            }
        }
    }

    private void checkNodesReferencingUnusedRelationships( long fromNodeId, long toNodeId, PageCacheTracer pageCacheTracer )
    {
        // Do this after we've done node.nextRel caching and checking of those. Checking also clears those values, so simply
        // go through the cache and see if there are any relationship ids left and report them
        CacheAccess.Client client = cacheAccess.client();
        try ( var cursorContext = new CursorContext( pageCacheTracer.createPageCursorTracer( UNUSED_RELATIONSHIP_CHECKER_TAG ) ) )
        {
            for ( long id = fromNodeId; id < toNodeId && !context.isCancelled(); id++ )
            {
                // Only check if we haven't come across this sparse node while checking relationships
                boolean nodeInUse = client.getBooleanFromCache( id, CacheSlots.NodeLink.SLOT_IN_USE );
                if ( nodeInUse )
                {
                    boolean needsChecking = client.getBooleanFromCache( id, CacheSlots.NodeLink.SLOT_CHECK_MARK );
                    if ( needsChecking )
                    {
                        long nodeNextRel = client.getFromCache( id, CacheSlots.NodeLink.SLOT_RELATIONSHIP_ID );
                        boolean nodeIsDense = client.getBooleanFromCache( id, CacheSlots.NodeLink.SLOT_IS_DENSE );
                        if ( !NULL_REFERENCE.is( nodeNextRel ) )
                        {
                            if ( !nodeIsDense )
                            {
                                RelationshipRecord relationship = recordLoader.relationship( nodeNextRel, cursorContext );
                                NodeRecord node = recordLoader.node( id, cursorContext );
                                if ( !relationship.inUse() )
                                {
                                    reporter.forNode( node ).relationshipNotInUse( relationship );
                                }
                                else
                                {
                                    reporter.forNode( node ).relationshipForOtherNode( relationship );
                                }
                            }
                            else
                            {
                                RelationshipGroupRecord group = recordLoader.relationshipGroup( nodeNextRel, cursorContext );
                                if ( !group.inUse() )
                                {
                                    reporter.forNode( recordLoader.node( id, cursorContext ) ).relationshipGroupNotInUse( group );
                                }
                                else
                                {
                                    reporter.forNode( recordLoader.node( id, cursorContext ) ).relationshipGroupHasOtherOwner( group );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public String toString()
    {
        return String.format( "%s[highId:%d,indexesToCheck:%d]", getClass().getSimpleName(), neoStores.getRelationshipStore().getHighId(), indexes.size() );
    }
}
