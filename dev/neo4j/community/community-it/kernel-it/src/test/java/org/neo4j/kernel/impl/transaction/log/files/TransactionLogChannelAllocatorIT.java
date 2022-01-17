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
package org.neo4j.kernel.impl.transaction.log.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.configuration.Config;
import org.neo4j.internal.nativeimpl.NativeAccessProvider;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.KernelVersion;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.impl.api.TestCommandReaderFactory;
import org.neo4j.kernel.impl.transaction.SimpleLogVersionRepository;
import org.neo4j.kernel.impl.transaction.log.LogHeaderCache;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogVersionedStoreChannel;
import org.neo4j.kernel.impl.transaction.log.entry.LogHeader;
import org.neo4j.kernel.impl.transaction.log.entry.VersionAwareLogEntryReader;
import org.neo4j.logging.NullLog;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.monitoring.Monitors;
import org.neo4j.monitoring.PanicEventGenerator;
import org.neo4j.storageengine.api.StoreId;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.testdirectory.TestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.neo4j.kernel.impl.transaction.log.entry.LogHeaderWriter.writeLogHeader;
import static org.neo4j.kernel.impl.transaction.log.entry.LogVersions.CURRENT_FORMAT_LOG_HEADER_SIZE;
import static org.neo4j.kernel.impl.transaction.log.entry.LogVersions.CURRENT_LOG_FORMAT_VERSION;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;

@TestDirectoryExtension
class TransactionLogChannelAllocatorIT
{
    private static final long ROTATION_THRESHOLD = ByteUnit.mebiBytes( 25 );
    @Inject
    private TestDirectory testDirectory;
    @Inject
    private FileSystemAbstraction fileSystem;
    private TransactionLogFilesHelper fileHelper;
    private TransactionLogChannelAllocator fileAllocator;

    @BeforeEach
    void setUp()
    {
        fileHelper = new TransactionLogFilesHelper( fileSystem, testDirectory.homePath() );
        fileAllocator = createLogFileAllocator();
    }

    @Test
    void rawChannelDoesNotTryToAdviseOnFileContent() throws IOException
    {
        Path path = fileHelper.getLogFileForVersion( 1 );
        try ( var storeChannel = fileSystem.write( path ) )
        {
            writeLogHeader( storeChannel, new LogHeader( CURRENT_LOG_FORMAT_VERSION, 1, 1, 1 ), INSTANCE );
        }

        var logHeaderCache = new LogHeaderCache( 10 );
        var logFileContext = createLogFileContext();
        var nativeChannelAccessor = new AdviseCountingChannelNativeAccessor();
        var channelAllocator = new TransactionLogChannelAllocator( logFileContext, fileHelper, logHeaderCache, nativeChannelAccessor );
        try ( var channel = channelAllocator.openLogChannel( 1, true ) )
        {
            assertEquals( 0, nativeChannelAccessor.getCallCounter() );
        }
    }

    @Test
    void defaultChannelTryToAdviseOnFileContent() throws IOException
    {
        Path path = fileHelper.getLogFileForVersion( 1 );
        try ( StoreChannel storeChannel = fileSystem.write( path ) )
        {
            writeLogHeader( storeChannel, new LogHeader( CURRENT_LOG_FORMAT_VERSION, 1, 1, 1 ), INSTANCE );
        }

        var logHeaderCache = new LogHeaderCache( 10 );
        var logFileContext = createLogFileContext();
        var nativeChannelAccessor = new AdviseCountingChannelNativeAccessor();
        var channelAllocator = new TransactionLogChannelAllocator( logFileContext, fileHelper, logHeaderCache, nativeChannelAccessor );
        try ( var channel = channelAllocator.openLogChannel( 1 ) )
        {
            assertEquals( 1, nativeChannelAccessor.getCallCounter() );
        }
    }

    @Test
    @EnabledOnOs( OS.LINUX )
    void allocateNewTransactionLogFile() throws IOException
    {
        PhysicalLogVersionedStoreChannel logChannel = fileAllocator.createLogChannel( 10, () -> 1L );
        assertEquals( ROTATION_THRESHOLD, logChannel.size() );
    }

    @Test
    @DisabledOnOs( OS.LINUX )
    void allocateNewTransactionLogFileOnSystemThatDoesNotSupportPreallocations() throws IOException
    {
        try ( PhysicalLogVersionedStoreChannel logChannel = fileAllocator.createLogChannel( 10, () -> 1L ) )
        {
            assertEquals( CURRENT_FORMAT_LOG_HEADER_SIZE, logChannel.size() );
        }
    }

    @Test
    void openExistingFileDoesNotPerformAnyAllocations() throws IOException
    {
        Path file = fileHelper.getLogFileForVersion( 11 );
        fileSystem.write( file ).close();

        TransactionLogChannelAllocator fileAllocator = createLogFileAllocator();
        try ( PhysicalLogVersionedStoreChannel channel = fileAllocator.createLogChannel( 11, () -> 1L ) )
        {
            assertEquals( CURRENT_FORMAT_LOG_HEADER_SIZE, channel.size() );
        }
    }

    private TransactionLogChannelAllocator createLogFileAllocator()
    {
        LogHeaderCache logHeaderCache = new LogHeaderCache( 10 );
        var logFileContext = createLogFileContext();
        var nativeChannelAccessor = new LogFileChannelNativeAccessor( fileSystem, logFileContext );
        return new TransactionLogChannelAllocator( logFileContext, fileHelper, logHeaderCache, nativeChannelAccessor );
    }

    private TransactionLogFilesContext createLogFileContext()
    {
        return new TransactionLogFilesContext( new AtomicLong( ROTATION_THRESHOLD ), new AtomicBoolean( true ),
                new VersionAwareLogEntryReader( new TestCommandReaderFactory() ), () -> 1L,
                () -> 1L, () -> new LogPosition( 0, 1 ),
                SimpleLogVersionRepository::new, fileSystem, NullLogProvider.getInstance(), DatabaseTracers.EMPTY, () -> StoreId.UNKNOWN,
                NativeAccessProvider.getNativeAccess(), INSTANCE, new Monitors(), true,
                new DatabaseHealth( PanicEventGenerator.NO_OP, NullLog.getInstance() ), () -> KernelVersion.LATEST,
                Clock.systemUTC(), Config.defaults() );
    }

    private static class AdviseCountingChannelNativeAccessor extends ChannelNativeAccessor.EmptyChannelNativeAccessor
    {
        private long callCounter;

        @Override
        public void adviseSequentialAccessAndKeepInCache( StoreChannel storeChannel, long version )
        {
            callCounter++;
        }

        public long getCallCounter()
        {
            return callCounter;
        }
    }
}
