/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008-9, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 * @authors Andrew Dinn
 */
package org.jboss.jbossts.orchestration.rule.helper;

import org.jboss.jbossts.orchestration.rule.type.Type;
import org.jboss.jbossts.orchestration.rule.Rule;
import org.jboss.jbossts.orchestration.rule.exception.ExecuteException;
import org.jboss.jbossts.orchestration.rule.binding.Bindings;
import org.jboss.jbossts.orchestration.rule.binding.Binding;
import org.jboss.jbossts.orchestration.agent.Transformer;

import java.util.HashMap;
import java.util.Iterator;

/**
 * Implementation of RuleHelper which extends the functionality of the standard helper class,
 * Helper, by adding the methods required to implement interface RuleHelper. It provides an
 * implementation which executes rules by interpreting the rule tree. Any rule which employs the
 * standard helper type checks built in method calls against class Helper. However, the interpreter
 * assumes that the helper object implements InterpretedHelper.
 *
 * When a rule is compiled class Helper is extended with a generated class CompiledHelper<NNN>
 * which also implements interface RuleHelper. The implementation of the execute method is generated
 * by translating the parse tree to bytecode. Builtin calls are translated to calls of methods
 * defined by class Helper.
 *
 * A rule can also specify its own helper class in order to provide its own set of builtin
 * operations. The helper class does not implement interface RuleHelper. Instead the
 * compilation process will generate a subclass of the user-defined helper class which
 * provides an appropriate implementation for the RuleHelper methods, including an implementation
 * of the execute method dreived from the rule parse tree. As in the default case, builtin calls
 * are translated to calls of methods defined by the helper class. 
 */
public class InterpretedHelper extends Helper implements HelperAdapter
{
    protected HashMap<String, Object> bindingMap;
    private HashMap<String, Type> bindingTypeMap;

    public InterpretedHelper(Rule rule)
    {
        super(rule);
        bindingMap = new HashMap<String, Object>();
        bindingTypeMap = new HashMap<String, Type>();
    }

    /**
     * install values into the bindings map and then call the execute0 method
     * to actually execute the rule
     * @param bindings
     * @param recipient
     * @param args
     */
    public void execute(Bindings bindings, Object recipient, Object[] args)
            throws ExecuteException
    {
        if (Transformer.isVerbose()) {
            System.out.println(rule.getName() + " execute");
        }
        Iterator<Binding> iterator = bindings.iterator();
        while (iterator.hasNext()) {
            Binding binding = iterator.next();
            String name = binding.getName();
            Type type = binding.getType();
            if (binding.isHelper()) {
                bindingMap.put(name, this);
                bindingTypeMap.put(name, type);
            } else if (binding.isRecipient()) {
                bindingMap.put(name, recipient);
                bindingTypeMap.put(name, type);
            } else if (binding.isParam()) {
                bindingMap.put(name, args[binding.getIndex() - 1]);
                bindingTypeMap.put(name, type);
            }
        }

        // now do the actual execution

        execute0();
    }

    /**
     * basic implementation of rule execution
     *
     * @throws ExecuteException
     */
        
    protected void execute0()
            throws ExecuteException
    {
        // System.out.println(rule.getName() + " execute0");
        bind();
        if (test()) {
            fire();
        }
    }

    public void bindVariable(String name, Object value)
    {
        bindingMap.put(name, value);
    }

    public Object getBinding(String name)
    {
        return bindingMap.get(name);
    }

    private void bind()
            throws ExecuteException
    {
        // System.out.println(rule.getName() + " bind");
        rule.getEvent().interpret(this);
    }

    private boolean test()
            throws ExecuteException
    {
        // System.out.println(rule.getName() + " test");
        return rule.getCondition().interpret(this);
    }
        
    private void fire()
            throws ExecuteException
    {
        // System.out.println(rule.getName() + " fire");
        rule.getAction().interpret(this);
    }

    public String getName() {
        return rule.getName();
    }
}
