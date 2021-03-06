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
package org.neo4j.kernel.impl.store;

import org.eclipse.collections.api.set.ImmutableSet;

import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.neo4j.collection.trackable.HeapTrackingCollections;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.helpers.DatabaseReadOnlyChecker;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.id.IdType;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.store.format.RecordFormat;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.impl.store.record.TokenRecord;
import org.neo4j.logging.LogProvider;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.token.api.NamedToken;

import static org.neo4j.kernel.impl.store.NoStoreHeaderFormat.NO_STORE_HEADER_FORMAT;
import static org.neo4j.kernel.impl.store.PropertyStore.decodeString;
import static org.neo4j.kernel.impl.store.record.RecordLoad.NORMAL;

public abstract class TokenStore<RECORD extends TokenRecord>
        extends CommonAbstractStore<RECORD,NoStoreHeader>
{
    public static final int NAME_STORE_BLOCK_SIZE = 30;

    private final DynamicStringStore nameStore;

    public TokenStore(
            Path path,
            Path idFile,
            Config configuration,
            IdType idType,
            IdGeneratorFactory idGeneratorFactory,
            PageCache pageCache,
            LogProvider logProvider,
            DynamicStringStore nameStore,
            String typeDescriptor,
            RecordFormat<RECORD> recordFormat,
            String storeVersion,
            DatabaseReadOnlyChecker readOnlyChecker,
            String databaseName,
            ImmutableSet<OpenOption> openOptions )
    {
        super( path, idFile, configuration, idType, idGeneratorFactory, pageCache, logProvider, typeDescriptor,
                recordFormat, NO_STORE_HEADER_FORMAT, storeVersion, readOnlyChecker, databaseName, openOptions );
        this.nameStore = nameStore;
    }

    public DynamicStringStore getNameStore()
    {
        return nameStore;
    }

    public List<NamedToken> getTokens( CursorContext cursorContext )
    {
        return readAllTokens( false, cursorContext );
    }

    /**
     * Same as {@link #getTokens(CursorContext)}, except tokens that cannot be read due to inconsistencies will just be ignored,
     * while {@link #getTokens(CursorContext)} would throw an exception in such cases.
     * @return All tokens that could be read without any apparent problems.
     */
    public List<NamedToken> getAllReadableTokens( CursorContext cursorContext )
    {
        return readAllTokens( true, cursorContext );
    }

    private List<NamedToken> readAllTokens( boolean ignoreInconsistentTokens, CursorContext cursorContext )
    {
        long highId = getHighId();
        ArrayList<NamedToken> records = new ArrayList<>();
        records.ensureCapacity( Math.toIntExact( highId ) );
        RECORD record = newRecord();
        for ( int i = 0; i < highId; i++ )
        {
            if ( !getRecord( i, record, RecordLoad.LENIENT_CHECK, cursorContext ).inUse() )
            {
                continue;
            }

            if ( record.getNameId() != Record.RESERVED.intValue() )
            {
                try
                {
                    String name = getStringFor( record, cursorContext );
                    records.add( new NamedToken( name, i, record.isInternal() ) );
                }
                catch ( Exception e )
                {
                    if ( !ignoreInconsistentTokens )
                    {
                        throw e;
                    }
                }
            }
        }
        return records;
    }

    public NamedToken getToken( int id, CursorContext cursorContext )
    {
        RECORD record = getRecord( id, newRecord(), NORMAL, cursorContext );
        return new NamedToken( getStringFor( record, cursorContext ), record.getIntId(), record.isInternal() );
    }

    public Collection<DynamicRecord> allocateNameRecords( byte[] chars, CursorContext cursorContext, MemoryTracker memoryTracker )
    {
        Collection<DynamicRecord> records = HeapTrackingCollections.newArrayList( memoryTracker );
        nameStore.allocateRecordsFromBytes( records, chars, cursorContext, memoryTracker );
        return records;
    }

    @Override
    public void updateRecord( RECORD record, IdUpdateListener idUpdateListener, PageCursor cursor, CursorContext cursorContext )
    {
        super.updateRecord( record, idUpdateListener, cursor, cursorContext );
        if ( !record.isLight() )
        {
            for ( DynamicRecord keyRecord : record.getNameRecords() )
            {
                nameStore.updateRecord( keyRecord, idUpdateListener, cursorContext );
            }
        }
    }

    @Override
    public void ensureHeavy( RECORD record, CursorContext cursorContext )
    {
        if ( !record.isLight() )
        {
            return;
        }

        // Guard for cycles in the name chain, since this might be called by the consistency checker on an inconsistent store.
        // This will throw an exception if there's a cycle, and we'll just ignore those tokens at this point.
        record.addNameRecords( nameStore.getRecords( record.getNameId(), NORMAL, true, cursorContext ) );
    }

    public String getStringFor( RECORD nameRecord, CursorContext cursorContext )
    {
        ensureHeavy( nameRecord, cursorContext );
        int recordToFind = nameRecord.getNameId();
        Iterator<DynamicRecord> records = nameRecord.getNameRecords().iterator();
        Collection<DynamicRecord> relevantRecords = new ArrayList<>();
        while ( recordToFind != Record.NO_NEXT_BLOCK.intValue() &&  records.hasNext() )
        {
            DynamicRecord record = records.next();
            if ( record.inUse() && record.getId() == recordToFind )
            {
                recordToFind = (int) record.getNextBlock();
                relevantRecords.add( record );
                records = nameRecord.getNameRecords().iterator();
            }
        }
        return decodeString( nameStore.readFullByteArray( relevantRecords, PropertyType.STRING, cursorContext ).other() );
    }
}
