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
package org.neo4j.server.web.logging;

import org.eclipse.jetty.io.ByteBufferPool;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.kernel.api.net.NetworkConnectionTracker;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.server.web.Jetty9WebServer;
import org.neo4j.test.rule.ImpermanentDbmsRule;
import org.neo4j.test.rule.SuppressOutput;
import org.neo4j.test.server.ExclusiveWebContainerTestBase;

import static org.mockito.Mockito.mock;
import static org.neo4j.test.rule.SuppressOutput.suppressAll;

public class Jetty9WebServerIT extends ExclusiveWebContainerTestBase
{
    @Rule
    public SuppressOutput suppressOutput = suppressAll();
    @Rule
    public ImpermanentDbmsRule dbRule = new ImpermanentDbmsRule();

    private Jetty9WebServer webServer;

    @Test
    public void shouldBeAbleToUsePortZero() throws Exception
    {
        // Given
        webServer = new Jetty9WebServer( NullLogProvider.getInstance(), Config.defaults(), NetworkConnectionTracker.NO_OP, mock( ByteBufferPool.class ) );

        webServer.setHttpAddress( new SocketAddress( "localhost", 0 ) );

        // When
        webServer.start();

        // Then no exception
    }

    @Test
    public void shouldBeAbleToRestart() throws Throwable
    {
        // given
        webServer = new Jetty9WebServer( NullLogProvider.getInstance(), Config.defaults(), NetworkConnectionTracker.NO_OP, mock( ByteBufferPool.class ) );
        webServer.setHttpAddress( new SocketAddress( "127.0.0.1", 7878 ) );

        // when
        webServer.start();
        webServer.stop();
        webServer.start();

        // then no exception
    }

    @Test
    public void shouldStopCleanlyEvenWhenItHasntBeenStarted()
    {
        new Jetty9WebServer( NullLogProvider.getInstance(), Config.defaults(), NetworkConnectionTracker.NO_OP, mock( ByteBufferPool.class ) ).stop();
    }

    @After
    public void cleanup()
    {
        if ( webServer != null )
        {
            webServer.stop();
        }
    }

}
