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
package org.neo4j.internal.kernel.api.security;

import java.util.function.Supplier;

import org.neo4j.internal.kernel.api.RelTypeSupplier;
import org.neo4j.internal.kernel.api.TokenSet;

public class TestAccessMode implements AccessMode
{
    private final boolean allowRead;
    private final boolean allowReadAll;
    private final boolean allowWrite;
    private final boolean allowSchema;

    public TestAccessMode( boolean allowRead, boolean allowReadAll, boolean allowWrite, boolean allowSchema )
    {
        this.allowRead = allowRead;
        this.allowReadAll = allowReadAll;
        this.allowWrite = allowWrite;
        this.allowSchema = allowSchema;
    }

    @Override
    public boolean allowsWrites()
    {
        return allowWrite;
    }

    @Override
    public boolean allowsTokenCreates( PrivilegeAction action )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsSchemaWrites()
    {
        return allowSchema;
    }

    @Override
    public boolean allowsSchemaWrites( PrivilegeAction action )
    {
        return allowSchema;
    }

    @Override
    public boolean allowsShowIndex()
    {
        return allowSchema;
    }

    @Override
    public boolean allowsShowConstraint()
    {
        return allowSchema;
    }

    @Override
    public boolean allowsTraverseAllLabels()
    {
        return allowReadAll;
    }

    @Override
    public boolean allowsTraverseAllNodesWithLabel( long label )
    {
        return allowReadAll;
    }

    @Override
    public boolean disallowsTraverseLabel( long label )
    {
        return !allowRead;
    }

    @Override
    public boolean allowsTraverseNode( long... labels )
    {
        return allowRead;
    }

    @Override
    public boolean allowsTraverseAllRelTypes()
    {
        return allowReadAll;
    }

    @Override
    public boolean allowsTraverseRelType( int relType )
    {
        return allowRead;
    }

    @Override
    public boolean disallowsTraverseRelType( int relType )
    {
        return !allowRead;
    }

    @Override
    public boolean allowsReadPropertyAllLabels( int propertyKey )
    {
        return allowReadAll;
    }

    @Override
    public boolean disallowsReadPropertyForSomeLabel( int propertyKey )
    {
        return !allowReadAll;
    }

    @Override
    public boolean allowsReadNodeProperty( Supplier<TokenSet> labels, int propertyKey )
    {
        return allowRead;
    }

    @Override
    public boolean allowsReadPropertyAllRelTypes( int propertyKey )
    {
        return allowReadAll;
    }

    @Override
    public boolean allowsReadRelationshipProperty( RelTypeSupplier relType, int propertyKey )
    {
        return allowRead;
    }

    @Override
    public boolean allowsSeePropertyKeyToken( int propertyKey )
    {
        return allowRead;
    }

    @Override
    public boolean shouldBoostAccessForProcedureWith( String[] allowed )
    {
        return false;
    }

    @Override
    public boolean allowsExecuteProcedure( int procedureId )
    {
        return true;
    }

    @Override
    public boolean shouldBoostProcedure( int procedureId )
    {
        return false;
    }

    @Override
    public boolean allowsExecuteFunction( int id )
    {
        return true;
    }

    @Override
    public boolean shouldBoostFunction( int id )
    {
        return false;
    }

    @Override
    public boolean allowsExecuteAggregatingFunction( int id )
    {
        return true;
    }

    @Override
    public boolean shouldBoostAggregatingFunction( int id )
    {
        return false;
    }

    @Override
    public boolean allowsSetLabel( long labelId )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsRemoveLabel( long labelId )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsCreateNode( int[] labelIds )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsDeleteNode( Supplier<TokenSet> labelSupplier )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsCreateRelationship( int relType )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsDeleteRelationship( int relType )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsSetProperty( Supplier<TokenSet> labels, int propertyKey )
    {
        return allowWrite;
    }

    @Override
    public boolean allowsSetProperty( RelTypeSupplier relType, int propertyKey )
    {
        return allowWrite;
    }

    @Override
    public String name()
    {
        return "Test";
    }
}
