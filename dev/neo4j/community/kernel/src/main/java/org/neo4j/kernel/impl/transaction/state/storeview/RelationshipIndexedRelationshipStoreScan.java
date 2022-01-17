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
package org.neo4j.kernel.impl.transaction.state.storeview;

import java.util.function.IntPredicate;
import javax.annotation.Nullable;

import org.neo4j.configuration.Config;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.api.index.TokenIndexReader;
import org.neo4j.kernel.impl.api.index.PropertyScanConsumer;
import org.neo4j.kernel.impl.api.index.TokenScanConsumer;
import org.neo4j.lock.LockService;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.StorageReader;

public class RelationshipIndexedRelationshipStoreScan extends RelationshipStoreScan
{
    private final TokenIndexReader tokenIndexReader;

    public RelationshipIndexedRelationshipStoreScan( Config config, StorageReader storageReader,
                                                     LockService locks,
                                                     TokenIndexReader tokenIndexReader,
                                                     @Nullable TokenScanConsumer relationshipTypeScanConsumer,
                                                     @Nullable PropertyScanConsumer propertyScanConsumer,
                                                     int[] relationshipTypeIds,
                                                     IntPredicate propertyKeyIdFilter, boolean parallelWrite,
                                                     JobScheduler scheduler,
                                                     PageCacheTracer cacheTracer, MemoryTracker memoryTracker )
    {
        super( config, storageReader, locks, relationshipTypeScanConsumer, propertyScanConsumer, relationshipTypeIds, propertyKeyIdFilter, parallelWrite,
               scheduler, cacheTracer, memoryTracker );
        this.tokenIndexReader = tokenIndexReader;
    }

    @Override
    public EntityIdIterator getEntityIdIterator( CursorContext cursorContext )
    {
        return new TokenIndexScanIdIterator( tokenIndexReader, entityTokenIdFilter, cursorContext );
    }
}
