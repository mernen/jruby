/***** BEGIN LICENSE BLOCK *****
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
 * Copyright (C) 2001-2002 Jan Arne Petersen <jpetersen@uni-bonn.de>
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
package org.jruby.runtime;
@Deprecated /* FIXME unused */
public class RubyOptions {
    private final JavaSupport javaSupport = new JavaSupport();

    public JavaSupport getJavaSupport() {
        return javaSupport;
    }

    public static final class JavaSupport {

        private boolean rubyNames = true;
        private boolean javaNames = true;
        private boolean rubyModules = true;

        private JavaSupport() {
        }

        public boolean isRubyNames() {
            return rubyNames;
        }
        
        public boolean isJavaNames() {
            return javaNames;
        }

        public boolean isRubyModules() {
            return rubyModules;
        }

        public void setRubyNames(boolean rubyNames) {
            this.rubyNames = rubyNames;
        }

        public void setJavaNames(boolean javaNames) {
            this.javaNames = javaNames;
        }

        public void setRubyModules(boolean rubyModules) {
            this.rubyModules = rubyModules;
        }
    }
}
