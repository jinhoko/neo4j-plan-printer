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
package org.neo4j.kernel.recovery;

import java.io.IOException;

import org.neo4j.configuration.Config;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.memory.MemoryTracker;

import static org.neo4j.kernel.recovery.Recovery.performRecovery;

public class RecoveryFacade
{
    private final FileSystemAbstraction fs;
    private final PageCache pageCache;
    private final DatabaseTracers tracers;
    private final Config config;
    private final MemoryTracker memoryTracker;

    RecoveryFacade( FileSystemAbstraction fs, PageCache pageCache, DatabaseTracers tracers, Config config, MemoryTracker memoryTracker )
    {
        this.fs = fs;
        this.pageCache = pageCache;
        this.tracers = tracers;
        this.config = config;
        this.memoryTracker = memoryTracker;
    }

    public void recovery( DatabaseLayout databaseLayout ) throws IOException
    {
        performRecovery( fs, pageCache, tracers, config, databaseLayout, memoryTracker );
    }
}
