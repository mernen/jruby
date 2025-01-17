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
 * Copyright (C) 2002 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2006 Lukas Felber <lfelber@hsr.ch>
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
import org.jruby.RubySymbol;

import org.jruby.ast.types.ILiteralNode;
import org.jruby.ast.types.INameNode;
import org.jruby.ast.visitor.NodeVisitor;
import org.jruby.evaluator.Instruction;
import org.jruby.lexer.yacc.ISourcePosition;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/** 
 * Represents a symbol (:symbol_name).
 */
public class SymbolNode extends Node implements ILiteralNode, INameNode {
    private String name;
    private RubySymbol symbol;

    public SymbolNode(ISourcePosition position, String name) {
	    super(position, NodeType.SYMBOLNODE);
	    this.name = name;
    }

    public Instruction accept(NodeVisitor iVisitor) {
        return iVisitor.visitSymbolNode(this);
    }

    /**
     * Gets the name.
     * @return Returns a String
     */
    public String getName() {
        return name;
    }
    
    public List<Node> childNodes() {
        return EMPTY_LIST;
    }

    public RubySymbol getSymbol(Ruby runtime) {
        RubySymbol sym;
        if ((sym = symbol) != null) return sym;
        return symbol = runtime.fastNewSymbol(name);
    }
    
    @Override
    public IRubyObject interpret(Ruby runtime, ThreadContext context, IRubyObject self, Block aBlock) {
        return getSymbol(runtime);
    }
    
    @Override
    public IRubyObject when(WhenNode whenNode, IRubyObject value, ThreadContext context, Ruby runtime, IRubyObject self, Block aBlock) {
       if (value == null) whenNode.interpret(runtime, context, self, aBlock);
       if (value instanceof RubySymbol && ((RubySymbol) value).asJavaString() == name) {
           return whenNode.interpret(runtime, context, self, aBlock);
       }

       return null;
    }
}
