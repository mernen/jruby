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
 * Copyright (C) 2001-2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2001-2002 Jan Arne Petersen <jpetersen@uni-bonn.de>
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
import org.jruby.evaluator.ASTInterpreter;
import org.jruby.evaluator.Instruction;
import org.jruby.javasupport.util.RuntimeHelpers;
import org.jruby.lexer.yacc.ISourcePosition;
import org.jruby.runtime.Block;
import org.jruby.runtime.CallSite;
import org.jruby.runtime.InterpretedBlock;
import org.jruby.runtime.MethodIndex;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/** 
 * Represents a method call with self as an implicit receiver.
 */
public class FCallNode extends Node implements INameNode, IArgumentNode, BlockAcceptingNode {
    private Node argsNode;
    private Node iterNode;
    public CallSite callAdapter;

    @Deprecated
    public FCallNode(ISourcePosition position, String name, Node argsNode) {
        this(position, name, argsNode, null);
    }
    
    protected FCallNode(ISourcePosition position, String name, Node argsNode, Node iterNode) {
        super(position, NodeType.FCALLNODE);
        setArgsNode(argsNode);
        this.iterNode = iterNode;
        this.callAdapter = MethodIndex.getFunctionalCallSite(name);
    }
    
    /**
     * Accept for the visitor pattern.
     * @param iVisitor the visitor
     **/
    public Instruction accept(NodeVisitor iVisitor) {
        return iVisitor.visitFCallNode(this);
    }
    
    /**
     * Get the node that represents a block or a block variable.
     */
    public Node getIterNode() {
        return iterNode;
    }
    
    public Node setIterNode(Node iterNode) {
        this.iterNode = iterNode;
        callAdapter = MethodIndex.getFunctionalCallSite(callAdapter.methodName);
        
        return this;
    }

    /**
     * Gets the argsNode.
     * @return Returns a Node
     */
    public Node getArgsNode() {
        return argsNode;
    }
    
    /**
     * Set the argsNode.  This is for re-writer and general interpretation.
     * 
     * @param argsNode set the arguments for this node.
     */
    public Node setArgsNode(Node argsNode) {
        this.argsNode = argsNode;
        // If we have more than one arg, make sure the array created to contain them is not ObjectSpaced
        if (argsNode instanceof ArrayNode) {
            ((ArrayNode)argsNode).setLightweight(true);
        }
        
        return argsNode;
    }

    /**
     * Gets the name.
     * @return Returns a String
     */
    public String getName() {
        return callAdapter.methodName;
    }
    
    public List<Node> childNodes() {
        return createList(argsNode, iterNode);
    }

    @Override
    public String toString() {
        return "" + getClass().getName() + ": '" + getName() + "' @ " + getPosition();
    }
    
    @Override
    public IRubyObject interpret(Ruby runtime, ThreadContext context, IRubyObject self, Block aBlock) {
        assert false : "Should not happen anymore";

        return null;
    }
    
    public Block getBlock(ThreadContext context, IRubyObject self) {
        IterNode iter = (IterNode) iterNode;
        
        iter.getScope().determineModule();
            
        // Create block for this iter node
        // FIXME: We shouldn't use the current scope if it's not actually from the same hierarchy of static scopes
        return InterpretedBlock.newInterpretedClosure(context, iter.getBlockBody(), self);
    }
    
    public Block getBlock(Ruby runtime, ThreadContext context, IRubyObject self, Block aBlock) {
        return RuntimeHelpers.getBlockFromBlockPassBody(runtime, iterNode.interpret(runtime, context, self, aBlock), aBlock);
    }
    
    @Override
    public String definition(Ruby runtime, ThreadContext context, IRubyObject self, Block aBlock) {
        if (self.getMetaClass().isMethodBound(getName(), false)) {
            return ASTInterpreter.getArgumentDefinition(runtime, context, getArgsNode(), "method", self, aBlock);
        }
            
        return null;
    }
}
