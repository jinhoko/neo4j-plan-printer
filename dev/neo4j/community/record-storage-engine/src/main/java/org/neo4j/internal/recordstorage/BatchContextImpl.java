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
package org.neo4j.internal.recordstorage;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.neo4j.io.IOUtils;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.store.IdUpdateListener;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.lock.LockGroup;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.IndexUpdateListener;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.util.concurrent.WorkSync;

/**
 * A batch context implementation that does not do anything with scan stores.
 * It assumes use of token indexes.
 * This will be the only implementation when migration to token indexes is done!
 */
public class BatchContextImpl implements BatchContext
{
    private final WorkSync<IndexUpdateListener,IndexUpdatesWork> indexUpdatesSync;
    private final NodeStore nodeStore;
    private final PropertyStore propertyStore;
    private final StorageEngine storageEngine;
    private final SchemaCache schemaCache;
    private final CursorContext cursorContext;
    private final MemoryTracker memoryTracker;
    private final IdUpdateListener idUpdateListener;

    private final IndexActivator indexActivator;
    private final LockGroup lockGroup;
    private IndexUpdates indexUpdates;

    public BatchContextImpl( IndexUpdateListener indexUpdateListener,
            WorkSync<IndexUpdateListener,IndexUpdatesWork> indexUpdatesSync, NodeStore nodeStore, PropertyStore propertyStore,
            RecordStorageEngine recordStorageEngine, SchemaCache schemaCache, CursorContext cursorContext, MemoryTracker memoryTracker,
            IdUpdateListener idUpdateListener )
    {
        this.indexActivator = new IndexActivator( indexUpdateListener );
        this.indexUpdatesSync = indexUpdatesSync;
        this.nodeStore = nodeStore;
        this.propertyStore = propertyStore;
        this.storageEngine = recordStorageEngine;
        this.schemaCache = schemaCache;
        this.cursorContext = cursorContext;
        this.memoryTracker = memoryTracker;
        this.idUpdateListener = idUpdateListener;
        this.lockGroup = new LockGroup();
    }

    @Override
    public LockGroup getLockGroup()
    {
        return lockGroup;
    }

    @Override
    public void close() throws Exception
    {
        applyPendingLabelAndIndexUpdates();

        IOUtils.closeAll( indexUpdates, idUpdateListener, lockGroup, indexActivator );
    }

    @Override
    public IndexActivator getIndexActivator()
    {
        return indexActivator;
    }

    @Override
    public void applyPendingLabelAndIndexUpdates() throws IOException
    {
        if ( indexUpdates != null && indexUpdates.hasUpdates() )
        {
            try
            {
                indexUpdatesSync.apply( new IndexUpdatesWork( indexUpdates, cursorContext ) );
            }
            catch ( ExecutionException e )
            {
                throw new IOException( "Failed to flush index updates", e );
            }
            finally
            {
                // close index updates and set to null, so subsequent index updates from the same batch start with fresh instance
                IOUtils.closeAll( indexUpdates );
                indexUpdates = null;
            }
        }
    }

    @Override
    public IndexUpdates indexUpdates()
    {
        if ( indexUpdates == null )
        {
            indexUpdates = new OnlineIndexUpdates( nodeStore, schemaCache, new PropertyPhysicalToLogicalConverter( propertyStore, cursorContext ),
                    storageEngine.newReader(), cursorContext, memoryTracker );
        }
        return indexUpdates;
    }

    @Override
    public IdUpdateListener getIdUpdateListener()
    {
        return idUpdateListener;
    }
}
