/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ast;

import java.util.List;

import org.jruby.Ruby;
import org.jruby.ast.types.INameNode;
import org.jruby.ast.visitor.NodeVisitor;
import org.jruby.compiler.ASTInspector;
import org.jruby.evaluator.Instruction;
import org.jruby.lexer.yacc.ISourcePosition;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/**
 * An assignment to a local variable.
 */
public class LocalAsgnNode extends AssignableNode implements INameNode {
    // The name of the variable
    private String name;
    
    // A scoped location of this variable (high 16 bits is how many scopes down and low 16 bits
    // is what index in the right scope to set the value.
    private final int location;

    public LocalAsgnNode(ISourcePosition position, String name, int location, Node valueNode) {
        super(position, NodeType.LOCALASGNNODE, valueNode);
        this.name = name;
        // in order to make pragma's noops we set location to a special value
        if (ASTInspector.PRAGMAS.contains(name)) {
            this.location = 0xFFFFFFFF;
        } else {
            this.location = location;
        }
    }
    
    /**
     * Accept for the visitor pattern.
     * @param iVisitor the visitor
     **/
    public Instruction accept(NodeVisitor iVisitor) {
        return iVisitor.visitLocalAsgnNode(this);
    }
    
    /**
     * Name of the local assignment.
     **/
    public String getName() {
        return name;
    }
    
    /**
     * Change the name of this local assignment (for refactoring)
     * @param name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * How many scopes should we burrow down to until we need to set the block variable value.
     * 
     * @return 0 for current scope, 1 for one down, ...
     */
    public int getDepth() {
        return location >> 16;
    }
    
    /**
     * Gets the index within the scope construct that actually holds the eval'd value
     * of this local variable
     * 
     * @return Returns an int offset into storage structure
     */
    public int getIndex() {
        return location & 0xffff;
    }
    
    public List<Node> childNodes() {
        return createList(getValueNode());
    }

    @Override
    public IRubyObject interpret(Ruby runtime, ThreadContext context, IRubyObject self, Block aBlock) {
        // ignore compiler pragmas
        if (location == 0xFFFFFFFF) return runtime.getNil();
        
        return context.getCurrentScope().setValue(getIndex(),
                getValueNode().interpret(runtime,context, self, aBlock), getDepth());
    }
    
    @Override
    public IRubyObject assign(Ruby runtime, ThreadContext context, IRubyObject self, IRubyObject value, Block block, boolean checkArity) {
        context.getCurrentScope().setValue(getIndex(), value, getDepth());
        
        return runtime.getNil();
    }
}
