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
package org.neo4j.shell.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VersionsTest
{
    @Test
    public void shouldWorkForEmptyString() throws Exception
    {
        assertEquals( 0, Versions.version( "" ).compareTo( Versions.version( "0.0.0" ) ) );
        assertEquals( 0, Versions.majorVersion( "" ) );
        assertEquals( 0, Versions.minorVersion( "" ) );
        assertEquals( 0, Versions.patch( "" ) );
    }

    @Test
    public void shouldWorkForReleaseVersion() throws Exception
    {
        String versionString = "3.4.5";
        assertEquals( 0, Versions.version( versionString ).compareTo( Versions.version( "3.4.5" ) ) );
        assertEquals( 3, Versions.majorVersion( versionString ) );
        assertEquals( 4, Versions.minorVersion( versionString ) );
        assertEquals( 5, Versions.patch( versionString ) );
    }

    @Test
    public void shouldWorkForPreReleaseVersion() throws Exception
    {
        String versionString = "3.4.55-beta99";
        assertEquals( 0, Versions.version( versionString ).compareTo( Versions.version( "3.4.55" ) ) );
        assertEquals( 3, Versions.majorVersion( versionString ) );
        assertEquals( 4, Versions.minorVersion( versionString ) );
        assertEquals( 55, Versions.patch( versionString ) );
    }
}
