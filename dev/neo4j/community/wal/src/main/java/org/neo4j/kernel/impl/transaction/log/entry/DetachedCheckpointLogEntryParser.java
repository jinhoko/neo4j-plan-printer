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
package org.neo4j.kernel.impl.transaction.log.entry;

import java.io.IOException;

import org.neo4j.io.fs.ReadableChecksumChannel;
import org.neo4j.kernel.KernelVersion;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogPositionMarker;
import org.neo4j.storageengine.api.CommandReaderFactory;
import org.neo4j.storageengine.api.StoreId;

import static java.nio.charset.StandardCharsets.UTF_8;

class DetachedCheckpointLogEntryParser extends LogEntryParser
{
    public static final int MAX_DESCRIPTION_LENGTH = 120;

    DetachedCheckpointLogEntryParser()
    {
        super( LogEntryTypeCodes.DETACHED_CHECK_POINT );
    }

    @Override
    LogEntry parse( KernelVersion version, ReadableChecksumChannel channel, LogPositionMarker marker, CommandReaderFactory commandReaderFactory )
            throws IOException
    {
        long logVersion = channel.getLong();
        long byteOffset = channel.getLong();
        long checkpointTimeMillis = channel.getLong();
        StoreId storeId = new StoreId( channel.getLong(), channel.getLong(), channel.getLong(), channel.getLong(), channel.getLong() );
        short reasonBytesLength = channel.getShort();
        byte[] bytes = new byte[MAX_DESCRIPTION_LENGTH];
        channel.get( bytes, MAX_DESCRIPTION_LENGTH );
        String reason = new String( bytes, 0, reasonBytesLength, UTF_8 );
        channel.endChecksumAndValidate();
        return new LogEntryDetachedCheckpoint( version, new LogPosition( logVersion, byteOffset ), checkpointTimeMillis, storeId, reason );
    }
}
