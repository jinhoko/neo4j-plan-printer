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
package org.neo4j.bolt.v3.runtime;

import org.neo4j.bolt.messaging.BoltIOException;
import org.neo4j.bolt.messaging.RequestMessage;
import org.neo4j.bolt.runtime.BoltProtocolBreachFatality;
import org.neo4j.bolt.runtime.statemachine.BoltStateMachineState;
import org.neo4j.bolt.runtime.statemachine.StateMachineContext;
import org.neo4j.bolt.runtime.statemachine.StatementMetadata;
import org.neo4j.bolt.runtime.statemachine.StatementProcessor;
import org.neo4j.bolt.v3.messaging.request.BeginMessage;
import org.neo4j.bolt.v3.messaging.request.RunMessage;
import org.neo4j.bolt.v3.messaging.request.TransactionInitiatingMessage;
import org.neo4j.memory.HeapEstimator;
import org.neo4j.values.storable.Values;

import static org.neo4j.bolt.v4.messaging.MessageMetadataParser.ABSENT_DB_NAME;
import static org.neo4j.util.Preconditions.checkState;
import static org.neo4j.values.storable.Values.stringArray;

/**
 * The READY state indicates that the connection is ready to accept a
 * new RUN request. This is the "normal" state for a connection and
 * becomes available after successful authorisation and when not
 * executing another statement. It is this that ensures that statements
 * must be executed in series and each must wait for the previous
 * statement to complete.
 */
public class ReadyState extends FailSafeBoltStateMachineState
{
    public static final long SHALLOW_SIZE = HeapEstimator.shallowSizeOfInstance( ReadyState.class );

    private BoltStateMachineState streamingState;
    private BoltStateMachineState txReadyState;

    public static final String FIELDS_KEY = "fields";
    public static final String FIRST_RECORD_AVAILABLE_KEY = "t_first";

    @Override
    public BoltStateMachineState processUnsafe( RequestMessage message, StateMachineContext context ) throws Exception
    {
        if ( message instanceof RunMessage )
        {
            return processRunMessage( (RunMessage) message, context );
        }
        if ( message instanceof BeginMessage )
        {
            return processBeginMessage( (BeginMessage) message, context );
        }
        return null;
    }

    @Override
    public String name()
    {
        return "READY";
    }

    public void setStreamingState( BoltStateMachineState streamingState )
    {
        this.streamingState = streamingState;
    }

    public void setTransactionReadyState( BoltStateMachineState txReadyState )
    {
        this.txReadyState = txReadyState;
    }

    private BoltStateMachineState processRunMessage( RunMessage message, StateMachineContext context ) throws Exception
    {
        long start = context.clock().millis();
        StatementProcessor statementProcessor = getStatementProcessor( message, context );
        StatementMetadata statementMetadata = statementProcessor.run( message.statement(), message.params(), message.bookmarks(), message.transactionTimeout(),
                message.getAccessMode(), message.transactionMetadata() );
        long end = context.clock().millis();

        context.connectionState().onMetadata( FIELDS_KEY, stringArray( statementMetadata.fieldNames() ) );
        context.connectionState().onMetadata( FIRST_RECORD_AVAILABLE_KEY, Values.longValue( end - start ) );

        return streamingState;
    }

    private BoltStateMachineState processBeginMessage( BeginMessage message, StateMachineContext context ) throws Exception
    {
        StatementProcessor statementProcessor = getStatementProcessor( message, context );
        statementProcessor.beginTransaction( message.bookmarks(), message.transactionTimeout(), message.getAccessMode(), message.transactionMetadata() );
        return txReadyState;
    }

    @Override
    protected void assertInitialized()
    {
        checkState( streamingState != null, "Streaming state not set" );
        checkState( txReadyState != null, "TransactionReady state not set" );
        super.assertInitialized();
    }

    protected StatementProcessor getStatementProcessor( TransactionInitiatingMessage message, StateMachineContext context )
            throws BoltProtocolBreachFatality, BoltIOException
    {
        return context.setCurrentStatementProcessorForDatabase( ABSENT_DB_NAME );
    }
}
