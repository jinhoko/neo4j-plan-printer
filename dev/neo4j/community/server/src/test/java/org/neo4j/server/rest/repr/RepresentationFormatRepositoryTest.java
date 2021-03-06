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
package org.neo4j.server.rest.repr;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.internal.helpers.collection.MapUtil.map;

public class RepresentationFormatRepositoryTest
{
    private final RepresentationFormatRepository repository = new RepresentationFormatRepository();

    @Test
    public void canProvideJsonFormat()
    {
        assertNotNull( repository.inputFormat( MediaType.valueOf( "application/json" ) ) );
    }

    @Test
    public void canProvideUTF8EncodedJsonFormat()
    {
        assertNotNull( repository.inputFormat( MediaType.valueOf( "application/json;charset=UTF-8" ) ) );
    }

    @Test
    public void canNotGetInputFormatBasedOnWildcardMediaType()
    {
        InputFormat format = repository.inputFormat( MediaType.WILDCARD_TYPE );
        assertThrows( MediaTypeNotSupportedException.class, () -> format.readValue( "foo" ) );
    }

    @Test
    public void canProvideJsonOutputFormat()
    {
        OutputFormat format = repository.outputFormat( singletonList( MediaType.APPLICATION_JSON_TYPE ), null, null );
        assertNotNull( format );
        assertEquals( "\"test\"", format.assemble( ValueRepresentation.string( "test" ) ) );
    }

    @Test
    public void cannotProvideStreamingForOtherMediaTypes() throws Exception
    {
        final Response.ResponseBuilder responseBuilder = mock( Response.ResponseBuilder.class );
        // no streaming
        when( responseBuilder.entity( any(byte[].class) ) ).thenReturn( responseBuilder );
        verify( responseBuilder, never() ).entity( isA( StreamingOutput.class ) );
        when( responseBuilder.type( ArgumentMatchers.<MediaType>any() ) ).thenReturn( responseBuilder );
        when( responseBuilder.build() ).thenReturn( null );
        OutputFormat format = repository.outputFormat( singletonList( MediaType.TEXT_HTML_TYPE ),
                new URI( "http://some.host" ), streamingHeader() );
        assertNotNull( format );
        format.response( responseBuilder, new ExceptionRepresentation( new RuntimeException() ) );
    }

    @Test
    public void canProvideStreamingJsonOutputFormat() throws Exception
    {
        Response response = mock( Response.class );
        final AtomicReference<StreamingOutput> ref = new AtomicReference<>();
        final Response.ResponseBuilder responseBuilder = mockResponseBuilder( response, ref );
        OutputFormat format = repository.outputFormat( singletonList( MediaType.APPLICATION_JSON_TYPE ), null,
                streamingHeader() );
        assertNotNull( format );
        Response returnedResponse = format.response( responseBuilder, new MapRepresentation( map( "a", "test" ) ) );
        assertSame( response, returnedResponse );
        StreamingOutput streamingOutput = ref.get();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        streamingOutput.write( baos );
        assertEquals( "{\"a\":\"test\"}", baos.toString() );
    }

    private static Response.ResponseBuilder mockResponseBuilder( Response response, final AtomicReference<StreamingOutput> ref )
    {
        final Response.ResponseBuilder responseBuilder = mock( Response.ResponseBuilder.class );
        when( responseBuilder.entity( isA( StreamingOutput.class ) ) ).thenAnswer(
                invocationOnMock ->
                {
                    ref.set( invocationOnMock.getArgument( 0 ) );
                    return responseBuilder;
                } );
        when( responseBuilder.type( ArgumentMatchers.<MediaType>any() ) ).thenReturn( responseBuilder );
        when( responseBuilder.build() ).thenReturn( response );
        return responseBuilder;
    }

    @SuppressWarnings( "unchecked" )
    private static MultivaluedMap<String, String> streamingHeader()
    {
        MultivaluedMap<String, String> headers = mock( MultivaluedMap.class );
        when( headers.getFirst( StreamingFormat.STREAM_HEADER ) ).thenReturn( "true" );
        return headers;
    }
}
