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
package org.neo4j.kernel.impl.store;

import java.util.Objects;
import java.util.Optional;

import org.neo4j.internal.id.IdType;
import org.neo4j.io.layout.DatabaseFile;
import org.neo4j.io.pagecache.context.CursorContext;

public enum StoreType
{
    NODE_LABEL( DatabaseFile.NODE_LABEL_STORE, IdType.NODE_LABELS )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createNodeLabelStore( cursorContext );
                }
            },
    NODE( DatabaseFile.NODE_STORE, IdType.NODE )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createNodeStore( cursorContext );
                }
            },
    PROPERTY_KEY_TOKEN_NAME( DatabaseFile.PROPERTY_KEY_TOKEN_NAMES_STORE, IdType.PROPERTY_KEY_TOKEN_NAME )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createPropertyKeyTokenNamesStore( cursorContext );
                }
            },
    PROPERTY_KEY_TOKEN( DatabaseFile.PROPERTY_KEY_TOKEN_STORE, IdType.PROPERTY_KEY_TOKEN )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createPropertyKeyTokenStore( cursorContext );
                }
            },
    PROPERTY_STRING( DatabaseFile.PROPERTY_STRING_STORE, IdType.STRING_BLOCK )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createPropertyStringStore( cursorContext );
                }
            },
    PROPERTY_ARRAY( DatabaseFile.PROPERTY_ARRAY_STORE, IdType.ARRAY_BLOCK )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createPropertyArrayStore( cursorContext );
                }
            },
    PROPERTY( DatabaseFile.PROPERTY_STORE, IdType.PROPERTY )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createPropertyStore( cursorContext );
                }
            },
    RELATIONSHIP( DatabaseFile.RELATIONSHIP_STORE, IdType.RELATIONSHIP )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createRelationshipStore( cursorContext );
                }
            },
    RELATIONSHIP_TYPE_TOKEN_NAME( DatabaseFile.RELATIONSHIP_TYPE_TOKEN_NAMES_STORE, IdType.RELATIONSHIP_TYPE_TOKEN_NAME )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createRelationshipTypeTokenNamesStore( cursorContext );
                }
            },
    RELATIONSHIP_TYPE_TOKEN( DatabaseFile.RELATIONSHIP_TYPE_TOKEN_STORE, IdType.RELATIONSHIP_TYPE_TOKEN )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createRelationshipTypeTokenStore( cursorContext );
                }
            },
    LABEL_TOKEN_NAME( DatabaseFile.LABEL_TOKEN_NAMES_STORE, IdType.LABEL_TOKEN_NAME )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createLabelTokenNamesStore( cursorContext );
                }
            },
    LABEL_TOKEN( DatabaseFile.LABEL_TOKEN_STORE, IdType.LABEL_TOKEN )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createLabelTokenStore( cursorContext );
                }
            },
    SCHEMA( DatabaseFile.SCHEMA_STORE, IdType.SCHEMA )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createSchemaStore( cursorContext );
                }
            },
    RELATIONSHIP_GROUP( DatabaseFile.RELATIONSHIP_GROUP_STORE, IdType.RELATIONSHIP_GROUP )
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createRelationshipGroupStore( cursorContext );
                }
            },
    META_DATA( DatabaseFile.METADATA_STORE, IdType.NODE ) // Make sure this META store is last
            {
                @Override
                public CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext )
                {
                    return neoStores.createMetadataStore( cursorContext );
                }
            };

    private final DatabaseFile databaseFile;
    private final IdType idType;

    StoreType( DatabaseFile databaseFile, IdType idType )
    {
        this.databaseFile = databaseFile;
        this.idType = idType;
    }

    abstract CommonAbstractStore open( NeoStores neoStores, CursorContext cursorContext );

    public DatabaseFile getDatabaseFile()
    {
        return databaseFile;
    }

    public IdType getIdType()
    {
        return idType;
    }

    /**
     * Determine type of a store base on provided database file.
     *
     * @param databaseFile - database file to map
     * @return an {@link Optional} that wraps the matching store type of the specified file,
     * or {@link Optional#empty()} if the given file name does not match any store files.
     */
    public static Optional<StoreType> typeOf( DatabaseFile databaseFile )
    {
        Objects.requireNonNull( databaseFile );
        StoreType[] values = StoreType.values();
        for ( StoreType value : values )
        {
            if ( value.getDatabaseFile().equals( databaseFile ) )
            {
                return Optional.of( value );
            }
        }
        return Optional.empty();
    }
}
