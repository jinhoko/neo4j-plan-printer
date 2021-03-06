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
package org.neo4j.bolt.runtime.statemachine.impl;

import java.time.Clock;

import org.neo4j.bolt.messaging.BoltIOException;
import org.neo4j.bolt.runtime.BoltProtocolBreachFatality;
import org.neo4j.bolt.runtime.statemachine.StatementProcessor;
import org.neo4j.bolt.runtime.statemachine.StatementProcessorReleaseManager;
import org.neo4j.bolt.runtime.statemachine.TransactionStateMachineSPI;
import org.neo4j.bolt.runtime.statemachine.TransactionStateMachineSPIProvider;
import org.neo4j.bolt.security.auth.AuthenticationResult;
import org.neo4j.bolt.v41.messaging.RoutingContext;
import org.neo4j.memory.MemoryTracker;

public class StatementProcessorProvider
{
    private final Clock clock;
    private final AuthenticationResult authResult;
    private final TransactionStateMachineSPIProvider spiProvider;
    private final StatementProcessorReleaseManager resourceReleaseManger;
    private final RoutingContext routingContext;
    private final MemoryTracker memoryTracker;

    public StatementProcessorProvider( AuthenticationResult authResult, TransactionStateMachineSPIProvider transactionSpiProvider, Clock clock,
                                       StatementProcessorReleaseManager releaseManager, RoutingContext routingContext, MemoryTracker memoryTracker )
    {
        this.authResult = authResult;
        this.spiProvider = transactionSpiProvider;
        this.clock = clock;
        this.resourceReleaseManger = releaseManager;
        this.routingContext = routingContext;
        this.memoryTracker = memoryTracker;
    }

    public StatementProcessor getStatementProcessor( String databaseName ) throws BoltProtocolBreachFatality, BoltIOException
    {
        memoryTracker.allocateHeap( TransactionStateMachine.SHALLOW_SIZE );

        TransactionStateMachineSPI transactionSPI = spiProvider.getTransactionStateMachineSPI( databaseName, resourceReleaseManger );
        return new TransactionStateMachine( databaseName, transactionSPI, authResult, clock, routingContext );
    }

    public void releaseStatementProcessor()
    {
        memoryTracker.releaseHeap( TransactionStateMachine.SHALLOW_SIZE );

        spiProvider.releaseTransactionStateMachineSPI();
    }
}
