/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.jruby.runtime;

/**
 *
 * @author headius
 */
public abstract class AbstractCompiledBlockCallback implements CompiledBlockCallback {
    protected final Object $scriptObject;

    public AbstractCompiledBlockCallback(Object scriptObject) {
        $scriptObject = scriptObject;
    }
}
