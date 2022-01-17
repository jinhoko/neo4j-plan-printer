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
package org.neo4j.internal.batchimport.staging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.internal.batchimport.executor.ProcessorScheduler;
import org.neo4j.internal.batchimport.stats.Keys;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.RandomExtension;
import org.neo4j.test.rule.RandomRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.internal.batchimport.Configuration.DEFAULT;
import static org.neo4j.internal.batchimport.staging.ExecutionMonitor.INVISIBLE;
import static org.neo4j.internal.batchimport.staging.ExecutionSupervisors.superviseDynamicExecution;
import static org.neo4j.io.pagecache.tracing.PageCacheTracer.NULL;

@ExtendWith( RandomExtension.class )
class StageTest
{
    @Inject
    private RandomRule random;

    @Test
    void shouldReceiveBatchesInOrder()
    {
        // GIVEN
        Configuration config = new Configuration.Overridden( DEFAULT )
        {
            @Override
            public int batchSize()
            {
                return 10;
            }
        };
        Stage stage = new Stage( "Test stage", null, config, Step.ORDER_SEND_DOWNSTREAM );
        long batches = 1000;
        final long items = batches * config.batchSize();
        stage.add( new PullingProducerStep( stage.control(), config )
        {
            private final Object theObject = new Object();
            private long i;

            @Override
            protected Object nextBatchOrNull( long ticket, int batchSize )
            {
                if ( i >= items )
                {
                    return null;
                }

                Object[] batch = new Object[batchSize];
                Arrays.fill( batch, theObject );
                i += batchSize;
                return batch;
            }

            @Override
            protected long position()
            {
                return 0;
            }
        } );

        for ( int i = 0; i < 3; i++ )
        {
            stage.add( new ReceiveOrderAssertingStep( stage.control(), "Step" + i, config, i, false ) );
        }
        stage.add( new ReceiveOrderAssertingStep( stage.control(), "Final step", config, 0, true ) );

        // WHEN
        StageExecution execution = stage.execute();
        for ( Step<?> step : execution.steps() )
        {
            // we start off with two in each step
            step.processors( 1 );
        }
        new ExecutionSupervisor( INVISIBLE ).supervise( execution );

        // THEN
        for ( Step<?> step : execution.steps() )
        {
            assertEquals( batches, step.stats().stat( Keys.done_batches ).asLong(), "For " + step );
        }
        stage.close();
    }

    @Test
    void shouldCloseOnPanic()
    {
        // given
        // a producer, a processor, a forked processor and a final step
        Configuration configuration = DEFAULT;
        TrackingPanicMonitor panicMonitor = new TrackingPanicMonitor();
        Stage stage = new Stage( "test close on panic", null, configuration, random.nextBoolean() ? Step.ORDER_SEND_DOWNSTREAM : 0,
                ProcessorScheduler.SPAWN_THREAD, panicMonitor )
        {
            {
                // Producer
                add( new PullingProducerStep( control(), configuration )
                {
                    private volatile long ticket;
                    private final ChaosMonkey chaosMonkey = new ChaosMonkey();

                    @Override
                    protected Object nextBatchOrNull( long ticket, int batchSize )
                    {
                        chaosMonkey.makeChaos();
                        this.ticket = ticket;
                        return new int[batchSize];
                    }

                    @Override
                    protected long position()
                    {
                        return ticket;
                    }
                } );

                // Processor
                add( new ProcessorStep<>( control(), "processor", configuration, 2, NULL )
                {
                    private final ChaosMonkey chaosMonkey = new ChaosMonkey();

                    @Override
                    protected void process( Object batch, BatchSender sender, CursorContext cursorContext )
                    {
                        chaosMonkey.makeChaos();
                        sender.send( batch );
                    }
                } );

                // Forked processor
                add( new ForkedProcessorStep<>( control(), "forked processor", configuration )
                {
                    private final ChaosMonkey chaosMonkey = new ChaosMonkey();

                    @Override
                    protected void forkedProcess( int id, int processors, Object batch )
                    {
                        chaosMonkey.makeChaos();
                    }
                } );

                // Final consumer
                add( new ProcessorStep<>( control(), "consumer", configuration, 1, NULL )
                {
                    private final ChaosMonkey chaosMonkey = new ChaosMonkey();

                    @Override
                    protected void process( Object batch, BatchSender sender, CursorContext cursorContext ) throws Throwable
                    {
                        chaosMonkey.makeChaos();
                        // don't pass the batch further, i.e. end of the line
                    }
                } );
            }
        };

        // when/then
        assertThrows( RuntimeException.class, () -> superviseDynamicExecution( stage ) );
        assertTrue( panicMonitor.hasReceivedPanic() );
        assertTrue( panicMonitor.getReceivedPanic().getMessage().contains( "Chaos monkey" ) );
    }

    private static class ReceiveOrderAssertingStep extends ProcessorStep<Object>
    {
        private final AtomicLong lastTicket = new AtomicLong();
        private final long processingTime;
        private final boolean endOfLine;

        ReceiveOrderAssertingStep( StageControl control, String name, Configuration config,
                long processingTime, boolean endOfLine )
        {
            super( control, name, config, 1, NULL );
            this.processingTime = processingTime;
            this.endOfLine = endOfLine;
        }

        @Override
        public long receive( long ticket, Object batch )
        {
            assertEquals( lastTicket.getAndIncrement(), ticket, "For " + batch + " in " + name() );
            return super.receive( ticket, batch );
        }

        @Override
        protected void process( Object batch, BatchSender sender, CursorContext cursorContext )
        {
            try
            {
                Thread.sleep( processingTime );
            }
            catch ( InterruptedException e )
            {
                throw new RuntimeException( e );
            }

            if ( !endOfLine )
            {
                sender.send( batch );
            }
        }
    }

    private class ChaosMonkey
    {
        private final int performance = random.nextInt( 0, 50 );

        void makeChaos()
        {
            try
            {
                Thread.sleep( performance );
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
            }

            if ( ThreadLocalRandom.current().nextFloat() < 0.01 )
            {
                throw new RuntimeException( "Chaos monkey causing failure" );
            }
        }
    }
}
