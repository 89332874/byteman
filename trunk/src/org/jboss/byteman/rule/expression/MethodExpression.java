/*
* JBoss, Home of Professional Open Source
* Copyright 2008, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.byteman.rule.expression;

import org.jboss.byteman.rule.type.Type;
import org.jboss.byteman.rule.type.TypeGroup;
import org.jboss.byteman.rule.binding.Binding;
import org.jboss.byteman.rule.exception.TypeException;
import org.jboss.byteman.rule.exception.ExecuteException;
import org.jboss.byteman.rule.exception.CompileException;
import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.compiler.StackHeights;
import org.jboss.byteman.rule.helper.HelperAdapter;
import org.jboss.byteman.rule.grammar.ParseNode;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;

/**
 * an expression which identifies a method invocation
 */
public class MethodExpression extends Expression
{
    public MethodExpression(Rule rule, Type type, ParseNode token, Expression recipient, List<Expression> arguments, String[] pathList) {
        super(rule, type, token);
        this.name = token.getText();
        this.recipient = recipient;
        this.arguments = arguments;
        this.argumentTypes = null;
        this.paramTypes = null;
        this.rootType = null;
        this.pathList = pathList;
    }

    /**
     * verify that variables mentioned in this expression are actually available in the supplied
     * bindings list and infer/validate the type of this expression or its subexpressions
     * where possible
     *
     * @return true if all variables in this expression are bound and no type mismatches have
     *         been detected during inference/validation.
     */
    public void bind() throws TypeException
    {
        // check that the recipient and argument expressions have valid bindings

        if (recipient != null) {
            // ok, we have a supplied recipient
            recipient.bind();
        } else if (pathList != null) {
            // see if the path starts with a bound variable and, if so, treat the path as a series
            // of field references and construct a owner expression from it.
            String leading = pathList[0];
            Binding binding = getBindings().lookup(leading);
            if (binding != null) {
                // create a sequence of field expressions and make it the recipient

                int l = pathList.length;
                Expression recipient =  new Variable(rule, binding.getType(), token, binding.getName());
                for (int idx = 1; idx < l; idx++) {
                    recipient = new FieldExpression(rule, Type.UNDEFINED, token, pathList[idx], recipient, null);
                }
                this.recipient = recipient;
                this.pathList = null;
                // not strictly necessary?
                this.recipient.bind();
            }
        }

        Iterator<Expression> iterator = arguments.iterator();

        while (iterator.hasNext()) {
            iterator.next().bind();
        }
    }

    public Type typeCheck(Type expected) throws TypeException {
        // if we have no recipient then we use the rule's helper as a target via a binding
        // to $-1. this means  we can type check the call against methods of class Helper
        // without having to do any special case processing.

        TypeGroup typeGroup =  getTypeGroup();

        if (recipient == null && pathList != null) {
            // treat the pathlist as a typename or a static field dereference possibly combined with
            // further field dereferences

            // factor off a typename from the path
            Type rootType = typeGroup.match(pathList);
            if (rootType == null) {
                throw new TypeException("FieldExpression.typeCheck : invalid path " + getPath(pathList.length) + " to static method " + name + getPos());
            }

            // find out how many of the path elements are included in the type name

            String rootTypeName = rootType.getName();

            int idx = getPathCount(rootTypeName);

            if (idx < pathList.length) {
                // create a static field reference using the type name and the first field name and wrap it with
                // enough field references to use up all the path

                String fieldName = pathList[idx++];
                Expression recipient = new StaticExpression(rule, Type.UNDEFINED, token, fieldName, rootTypeName);
                while (idx < pathList.length) {
                    recipient = new FieldExpression(rule, Type.UNDEFINED, token, pathList[idx++], recipient, null);
                }
                this.recipient = recipient;
            } else {
                // ok, this method reference is actually a static method call -- record the root type for later
                this.recipient = null;
                this.rootType = rootType;
            }
            // get rid of the path list now
            this.pathList = null;
            // not strictly necessary?
            if (this.recipient != null) {
                this.recipient.bind();
            }
        }

        // if we don't have a recipient and we didn't find a static class for the method then this is
        // a builtin
        
        if (recipient == null) {
            if (rootType == null) {
                Type ruleType = typeGroup.create(rule.getHelperClass().getCanonicalName());
                recipient = new DollarExpression(rule, ruleType, token, DollarExpression.HELPER_IDX);
                recipient.bind();

                rootType = recipient.typeCheck(Type.UNDEFINED);
            }
        } else {
            rootType = recipient.typeCheck(Type.UNDEFINED);
        }

        // ok the only way we fail to have a recipient now is because this is a static call
        
        boolean isStatic = (recipient == null);

        Class clazz = rootType.getTargetClass();
        
        // if we can find a unique method then we can use it to type the parameters
        // otherwise we do it the hard way
        int arity = arguments.size();
        Method[] methods = clazz.getMethods();
        List<Method> candidates = new ArrayList<Method>();
        boolean duplicates = false;

        for (Method method : methods) {
            int modifiers = method.getModifiers();
            // ensure we only look at static or non static methods as appropriate
            if (Modifier.isStatic(modifiers) == isStatic) {
                if (method.getName().equals(name) &&
                        method.getParameterTypes().length == arity) {
                    candidates.add(method);
                }
            }
        }

        argumentTypes = new ArrayList<Type>();

        // check each argument in turn -- if all candidates have the same argument type then
        // use that as the type to check against
        for (int i = 0; i < arguments.size() ; i++) {
            if (candidates.isEmpty()) {
                throw new TypeException("MethodExpression.typeCheck : invalid method " + name + " for target class " + rootType.getName() + getPos());
            }

            // TODO get and prune operations do not allow for coercion but type check does!
            // e.g. the parameter type may be int and the arg type float
            // or the parameter type may be String and the arg type class Foo
            // reimplement this using type inter-assignability to do the pruning

            Class candidateClass = getCandidateArgClass(candidates, i);
            Type candidateType;
            if (candidateClass != null) {
                candidateType = typeGroup.ensureType(candidateClass);
            } else {
                candidateType = Type.UNDEFINED;
            }
            Type argType = arguments.get(i).typeCheck(candidateType);
            argumentTypes.add(argType);
            if (candidateType == Type.UNDEFINED) {
                // we had several methods to choose from
                candidates = pruneCandidates(candidates, i, argType.getTargetClass());
            }
        }

        if (candidates.isEmpty()) {
            throw new TypeException("MethodExpression.typeCheck : invalid method " + name + " for target class " + rootType.getName() + getPos());
        }
        
        if (candidates.size() > 1) {
            throw new TypeException("MethodExpression.typeCheck : ambiguous method signature " + name + " for target class " + rootType.getName() + getPos());
        }

        method = candidates.get(0);

        // now go back and identify the parameter types

        this. paramTypes = new ArrayList<Type>();
        Class<?>[] paramClasses = method.getParameterTypes();

        for (int i = 0; i < arguments.size(); i++) {
            Class<?> paramClass = paramClasses[i];
            paramTypes.add(typeGroup.ensureType(paramClass));
        }

        type = typeGroup.ensureType(method.getReturnType());

        if (Type.dereference(expected).isDefined() && !expected.isAssignableFrom(type)) {
            throw new TypeException("MethodExpression.typeCheck : invalid expected type " + expected.getName() + getPos());
        }

        return type;
    }

    public Object interpret(HelperAdapter helper) throws ExecuteException {
        Object recipientValue = null;
        try {
            if (recipient != null) {
                recipientValue = recipient.interpret(helper);
                if (recipientValue == null) {
                    throw new ExecuteException("MethodExpression.interpret : null recipient for method " + token.getText() + getPos());
                }
            }
            int argCount = arguments.size();

            Object[] argValues = new Object[argCount];
            for (int i = 0; i < argCount; i++) {
                argValues[i] = arguments.get(i).interpret(helper);
            }

            // we have to enable triggers whenever we call out to a method in case it contians a trigger point
            // TODO - do we do this if the method is a built-in? i.e. if the target is an instance of the helper class
            // TODO - this breaks the user disable option so fix it!
            Rule.enableTriggersInternal();
            return method.invoke(recipientValue, argValues);
        } catch (InvocationTargetException e) {
            Throwable th = e.getCause();
            if (th instanceof ExecuteException) {
                throw (ExecuteException)th;
            } else {
                throw new ExecuteException("MethodExpression.interpret : exception invoking method " + token.getText() + getPos(), th);
            }
        } catch (ExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ExecuteException("MethodExpression.interpret : exception invoking method " + token.getText() + getPos(), e);
        } finally {
            // disable triggers again
            Rule.disableTriggersInternal();
        }
    }

    public void compile(MethodVisitor mv, StackHeights currentStackHeights, StackHeights maxStackHeights) throws CompileException
    {
        int currentStack = currentStackHeights.stackCount;
        int extraParams = 0; // space used by stacked args after conversion
        int expected = 0;
        if (recipient != null) {
            // compile code for recipient
            recipient.compile(mv, currentStackHeights, maxStackHeights);

            extraParams += 1;
        }

        int argCount = arguments.size();

        for (int i = 0; i < argCount; i++) {
            Expression argument = arguments.get(i);
            Type argType = argumentTypes.get(i);
            Type paramType = paramTypes.get(i);
            // compile code to stack argument and type convert if necessary
            argument.compile(mv, currentStackHeights, maxStackHeights);
            compileTypeConversion(argType, paramType, mv, currentStackHeights, maxStackHeights);
            // allow for stacked paramType value
            extraParams += (paramType.getNBytes() > 4 ? 2 : 1);
        }

        // enable triggering before we call the method
        // this temporarily adds an extra value to the stack so we need to check the stack height
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jboss/byteman/rule/Rule", "enableTriggersInternal", "()Z");
        mv.visitInsn(Opcodes.POP);
        if (maxStackHeights.stackCount == currentStackHeights.stackCount) {
            maxStackHeights.stackCount++;
        }

        // ok, now just call the method -- removes extraParams words

        String ownerName = Type.internalName(method.getDeclaringClass());

        if (recipient == null) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, ownerName, method.getName(), getDescriptor());
        } else if (recipient.getClass().isInterface()) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ownerName, method.getName(), getDescriptor());
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ownerName, method.getName(), getDescriptor());
        }

        // now disable triggering again
        // this temporarily adds an extra value to the stack but cannot increase the max height
        
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jboss/byteman/rule/Rule", "disableTriggersInternal", "()Z");
        mv.visitInsn(Opcodes.POP);

        // no need for type conversion as return type was derived from method
        if (type.getNBytes() > 4) {
            expected = 2;
        } else if (type != Type.VOID){
            expected = 1;
        } else {
            expected = 0;
        }

        // decrement the stack height to account for stacked param values (removed) and return value (added)
        currentStackHeights.addStackCount(expected - extraParams);

        // ensure we have only increased the stack by the return value size
        if (currentStackHeights.stackCount != currentStack + expected) {
            throw new CompileException("MethodExpression.compile : invalid stack height " + currentStackHeights.stackCount + " expecting " + (currentStack + expected));
        }

        // no need to update max stack since compiling the  recipient or arguments will
        // have done so (and there will be no change if there was no such compile call)
    }

    private String getDescriptor()
    {
        StringBuffer buffer = new StringBuffer();
        buffer.append("(");
        int nParams = paramTypes.size();
        for (int i = 0; i < nParams; i++) {
            buffer.append(paramTypes.get(i).getInternalName(true, true));
        }
        buffer.append(")");
        buffer.append(type.getInternalName(true, true));
        return buffer.toString();
    }
    
    public Class getCandidateArgClass(List<Method> candidates, int argIdx)
    {
        Class argClazz = null;

        for (Method m : candidates) {
            Class nextClazz = m.getParameterTypes()[argIdx];
            if (argClazz == null) {
                argClazz = nextClazz;
            } else if (argClazz != nextClazz) {
                return null;
            }
        }

        return argClazz;
    }

    public List<Method> pruneCandidates(List<Method> candidates, int argIdx, Class argClazz)
    {
        for (int i = 0; i < candidates.size();) {
            Method m = candidates.get(i);
            Class nextClazz = m.getParameterTypes()[argIdx];
            if (nextClazz != argClazz) {
                candidates.remove(i);
            } else {
                i++;
            }
        }
        return candidates;
    }

    public String getPath(int len)
    {
        StringBuffer buffer = new StringBuffer();
        buffer.append(pathList[0]);

        for (int i = 1; i < len; i++) {
            buffer.append(".");
            buffer.append(pathList[i]);
        }
        return buffer.toString();
    }

    public int getPathCount(String name)
    {
        // name will be package qualified so check whether the path list also includes the package
        if (name.startsWith(pathList[0])) {
            int charMax = name.length();
            int charCount = 0;
            int dotExtra = 0;
            int idx;
            for (idx = 0; idx < pathList.length; idx++) {
                charCount += (dotExtra + pathList[idx].length());
                if (charCount > charMax) {
                    break;
                }
            }
            return idx;
        } else {
            // name must have been obtained by globalizing an unqualified type name so the typename
            // is the first element in the path list
            return 1;
        }
    }

    public void writeTo(StringWriter stringWriter) {
        if (recipient != null) {
            recipient.writeTo(stringWriter);
            stringWriter.write(".");
        } else if (pathList != null) {
            stringWriter.write(getPath(pathList.length));
            stringWriter.write(".");
        }
        stringWriter.write(name);
        stringWriter.write("(");
        String sepr = "";
        for (Expression arg : arguments) {
            stringWriter.write(sepr);
            arg.writeTo(stringWriter);
            sepr = ", ";
        }
        stringWriter.write(")");
    }

    private String name;
    private List<Expression> arguments;
    private List<Type> argumentTypes;
    private List<Type> paramTypes;
    private Expression recipient;
    private Type rootType;
    private Method method;
    String[] pathList;
}
