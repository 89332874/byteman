/*
* JBoss, Home of Professional Open Source
* Copyright 2009, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.byteman.agent.adapter;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Method;
import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.type.TypeHelper;
import org.jboss.byteman.rule.binding.Bindings;
import org.jboss.byteman.rule.binding.Binding;
import org.jboss.byteman.agent.adapter.cfg.*;
import org.jboss.byteman.agent.Transformer;

import java.util.*;
import java.io.PrintStream;

/**
 * class which provides base functionality extended by all the location-specific method trigger adapters
 */
public class RuleTriggerMethodAdapter extends RuleMethodAdapter
{
    RuleTriggerMethodAdapter(MethodVisitor mv, Rule rule, int access, String name, String descriptor)
    {
        super(mv, rule, access, name, descriptor);
        this.access = access;
        this.descriptor = descriptor;
        this.callArrayBindings = new ArrayList<Binding>();
        this.returnType = Type.getReturnType(descriptor);
        this.argumentTypes = Type.getArgumentTypes(descriptor);
        this.argLocalIndices = new int[argumentTypes.length];
        this.bindingsDone = false;
        this.localTypes = new ArrayList();
    }

    private void setBindingIndices()
    {
        Bindings bindings = rule.getBindings();
        Iterator<Binding> iterator = bindings.iterator();
        List<Binding> aliasBindings = new ArrayList<Binding>();
        int argLocalIndex = 0;

        // if this is an instance method then the local 0 holds this and args start at local index 1

        if ((access & Opcodes.ACC_STATIC) == 0) {
            argLocalIndex += 1;
        }

        // compute the local indices of each method parameter

        for (int i = 0; i < argumentTypes.length; i++) {
            argLocalIndices[i] = argLocalIndex;
            argLocalIndex += argumentTypes[i].getSize();
        }

        // check the local var bindings and, if any of them are actually refereces to method params
        // alias them to the corresponding param binding

        while (iterator.hasNext()) {
            Binding binding = iterator.next();
            if  (binding.isLocalVar()){
                int localIdx = binding.getLocalIndex();
                if (localIdx < argLocalIndex) {
                    binding = alias(binding, bindings, localIdx);
                    if (binding != null) {
                        // the aliased param binding was not present so ensure it gets added to the
                        // binding set once we have finished iterating. there is no need to add it
                        // to the call bindings since we pass the aliased method param instead
                        aliasBindings.add(binding);
                    }
                } else {
                    callArrayBindings.add(binding);
                }
            }
        }

        bindings.addBindings(aliasBindings);
        
        // now iterate over the param vars and ensure they go into the call bindings list

        iterator = bindings.iterator();
        
        while (iterator.hasNext()) {
            Binding binding = iterator.next();
            if (binding.isParam()) {
                callArrayBindings.add(binding);
            } else if (binding.isReturn()) {
                // at some point we will allow reference to the current return value so we need
                // to be sure that the method has a non-void return type and that the location
                // specifier is AT RETURN but for now we do nothing
            }
        }
        // we don't have to do this but it makes debgging easier
        
        if (true) {
            // ok now sort the callArrayBindings for later use

            Comparator<Binding> comparator = new Comparator<Binding>() {
                public int compare(Binding b1, Binding b2)
                {
                    if (b1.isParam()) {
                        if (b2.isParam()) {
                            int i1 = b1.getIndex();
                            int i2 = b2.getIndex();
                            return (i1 < i2 ? -1 : (i1 > i2 ? 1 : 0));
                        } else {
                            return -1;
                        }
                    } else {
                        if (b2.isParam()) {
                            return 1;
                        } else {
                            int i1 = b1.getLocalIndex();
                            int i2 = b2.getLocalIndex();
                            return (i1 < i2 ? -1 : (i1 > i2 ? 1 : 0));
                        }
                    }
                }
            };

            Collections.sort(callArrayBindings, comparator);
        }

        // now give each binding a unique index in the object array
        int n = callArrayBindings.size();
        for (int i = 0; i < n; i++) {
            callArrayBindings.get(i).setCallArrayIndex(i);
        }
    }

    private Binding alias(Binding binding, Bindings bindings, int localIdx)
    {
        if (((access & Opcodes.ACC_STATIC) == 0) && (localIdx == 0)) {
            String name = "$0";
            Binding alias = bindings.lookup(name);
            if (alias == null) {
                alias = new Binding(rule, name);
                alias.setDescriptor(binding.getDescriptor());
                alias.setLocalIndex(binding.getLocalIndex());
                binding.aliasTo(alias);
                // alias var was new so return it for addition to the binding set
                return alias;
            } else {
                // just alias the binding
                binding.aliasTo(alias);
                return null;
            }
        }

        for (int i = 0; i < argLocalIndices.length; i++) {
            if (argLocalIndices[i] == localIdx) {
                String name = "$" + (i + 1);
                Binding alias = bindings.lookup(name);
                if (alias == null) {
                    alias = new Binding(rule, name);
                    alias.setDescriptor(binding.getDescriptor());
                    alias.setLocalIndex(binding.getLocalIndex());
                    binding.aliasTo(alias);
                    // alias var was new so return it for addition to the binding set
                    return alias;
                } else {
                    // just alias the binding
                    binding.aliasTo(alias);
                    return null;
                }
            }
        }

        return null;
    }

    public void doArgLoad()
    {
        if (!bindingsDone) {
            setBindingIndices();
            bindingsDone = true;
        }
        
        if (callArrayBindings.size() ==  0) {
            push((org.objectweb.asm.Type)null);
            return;
        }

        int arraySize = callArrayBindings.size();

        push(arraySize);
        Type objectType = Type.getType(Object.class);
        newArray(objectType);

        for (int i = 0; i < arraySize; i++) {
            Binding binding = callArrayBindings.get(i);
            dup();
            push(i);
            if (binding.isParam()) {
                int idx = binding.getIndex() - 1;
                loadArg(idx);
                box(argumentTypes[idx]);
            } else {
                int idx = binding.getLocalIndex();
                loadLocal(idx);
                box(getLocalType(idx));
            }
            arrayStore(objectType);
        }
    }

    /**
     * return true if the current block is handler which catches a thrown exception within the scope
     * of a monitor enter in order to be able exit the monitor and rethrow the exception
     * @return
     */
    protected boolean inRethrowHandler()
    {
        return cfg.inRethrowHandler();
    }

    private int access;
    private String descriptor;
    private Type returnType;
    private Type[] argumentTypes;
    private int[] argLocalIndices;
    private List<Binding> callArrayBindings;
    private boolean bindingsDone;
    private final List localTypes;

    private CFG cfg;

    private final static Type EXECUTE_EXCEPTION_TYPE = Type.getType(TypeHelper.externalizeType("org.jboss.byteman.rule.exception.ExecuteException"));
    private final static Type EARLY_RETURN_EXCEPTION_TYPE = Type.getType(TypeHelper.externalizeType("org.jboss.byteman.rule.exception.EarlyReturnException"));
    private final static Type THROW_EXCEPTION_TYPE = Type.getType(TypeHelper.externalizeType("org.jboss.byteman.rule.exception.ThrowException"));
    private final static String EXECUTE_EXCEPTION_TYPE_NAME = EXECUTE_EXCEPTION_TYPE.getInternalName();
    private final static String EARLY_RETURN_EXCEPTION_TYPE_NAME = EARLY_RETURN_EXCEPTION_TYPE.getInternalName();
    private final static String THROW_EXCEPTION_TYPE_NAME = THROW_EXCEPTION_TYPE.getInternalName();

    // methdos copied from GeneratorAdapter but modified so they invoke local MethodVisitor
    // method implementations rather than delegating to the next MethodVisitor in line



    // overridden methods from MethodVisitor

    @Override
    public void visitCode()
    {
        super.visitCode();
        // create a control flow graph for the method
        String methodName = this.rule.getTargetClass() + "." + this.name + this.descriptor;
        Label newStart = super.newLabel();
        this.cfg = new CFG(methodName, newStart);
        visitLabel(newStart);
    }

    @Override
    public void visitInsn(int opcode)
    {
        super.visitInsn(opcode);
        // look for interesting instructions which need inserting into the CFG
        switch(opcode)
        {
            case Opcodes.IRETURN:
            case Opcodes.LRETURN:
            case Opcodes.DRETURN:
            case Opcodes.RETURN:
            case Opcodes.ATHROW:
            {
                // add this instruction to the current block and then start a new current block
                cfg.add(opcode);
                Label newStart = super.newLabel();
                // must call split before visiting the label
                cfg.split(newStart);
                visitLabel(newStart);
            }
            break;
            case Opcodes.MONITORENTER:
            case Opcodes.MONITOREXIT:
            {
                // just add this instruction to the current block
                cfg.add(opcode);
            }
            break;
            default:
            {
                cfg.add(opcode);
            }
        }
    }

    @Override
    public void visitIincInsn(int var, int increment)
    {
        super.visitIincInsn(var, increment);
        cfg.add(Opcodes.IINC, var, increment);
    }

    @Override
    public void visitIntInsn(int opcode, int operand)
    {
        super.visitIntInsn(opcode, operand);
        cfg.add(opcode, operand);
    }

    @Override
    public void visitLdcInsn(Object cst)
    {
        super.visitLdcInsn(cst);
        cfg.add(Opcodes.LDC, cst.toString());
    }

    @Override
    public void visitVarInsn(int opcode, int var)
    {
        super.visitVarInsn(opcode, var);
        cfg.add(opcode, var);
    }

    @Override
    public void visitTypeInsn(int opcode, String desc)
    {
        super.visitTypeInsn(opcode, desc);
        cfg.add(opcode, desc);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc)
    {
        super.visitFieldInsn(opcode, owner, name, desc);
        cfg.add(opcode, owner, name, desc);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc)
    {
        super.visitMethodInsn(opcode, owner, name, desc);
        cfg.add(opcode, owner, name, desc);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label)
    {
        super.visitJumpInsn(opcode, label);
        switch (opcode)
        {
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE:
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE:
            {
                // create a new current block and add the label supplied in the call as the
                // first out of the old current block and the label of the new current block as
                // the second out
                cfg.add(opcode);
                Label newStart = super.newLabel();
                // must call split before visiting the label
                cfg.split(newStart, label, newStart);
                visitLabel(newStart);
            }
            break;
            case Opcodes.GOTO:
            {
                // create a new current block and  add the label supplied in the call as the
                // first out of the old current block
                cfg.add(opcode);
                Label newStart = super.newLabel();
                // must call split before visiting the label
                cfg.split(newStart, label);
                visitLabel(newStart);
            }
            break;
            case Opcodes.JSR:
            {
                // create a new current block and add the label supplied in the call as the first out
                // of the current block -- the new current block is a potential return point from the
                // JSR but we cannot represent that statically
                cfg.add(opcode);
                Label newStart = super.newLabel();
                // must call split before visiting the label
                cfg.split(newStart, label, newStart);
                visitLabel(newStart);
            }
            break;
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
            {
                // create a new current block and add the label supplied in the call as the
                // first out of the old current block and the label of the new current block as
                // the second out
                cfg.add(opcode);
                Label newStart = super.newLabel();
                // must call split before visiting the label
                cfg.split(newStart, label, newStart);
                visitLabel(newStart);
            }
            break;
        }
    }

    @Override
    public void visitLabel(Label label)
    {
        super.visitLabel(label);

        // tell the CFG to visit this label

        cfg.visitLabel(label);
        // if this is a try catch block end then we need to visit the try catch block now
        if (cfg.tryCatchEnd(label)) {
            for (TryCatchDetails details : cfg.tryCatchEndDetails(label)) {
                super.visitTryCatchBlock(details.getStart(), details.getEnd(), details.getHandler(), details.getType());
            }
        }
    }

    public void visitTriggerStart(Label label)
    {
        visitLabel(label);

        // tell the CFG to visit this label

        cfg.visitTriggerStart(label);
    }

    public void visitTriggerEnd(Label label)
    {
        visitLabel(label);

        // tell the CFG to visit this label

        cfg.visitTriggerEnd(label);

        // ok, update the trigger details with labels for the handler blocks we are going to generate

        TriggerDetails details = cfg.triggerEndDetails(label);

        Label returnHandler = newLabel();
        Label throwHandler = newLabel();
        Label executeHandler = newLabel();

        details.setEarlyReturnHandler(returnHandler);
        details.setThrowHandler(throwHandler);
        details.setExecuteHandler(executeHandler);

        // ok now we set up try catch handlers for the triggger block
        // n.b. we need to use a new label for the end because insertion of the handler is set off when
        // its end label is visited

        Label end = new Label();

        visitTryCatchBlock(details.getStart(), end, returnHandler, EARLY_RETURN_EXCEPTION_TYPE_NAME);
        visitTryCatchBlock(details.getStart(), end, throwHandler, THROW_EXCEPTION_TYPE_NAME);
        visitTryCatchBlock(details.getStart(), end, executeHandler, EXECUTE_EXCEPTION_TYPE_NAME);

        // ok this fixes the  handler end label at the same pont as the trigger end label
        
        visitLabel(end);
    }

    /*
    @Override
    public void visitLdcInsn(Object cst) {
        super.visitLdcInsn(cst);
    }
    @Override
    public void visitIincInsn(int var, int increment) {
        super.visitIincInsn(var, increment);
    }
    */

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels)
    {
        super.visitTableSwitchInsn(min, max, dflt, labels);
        cfg.add(Opcodes.TABLESWITCH, min, max);
        // create a new current block and then add the default lable and each of the switch labels as an
        // outgoing path from the current block
        Label newStart = super.newLabel();
        // must call split before visiting the label
        cfg.split(newStart, dflt, labels);
        visitLabel(newStart);
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels)
    {
        super.visitLookupSwitchInsn(dflt, keys, labels);
        cfg.add(Opcodes.TABLESWITCH, keys);
        // create a new current block and then add the default lable and each of the switch labels as an
        // outgoing path from the current block
        Label newStart = super.newLabel();
        // must call split before visiting the label
        cfg.split(newStart, dflt, labels);
        visitLabel(newStart);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims)
    {
        super.visitMultiANewArrayInsn(desc, dims);
        cfg.add(Opcodes.MULTIANEWARRAY, desc, dims);
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type)
    {
        // don't notify this until we reach the end label so we can slip
        // trigger sequence try catch blocks in.
        // super.visitTryCatchBlock(start, end, handler, type);

        // tell the cfg to visit this block

        cfg.visitTryCatchBlock(start, end, handler, type);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals)
    {
        Type returnType =  Type.getReturnType(descriptor);

        // check whether there are outstanding monitor opens at the start of the trigger
        // block and, if so, insert a handler which unlocks the monitor and then rethrows
        // the exception.

        Iterator<TriggerDetails> iterator = cfg.triggerDetails();
        boolean noneLeft = true;

        while (iterator.hasNext()) {
            TriggerDetails details = iterator.next();
            Label startLabel = details.getStart();
            CodeLocation startLocation = cfg.getLocation(startLabel);
            List<CodeLocation> openEnters = cfg.getOpenMonitors(startLocation);
            if (openEnters != null) {
                // add a handler here which unlocks each object and rethrows the
                // saved exception then protect it with a try catch block and update
                // the details so that it is the target of this block

                Label newStart = newLabel();
                Label newEnd = newLabel();
                Label newExecute = newLabel();
                Label newEarlyReturn = newLabel();
                Label newThrow = newLabel();
                // make the old exceptions arrive here
                visitLabel(details.getExecuteHandler());
                visitLabel(details.getEarlyReturnHandler());
                visitLabel(details.getThrowHandler());
                // now add a rethrow handler which exits the open monitors
                visitLabel(newStart);
                int listIdx = openEnters.size();
                while (listIdx-- > 0) {
                    CodeLocation enterLocation = openEnters.get(listIdx);
                    int varIdx = cfg.getSavedMonitorIdx(enterLocation);
                    // call super method to avoid indexing these instructions
                    visitVarInsn(Opcodes.ALOAD, varIdx);
                    visitInsn(Opcodes.MONITOREXIT);
                }
                // throw must be in scope of the try catch
                // call super method to avoid creating new blocks
                visitInsn(Opcodes.ATHROW);
                // add try catch blocks for each of the exception types
                visitTryCatchBlock(newStart, newEnd, newEarlyReturn, EARLY_RETURN_EXCEPTION_TYPE_NAME);
                visitTryCatchBlock(newStart, newEnd, newThrow, THROW_EXCEPTION_TYPE_NAME);
                // this comes last because it is the superclass of the previous two
                visitTryCatchBlock(newStart, newEnd, newExecute, EXECUTE_EXCEPTION_TYPE_NAME);
                // now visit label so they get processed
                visitLabel(newEnd);
                // and update the details so it will catch these exceptions
                details.setStart(newStart);
                details.setEnd(newEnd);
                details.setExecuteHandler(newExecute);
                details.setEarlyReturnHandler(newEarlyReturn);
                details.setThrowHandler(newThrow);
            }
        }

        // ok, so now we have to add the handler code for trigger block try catch handlers
        // we only need to add the handler code once but we need to make sure it is the target of
        //  all the try catch blocks by visiting their handler label before we insert the code

        iterator = cfg.triggerDetails();

        while (iterator.hasNext()) {
            TriggerDetails details = iterator.next();
            visitLabel(details.getEarlyReturnHandler());
        }

        if (Transformer.isVerbose()) {
            getStatic(Type.getType(System.class), "out", Type.getType(PrintStream.class));
            visitLdcInsn("caught ReturnException");
            invokeVirtual(Type.getType(PrintStream.class), Method.getMethod("void println(String)"));
        }
        // add exception handling code subclass first
        if (returnType == Type.VOID_TYPE) {
            // drop exception and just return
            pop();
            visitInsn(Opcodes.RETURN);
        } else {
            // fetch value from exception, unbox if needed and return value
            Method getReturnValueMethod = Method.getMethod("Object getReturnValue()");
            invokeVirtual(EARLY_RETURN_EXCEPTION_TYPE, getReturnValueMethod);
            unbox(returnType);
            returnValue();
        }

        iterator = cfg.triggerDetails();

        while (iterator.hasNext()) {
            TriggerDetails details = iterator.next();
            visitLabel(details.getThrowHandler());
        }

        if (Transformer.isVerbose()) {
            getStatic(Type.getType(System.class), "out", Type.getType(PrintStream.class));
            visitLdcInsn("caught ThrowException");
            invokeVirtual(Type.getType(PrintStream.class), Method.getMethod("void println(String)"));
        }
        // fetch value from exception, unbox if needed and return value
        Method getThrowableMethod = Method.getMethod("Throwable getThrowable()");
        invokeVirtual(THROW_EXCEPTION_TYPE, getThrowableMethod);
        throwException();

        // execute exception  comes last because it is the super of the othher two classes
        
        iterator = cfg.triggerDetails();

        while (iterator.hasNext()) {
            TriggerDetails details = iterator.next();
            visitLabel(details.getExecuteHandler());
        }

        if (Transformer.isVerbose()) {
            getStatic(Type.getType(System.class), "out", Type.getType(PrintStream.class));
            visitLdcInsn("caught ExecuteException");
            invokeVirtual(Type.getType(PrintStream.class), Method.getMethod("void println(String)"));
        }
        // rethrow an execute exception
        throwException();

        super.visitMaxs(maxStack, maxLocals);

        // hmm, don't think we need this
        cfg.visitMaxs();
    }

    @Override
    public void visitEnd()
    {
        super.visitEnd();
        // trash the current label
        cfg.visitEnd();
    }
}