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

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.neo4j.server.rest.repr.formats.JsonFormat;
import org.neo4j.service.Services;

public final class RepresentationFormatRepository
{
    private final Map<MediaType, RepresentationFormat> formats;

    public RepresentationFormatRepository()
    {
        this.formats = new HashMap<>();
        for ( RepresentationFormat format : Services.loadAll( RepresentationFormat.class ) )
        {
            formats.put( format.mediaType, format );
        }
    }

    public OutputFormat outputFormat( List<MediaType> acceptable, URI baseUri,
            MultivaluedMap<String,String> requestHeaders )
    {
        RepresentationFormat format = forHeaders( acceptable, requestHeaders );
        if ( format == null )
        {
            format = forMediaTypes( acceptable );
        }
        if ( format == null )
        {
            format = useDefault( acceptable );
        }
        return new OutputFormat( format, baseUri );
    }

    private RepresentationFormat forHeaders( List<MediaType> acceptable, MultivaluedMap<String,String> requestHeaders )
    {
        if ( requestHeaders == null )
        {
            return null;
        }
        if ( !containsType( acceptable, MediaType.APPLICATION_JSON_TYPE ) )
        {
            return null;
        }
        String streamHeader = requestHeaders.getFirst( StreamingFormat.STREAM_HEADER );
        if ( "true".equalsIgnoreCase( streamHeader ) )
        {
            return formats.get( StreamingFormat.MEDIA_TYPE );
        }
        return null;
    }

    private static boolean containsType( List<MediaType> mediaTypes, MediaType mediaType )
    {
        for ( MediaType type : mediaTypes )
        {
            if ( mediaType.getType().equals( type.getType() ) && mediaType.getSubtype().equals( type.getSubtype() ) )
            {
                return true;
            }
        }
        return false;
    }

    private RepresentationFormat forMediaTypes( List<MediaType> acceptable )
    {
        for ( MediaType type : acceptable )
        {
            RepresentationFormat format = formats.get( type );
            if ( format != null )
            {
                return format;
            }
        }
        return null;
    }

    public InputFormat inputFormat( MediaType type )
    {
        if ( type == null )
        {
            return useDefault();
        }

        RepresentationFormat format = formats.get( type );
        if ( format != null )
        {
            return format;
        }

        format = formats.get( new MediaType( type.getType(), type.getSubtype() ) );
        if ( format != null )
        {
            return format;
        }

        return useDefault( type );
    }

    private DefaultFormat useDefault( final List<MediaType> acceptable )
    {
        return useDefault( acceptable.toArray( new MediaType[0] ) );
    }

    private DefaultFormat useDefault( final MediaType... type )
    {
        return new DefaultFormat( new JsonFormat(), formats.keySet(), type );
    }
}
